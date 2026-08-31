package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.config.ToolExecutionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * DockerSandbox - Runs Python inside a locked-down, disposable container.
 *
 * <p>The script is bind-mounted <b>read-only</b> rather than piped, because stdin is reserved for
 * the {@code eztool()} protocol. That is what lets the existing stdin/stdout protocol survive
 * containerization untouched.
 *
 * <p>{@code --network none} is safe here for the same reason: {@code eztool()} calls travel over
 * the pipe, not the network. Tool code therefore gets no outbound network at all, which removes
 * data exfiltration and SSRF from Python tools as a class of problem.
 *
 * <p>Containment applied:
 * <ul>
 *   <li>{@code --network none} - no network access</li>
 *   <li>{@code --memory} / {@code --cpus} / {@code --pids-limit} - bounded blowups; an infinite
 *       loop burns a fraction of one core instead of a whole one</li>
 *   <li>{@code --read-only} plus a small noexec tmpfs - no persistence, no dropped binaries</li>
 *   <li>{@code --user 65534:65534}, {@code --cap-drop ALL}, {@code --security-opt no-new-privileges}</li>
 * </ul>
 *
 * <p>The container is given a deterministic name so a watchdog can {@code docker kill} it. Killing
 * the local {@code docker run} client alone is not a reliable stop.
 *
 * <p><b>Requires</b> the JVM to reach a Docker daemon. If this app itself runs in a container, the
 * staged script directory must be on a volume the daemon can see at the same path.
 */
@Component
@Slf4j
public class DockerSandbox implements PythonSandbox {

    public static final String ID = "DOCKER";

    private static final String CONTAINER_SCRIPT_PATH = "/script.py";

    private final ToolExecutionProperties config;

    public DockerSandbox(ToolExecutionProperties config) {
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SandboxHandle launch(Path scriptPath, String executionId, long timeoutMs) throws IOException {
        ToolExecutionProperties.DockerConfig docker = config.getPython().getDocker();

        // Unique per launch, not per context: one context may run several Python tools via eztool().
        String containerName = "eztool-" + executionId + "-" + UUID.randomUUID().toString().substring(0, 8);

        List<String> cmd = buildCommand(scriptPath, containerName);

        log.debug("[executionId={}] Launching container {}: {}", executionId, containerName, cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        return new SandboxHandle(process, () -> killContainer(docker.getBinary(), containerName, process),
                "docker:" + containerName);
    }

    /**
     * Visible for testing - the containment flags are the security contract, so they are asserted
     * directly rather than inferred from a running container.
     */
    List<String> buildCommand(Path scriptPath, String containerName) {
        ToolExecutionProperties.PythonConfig py = config.getPython();
        ToolExecutionProperties.DockerConfig docker = py.getDocker();

        List<String> cmd = new ArrayList<>(List.of(
                docker.getBinary(), "run",
                "--rm",
                "-i",                                   // keep stdin open for the eztool() protocol
                "--name", containerName,
                "--network", docker.getNetwork(),
                "--memory", docker.getMemory(),
                "--memory-swap", docker.getMemory(),    // equal to --memory disables swap
                "--cpus", docker.getCpus(),
                "--pids-limit", String.valueOf(docker.getPidsLimit()),
                "--user", docker.getUser(),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges"
        ));

        if (docker.isReadOnly()) {
            cmd.add("--read-only");
            // Ownership matters. A tmpfs mounts root-owned 0755, so a container running as an
            // unprivileged uid cannot write to its own scratch space at all. Worse, specifying
            // explicit tmpfs options REPLACES Docker's defaults, so hardening /tmp this way also
            // silently removed the 1777 mode it would otherwise have had.
            //
            // uid/gid is preferred over mode=1777: the sandbox user owns the mount and nothing else
            // can write to it, rather than making it world-writable. Both were verified against a
            // live daemon - see DockerSandboxAdversarialTest#workspaceIsWritableButNoexec.
            String owner = "uid=" + uidOf(docker.getUser()) + ",gid=" + gidOf(docker.getUser());

            cmd.add("--tmpfs");
            cmd.add("/tmp:rw,noexec,nosuid," + owner + ",size=" + docker.getTmpfsSize());
            // The one writable location, and it dies with the container. noexec means code cannot
            // drop a binary here and run it.
            cmd.add("--tmpfs");
            cmd.add(docker.getWorkspace()
                    + ":rw,noexec,nosuid," + owner + ",size=" + docker.getWorkspaceSize());
        }

        cmd.add("--workdir");
        cmd.add(docker.getWorkspace());

        // Grace period before SIGKILL when the watchdog stops the container.
        cmd.add("--stop-timeout");
        cmd.add(String.valueOf(docker.getStopTimeoutSeconds()));

        // Discard container logs: output is consumed over the pipe, so the daemon has no reason to
        // also persist it to disk where it would survive --rm and could be flooded.
        cmd.add("--log-driver");
        cmd.add("none");

        // Environment allowlist. A container starts with an empty environment unless told
        // otherwise; only explicitly named variables are forwarded, and only if actually set.
        if (docker.getEnvAllowlist() != null) {
            for (String name : docker.getEnvAllowlist()) {
                String value = System.getenv(name);
                if (value != null) {
                    cmd.add("--env");
                    cmd.add(name + "=" + value);
                }
            }
        }

        // Read-only bind mount of the single staged script - the container sees nothing else.
        cmd.add("-v");
        cmd.add(scriptPath.toAbsolutePath() + ":" + CONTAINER_SCRIPT_PATH + ":ro");

        if (docker.getExtraArgs() != null) {
            cmd.addAll(docker.getExtraArgs());
        }

        cmd.add(docker.getImage());

        // In-container wall clock, independent of the host watchdog. If the JVM's watchdog thread
        // is starved or the daemon connection is lost, the container still terminates itself.
        // --signal=KILL because a SIGTERM handler in tool code could otherwise ignore it.
        cmd.add("timeout");
        cmd.add("--signal=KILL");
        cmd.add(String.valueOf(docker.getHardTimeoutSeconds()));

        cmd.add("python3");
        if (py.getInterpreterArgs() != null) {
            cmd.addAll(py.getInterpreterArgs());
        }
        cmd.add(CONTAINER_SCRIPT_PATH);

        return cmd;
    }

    /** uid from a "uid:gid" spec; falls back to nobody rather than to root. */
    static String uidOf(String user) {
        if (user == null || user.isBlank()) return "65534";
        String uid = user.split(":", 2)[0].trim();
        return uid.matches("\\d+") ? uid : "65534";
    }

    /** gid from a "uid:gid" spec; falls back to nogroup rather than to root. */
    static String gidOf(String user) {
        if (user == null || !user.contains(":")) return "65534";
        String gid = user.split(":", 2)[1].trim();
        return gid.matches("\\d+") ? gid : "65534";
    }

    /**
     * Never mount the Docker socket, the host root, or the invoking user's home directory.
     * Asserted by test rather than left as a comment, because a well-meaning "just add a volume"
     * change is exactly how container escapes get introduced.
     */
    static final java.util.List<String> FORBIDDEN_MOUNT_SUBSTRINGS = java.util.List.of(
            "/var/run/docker.sock", "docker.sock", ":/:", "/etc/passwd", "/.ssh", "/.aws", "/.azure");

    /**
     * Stop the container, then the local client. Order matters: killing only the client can leave
     * the container running.
     */
    private void killContainer(String dockerBinary, String containerName, Process clientProcess) {
        try {
            Process kill = new ProcessBuilder(dockerBinary, "kill", containerName)
                    .redirectErrorStream(true)
                    .start();
            if (!kill.waitFor(10, TimeUnit.SECONDS)) {
                kill.destroyForcibly();
                log.error("'docker kill {}' did not complete within 10s", containerName);
            } else if (kill.exitValue() != 0) {
                // Usual cause: the container already exited on its own. Not an error.
                log.debug("'docker kill {}' exited {} (container likely already gone)",
                        containerName, kill.exitValue());
            } else {
                log.warn("Container {} killed", containerName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted killing container {}", containerName, e);
        } catch (Exception e) {
            log.error("Failed to kill container {}", containerName, e);
        } finally {
            clientProcess.destroyForcibly();
        }
    }
}
