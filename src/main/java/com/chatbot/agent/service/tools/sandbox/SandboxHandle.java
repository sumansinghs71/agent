package com.chatbot.agent.service.tools.sandbox;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SandboxHandle - A launched Python process plus a reliable way to kill it.
 *
 * <p>{@code Process.destroyForcibly()} is enough for a local subprocess but not for a container:
 * killing the local {@code docker run} client does not reliably stop the container. Each
 * {@link PythonSandbox} therefore supplies its own killer.
 *
 * <p>{@link #forceKill()} is idempotent and safe to call from a watchdog thread while another
 * thread is blocked reading the process's stdout - that is exactly its purpose. Killing the
 * process causes the blocked {@code readLine()} to return {@code null}, which is what unblocks
 * the protocol loop.
 */
@Slf4j
public final class SandboxHandle implements AutoCloseable {

    private final Process process;
    private final Runnable killer;
    private final String descriptor;
    private final AtomicBoolean killed = new AtomicBoolean(false);

    public SandboxHandle(Process process, Runnable killer, String descriptor) {
        this.process = process;
        this.killer = killer;
        this.descriptor = descriptor;
    }

    public Process process() {
        return process;
    }

    /**
     * @return human-readable identifier for logs (pid or container name)
     */
    public String descriptor() {
        return descriptor;
    }

    /**
     * @return true if {@link #forceKill()} was invoked - lets the caller distinguish a timeout
     * kill from the process exiting on its own
     */
    public boolean wasKilled() {
        return killed.get();
    }

    /**
     * Terminate the sandbox. Idempotent; never throws.
     */
    public void forceKill() {
        if (!killed.compareAndSet(false, true)) {
            return;
        }
        log.warn("Force-killing sandbox process [{}]", descriptor);
        try {
            killer.run();
        } catch (Exception e) {
            log.error("Error force-killing sandbox process [{}]", descriptor, e);
        }
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            forceKill();
        }
    }
}
