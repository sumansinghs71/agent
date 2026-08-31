package com.chatbot.agent.tools.mcp;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bidirectional in-process pipe, for wiring a client to a server without a subprocess.
 *
 * <p>Exists so the protocol can be tested deterministically. A stdio transport drags process
 * spawning, buffering and OS scheduling into every assertion, which makes protocol bugs and
 * environment flakiness indistinguishable.
 */
public class InMemoryTransport implements McpTransport {

    private final BlockingQueue<String> inbound;
    private final BlockingQueue<String> outbound;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private InMemoryTransport(BlockingQueue<String> inbound, BlockingQueue<String> outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    /** @return a connected pair: {@code [clientSide, serverSide]} */
    public static InMemoryTransport[] pair() {
        BlockingQueue<String> a = new LinkedBlockingQueue<>();
        BlockingQueue<String> b = new LinkedBlockingQueue<>();
        return new InMemoryTransport[]{
                new InMemoryTransport(b, a),
                new InMemoryTransport(a, b)
        };
    }

    @Override
    public void send(String json) {
        if (!open.get()) {
            throw new IllegalStateException("transport is closed");
        }
        outbound.add(json);
    }

    @Override
    public String receive(long timeoutMillis) throws InterruptedException, TimeoutException {
        String message = inbound.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (message == null) {
            if (!open.get()) {
                return null;
            }
            throw new TimeoutException("no MCP message within " + timeoutMillis + "ms");
        }
        return message;
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        open.set(false);
    }
}
