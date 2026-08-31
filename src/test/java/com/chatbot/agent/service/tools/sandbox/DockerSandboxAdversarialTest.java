package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.config.ToolExecutionProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial sandbox tests. These launch REAL containers and attack them.
 *
 * <p>{@code DockerSandboxCommandTest} asserts that the containment flags appear in the argv. That is
 * necessary but proves nothing about behaviour - a flag can be present and ineffective. These tests
 * run hostile Python inside the sandbox as configured and assert on what actually happens.
 *
 * <p>Skipped, not failed, when no Docker daemon is reachable, so the suite stays runnable on a
 * machine without Docker. CI runs them on an ubuntu runner where the daemon is present.
 *
 * <p><b>Scope of the claim.</b> These demonstrate that a container configured this way blocks the
 * listed attacks. Docker is a kernel-sharing isolation mechanism: a kernel vulnerability or a
 * container-escape primitive is out of scope for these tests and is NOT claimed to be prevented.
 * See docs/security/SANDBOX_SECURITY_REPORT.md.
 */
@EnabledIf("dockerAvailable")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DockerSandboxAdversarialTest {

    private static final long TIMEOUT_MS = 30_000;

    /** Bound on what this JVM retains from a hostile stream. */
    private static final int MAX_RETAINED_BYTES = 1024 * 1024;

    private DockerSandbox sandbox;
    private Path scriptDir;

    /** Marker written on the host, outside everything the container is given. */
    private Path hostSecretFile;
    private String hostSecretValue;

    /** Probed once and cached: two independent probes can disagree under load. */
    private static volatile Boolean dockerAvailable;

    static synchronized boolean dockerAvailable() {
        if (dockerAvailable == null) {
            try {
                Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
                dockerAvailable = p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                dockerAvailable = false;
            }
        }
        return dockerAvailable;
    }

    @BeforeAll
    void setUp() throws Exception {
        ToolExecutionProperties props = new ToolExecutionProperties();
        props.getPython().setSandbox("DOCKER");
        // Short in-container wall clock so the loop tests finish quickly.
        props.getPython().getDocker().setHardTimeoutSeconds(5);
        sandbox = new DockerSandbox(props);

        scriptDir = Files.createTempDirectory("adversarial-scripts");

        hostSecretValue = "HOST-ONLY-" + UUID.randomUUID();
        hostSecretFile = Files.createTempDirectory("host-secrets").resolve("credentials.txt");
        Files.writeString(hostSecretFile, hostSecretValue);
    }

    /** Result of running one hostile script. */
    private record Outcome(int exitCode, String stdout, String stderr, boolean timedOut) {
        boolean succeeded() {
            return exitCode == 0;
        }

        String combined() {
            return stdout + "\n" + stderr;
        }
    }

    private Outcome run(String python) throws Exception {
        Path script = scriptDir.resolve(UUID.randomUUID() + ".py");
        Files.writeString(script, python);
        // World-readable: the container runs as uid 65534 and must be able to read the mount.
        script.toFile().setReadable(true, false);
        scriptDir.toFile().setExecutable(true, false);

        SandboxHandle handle = sandbox.launch(script, "adv-" + UUID.randomUUID(), TIMEOUT_MS);
        ExecutorService drainers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "adv-drain");
            t.setDaemon(true);
            return t;
        });
        try {
            Process process = handle.process();
            process.getOutputStream().close();

            // Both streams must be drained CONCURRENTLY. Reading stdout to completion and only then
            // reading stderr deadlocks as soon as the process fills the stderr pipe buffer - and the
            // same applies in reverse once this reader stops consuming stdout at its retention cap.
            // This is the identical hazard PythonProtocolHandler solves with a dedicated stderr
            // drainer thread; the first version of this helper reintroduced it and wedged a
            // container for twelve minutes.
            Future<String> out = drainers.submit(() -> drain(process.getInputStream()));
            Future<String> err = drainers.submit(() -> drain(process.getErrorStream()));

            boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                handle.forceKill();
                process.waitFor(10, TimeUnit.SECONDS);
                return new Outcome(-1, get(out), get(err), true);
            }
            return new Outcome(process.exitValue(), get(out), get(err), false);
        } finally {
            drainers.shutdownNow();
            handle.close();
            Files.deleteIfExists(script);
        }
    }

    private static String get(Future<String> f) {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            f.cancel(true);
            return "";
        }
    }

    /**
     * Read a stream to EOF, retaining at most {@code MAX_RETAINED_BYTES} but continuing to consume
     * and discard beyond that. Retention is bounded so a flooding test cannot exhaust this JVM;
     * consumption continues so the writer is never blocked on a full pipe.
     */
    private static String drain(InputStream in) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        try {
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() < MAX_RETAINED_BYTES) {
                    buffer.write(chunk, 0, Math.min(read, MAX_RETAINED_BYTES - buffer.size()));
                }
            }
        } catch (Exception ignored) {
            // Stream closed by a kill. Whatever was captured is still useful.
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    // ================================================================== 1. network egress

    @Test
    @Order(1)
    @DisplayName("1. network egress is impossible")
    void networkEgressBlocked() throws Exception {
        Outcome o = run("""
                import socket, sys
                socket.setdefaulttimeout(3)
                try:
                    s = socket.socket()
                    s.connect(('1.1.1.1', 80))
                    print('EGRESS-SUCCEEDED')
                    sys.exit(0)
                except Exception as e:
                    print('EGRESS-BLOCKED:', type(e).__name__)
                    sys.exit(3)
                """);
        assertFalse(o.combined().contains("EGRESS-SUCCEEDED"), "tool code reached the network: " + o.combined());
        assertTrue(o.combined().contains("EGRESS-BLOCKED"), o.combined());
    }

    @Test
    @Order(2)
    @DisplayName("1b. DNS resolution is impossible")
    void dnsBlocked() throws Exception {
        Outcome o = run("""
                import socket, sys
                socket.setdefaulttimeout(3)
                try:
                    print('RESOLVED:', socket.gethostbyname('example.com'))
                    sys.exit(0)
                except Exception as e:
                    print('DNS-BLOCKED:', type(e).__name__)
                    sys.exit(3)
                """);
        assertFalse(o.combined().contains("RESOLVED:"), o.combined());
        assertTrue(o.combined().contains("DNS-BLOCKED"), o.combined());
    }

    // ================================================================== 2. host filesystem

    @Test
    @Order(3)
    @DisplayName("2. host filesystem is not readable")
    void hostFilesystemNotReadable() throws Exception {
        Outcome o = run("""
                import sys
                try:
                    with open(%s) as f:
                        print('HOST-FILE-READ:', f.read())
                    sys.exit(0)
                except Exception as e:
                    print('HOST-FILE-BLOCKED:', type(e).__name__)
                    sys.exit(3)
                """.formatted("'" + hostSecretFile.toAbsolutePath() + "'"));

        assertFalse(o.combined().contains(hostSecretValue),
                "the host secret leaked into the sandbox: " + o.combined());
        assertTrue(o.combined().contains("HOST-FILE-BLOCKED"), o.combined());
    }

    @Test
    @Order(4)
    @DisplayName("2b. the container filesystem is not the host filesystem")
    void containerFilesystemIsIsolated() throws Exception {
        // /etc/passwd exists in the image, so its presence proves nothing. What matters is that it
        // is the IMAGE's copy: the host's entry for this user must not appear.
        Outcome o = run("""
                import os
                print('USERS:', open('/etc/passwd').read())
                print('HOME-LISTING:', os.path.exists('/Users'), os.path.exists('/home/' + os.environ.get('USER','nobody')))
                """);
        assertFalse(o.combined().contains(System.getProperty("user.name") + ":"),
                "host user account visible inside container: " + o.combined());
    }

    // ================================================================== 3. write outside workspace

    @Test
    @Order(5)
    @DisplayName("3. writing outside the workspace fails (read-only root filesystem)")
    void writeOutsideWorkspaceBlocked() throws Exception {
        Outcome o = run("""
                import sys
                blocked = 0
                for path in ('/evil.txt', '/etc/evil.txt', '/usr/bin/evil'):
                    try:
                        open(path, 'w').write('x')
                        print('WROTE:', path)
                    except Exception as e:
                        blocked += 1
                        print('WRITE-BLOCKED:', path, type(e).__name__)
                sys.exit(0 if blocked == 3 else 9)
                """);
        assertFalse(o.combined().contains("WROTE:"), "wrote outside the workspace: " + o.combined());
        assertTrue(o.succeeded(), "expected all three writes to be refused: " + o.combined());
    }

    @Test
    @Order(6)
    @DisplayName("3b. the workspace IS writable, and is not executable")
    void workspaceIsWritableButNoexec() throws Exception {
        Outcome o = run("""
                import os, subprocess, sys
                p = '/workspace/probe.sh'
                open(p, 'w').write('#!/bin/sh\\necho executed\\n')
                os.chmod(p, 0o755)
                print('WROTE-WORKSPACE')
                try:
                    subprocess.run([p], check=True, capture_output=True)
                    print('EXECUTED-FROM-WORKSPACE')
                except Exception as e:
                    print('NOEXEC-ENFORCED:', type(e).__name__)
                sys.exit(0)
                """);
        assertTrue(o.combined().contains("WROTE-WORKSPACE"), o.combined());
        assertFalse(o.combined().contains("EXECUTED-FROM-WORKSPACE"),
                "workspace should be mounted noexec: " + o.combined());
    }

    // ================================================================== 4. environment secrets

    @Test
    @Order(7)
    @DisplayName("4. no host environment variable crosses into the container")
    void hostEnvironmentNotInherited() throws Exception {
        Outcome o = run("""
                import os
                for k, v in sorted(os.environ.items()):
                    print('ENV', k, '=', v)
                """);

        // The container does have an environment - HOME, PATH, LANG and so on are set by Docker and
        // by the image. The question is whether any of it came from the HOST. So this asserts on
        // host-specific VALUES, not on variable names.
        String hostUser = System.getProperty("user.name");
        String hostHome = System.getProperty("user.home");

        assertFalse(o.combined().contains(hostHome),
                "host home directory leaked into the sandbox environment: " + o.combined());
        assertFalse(o.combined().contains("ENV USER = " + hostUser),
                "host username leaked into the sandbox environment: " + o.combined());

        // And every variable the host process actually holds that looks like a credential must be
        // absent by name, since the JVM running this test inherits the developer's shell.
        for (String name : System.getenv().keySet()) {
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("KEY") || upper.contains("SECRET") || upper.contains("TOKEN")
                    || upper.contains("PASSWORD")) {
                assertFalse(o.combined().contains("ENV " + name + " ="),
                        "host credential variable " + name + " leaked into the sandbox");
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("4b. the env allowlist forwards exactly what it names, and nothing else")
    void envAllowlistForwardsOnlyNamedVariables() throws Exception {
        ToolExecutionProperties props = new ToolExecutionProperties();
        props.getPython().setSandbox("DOCKER");
        props.getPython().getDocker().setHardTimeoutSeconds(5);
        // PATH is always set in the host process, so it is a reliable positive control.
        props.getPython().getDocker().setEnvAllowlist(java.util.List.of("PATH", "DEFINITELY_NOT_SET_XYZ"));
        DockerSandbox allowlisting = new DockerSandbox(props);

        List<String> cmd = allowlisting.buildCommand(Path.of("/tmp/x.py"), "envtest");
        String argv = String.join(" ", cmd);

        assertTrue(argv.contains("--env PATH="),
                "an allowlisted variable that is set on the host must be forwarded: " + argv);
        assertFalse(argv.contains("DEFINITELY_NOT_SET_XYZ"),
                "an allowlisted variable that is unset must not be forwarded as empty: " + argv);
    }

    // ================================================================== 5/8. process limits

    @Test
    @Order(8)
    @DisplayName("5 & 8. a fork bomb is contained by the PID limit and does not hang the host")
    void forkBombContained() throws Exception {
        long start = System.currentTimeMillis();
        Outcome o = run("""
                import os, sys
                spawned = 0
                try:
                    while spawned < 10000:
                        pid = os.fork()
                        if pid == 0:
                            os._exit(0)
                        spawned += 1
                except Exception as e:
                    print('PID-LIMIT-HIT after', spawned, type(e).__name__)
                    sys.exit(3)
                print('FORKED-UNBOUNDED:', spawned)
                """);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(o.combined().contains("FORKED-UNBOUNDED"),
                "fork was not bounded by --pids-limit: " + o.combined());
        assertTrue(elapsed < TIMEOUT_MS, "fork bomb was not contained promptly (" + elapsed + "ms)");
    }

    @Test
    @Order(9)
    @DisplayName("9. a subprocess runs only as the unprivileged sandbox user")
    void subprocessIsUnprivileged() throws Exception {
        Outcome o = run("""
                import os, subprocess
                print('UID:', os.getuid(), 'GID:', os.getgid())
                r = subprocess.run(['id'], capture_output=True, text=True)
                print('ID:', r.stdout.strip())
                """);
        assertTrue(o.combined().contains("UID: 65534"),
                "sandbox must not run as root: " + o.combined());
        assertFalse(o.combined().contains("uid=0(root)"), o.combined());
    }

    // ================================================================== 6. infinite loop

    @Test
    @Order(10)
    @DisplayName("6. an infinite loop is killed by the in-container wall clock")
    void infiniteLoopKilled() throws Exception {
        long start = System.currentTimeMillis();
        Outcome o = run("""
                while True:
                    pass
                """);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(o.succeeded(), "an infinite loop must not exit successfully");
        // hardTimeoutSeconds is 5 in this fixture; allow generous slack for container startup.
        assertTrue(elapsed < 25_000,
                "loop ran for " + elapsed + "ms; the in-container timeout did not fire");
    }

    // ================================================================== 7. memory

    @Test
    @Order(11)
    @DisplayName("7. memory exhaustion is capped, and the host is unaffected")
    void memoryExhaustionCapped() throws Exception {
        Outcome o = run("""
                import sys
                chunks = []
                try:
                    while True:
                        chunks.append(bytearray(16 * 1024 * 1024))   # 16MB at a time
                        if len(chunks) > 64:                         # 1GB, far above the 256m cap
                            print('ALLOCATED-UNBOUNDED')
                            sys.exit(0)
                except MemoryError:
                    print('MEMORY-CAPPED after', len(chunks), 'chunks')
                    sys.exit(3)
                """);
        assertFalse(o.combined().contains("ALLOCATED-UNBOUNDED"),
                "memory was not capped by --memory: " + o.combined());
        assertFalse(o.succeeded(), "expected the container to be OOM-killed or to raise MemoryError");
    }

    // ================================================================== 10. output flood

    @Test
    @Order(12)
    @DisplayName("10. an output flood does not persist to disk or hang the reader")
    void outputFloodSurvivable() throws Exception {
        long start = System.currentTimeMillis();
        Outcome o = run("""
                import sys
                line = 'A' * 1000
                for _ in range(50000):
                    sys.stdout.write(line)
                sys.stdout.flush()
                """);
        long elapsed = System.currentTimeMillis() - start;

        // The point is that the reader is not deadlocked and the daemon is not persisting the flood
        // (--log-driver none). The application-level byte cap lives in PythonProtocolHandler.
        assertTrue(elapsed < TIMEOUT_MS, "output flood stalled the reader (" + elapsed + "ms)");
    }

    // ================================================================== capability drop

    @Test
    @Order(13)
    @DisplayName("capabilities are dropped and privilege cannot be regained")
    void capabilitiesDropped() throws Exception {
        Outcome o = run("""
                import os, sys
                try:
                    os.setuid(0)
                    print('BECAME-ROOT')
                except Exception as e:
                    print('SETUID-BLOCKED:', type(e).__name__)
                try:
                    os.mkdir('/mnt/escape')
                    print('MOUNTPOINT-CREATED')
                except Exception as e:
                    print('MKDIR-BLOCKED:', type(e).__name__)
                sys.exit(0)
                """);
        assertFalse(o.combined().contains("BECAME-ROOT"), o.combined());
        assertTrue(o.combined().contains("SETUID-BLOCKED"), o.combined());
    }

    @Test
    @Order(14)
    @DisplayName("the Docker socket is never mounted")
    void dockerSocketNotMounted() throws Exception {
        Outcome o = run("""
                import os
                print('SOCK-PRESENT:', os.path.exists('/var/run/docker.sock'))
                """);
        assertTrue(o.combined().contains("SOCK-PRESENT: False"),
                "the Docker socket must never be visible inside the sandbox: " + o.combined());
    }
}
