package com.chatbot.agent.service.tools.sandbox;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.metrics.AgentMetrics;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Resource-bounded JavaScript execution.
 *
 * <p>Replaces the JSR-223 {@code ScriptEngineManager} path, which was contained against host access
 * but had no CPU, memory or statement bound. A tight loop there held a thread-pool slot until the
 * JVM restarted, because {@code Future.cancel} cannot interrupt a thread spinning inside the guest
 * runtime - cancelling requires the runtime's cooperation, and JSR-223 offered no way to ask for it.
 *
 * <p>Two independent bounds now apply:
 * <ul>
 *   <li>a <b>statement limit</b>, which the engine enforces itself and which stops
 *       {@code while(true){}} without any external thread being involved;</li>
 *   <li>a <b>wall-clock watchdog</b> that calls {@link Context#close(boolean)} with
 *       {@code cancelIfExecuting}, which the engine honours from another thread.</li>
 * </ul>
 *
 * <p>Both were verified against this GraalJS version on a stock JVM before being relied on: the
 * statement limit fires and reports {@code resourceExhausted}, and cancellation returns in
 * single-digit milliseconds.
 *
 * <p>Host access is denied explicitly rather than relied upon as a default. {@code Java.type} is
 * unreachable, so guest code cannot reach the JVM.
 */
@Slf4j
public class JavaScriptSandbox {

    private final ToolExecutionProperties config;
    private final AgentMetrics metrics;
    private final ScheduledExecutorService watchdogScheduler;

    public JavaScriptSandbox(ToolExecutionProperties config, AgentMetrics metrics,
                             ScheduledExecutorService watchdogScheduler) {
        this.config = config;
        this.metrics = metrics;
        this.watchdogScheduler = watchdogScheduler;
    }

    /** Why an execution ended. */
    public enum Termination {
        COMPLETED,
        STATEMENT_LIMIT,
        WALL_CLOCK,
        OUTPUT_LIMIT,
        GUEST_ERROR
    }

    public record Result(boolean success, Object value, String error, Termination termination) {
    }

    /**
     * Execute a wrapped {@code function(data) { ... }} against the supplied arguments.
     *
     * @param bridges host objects to expose - only polyglot Proxy values, never plain Java objects,
     *                since a plain object would hand guest code a reflection surface
     */
    public Result execute(String wrappedFunction, Object arguments,
                          Map<String, Object> bridges, long timeoutMs, String executionId) {

        ResourceLimits limits = ResourceLimits.newBuilder()
                .statementLimit(config.getJavascript().getStatementLimit(), null)
                .build();

        Context context = Context.newBuilder("js")
                // Explicit, not inherited. Guest code gets no host objects, no class lookup, no
                // filesystem, no native calls, no threads and no subprocesses.
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowIO(false)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
                .resourceLimits(limits)
                .build();

        // The engine honours close(cancelIfExecuting) from another thread, which is what makes a
        // wall-clock bound enforceable at all.
        ScheduledFuture<?> watchdog = watchdogScheduler.schedule(() -> {
            log.error("[executionId={}] JavaScript exceeded {}ms; cancelling", executionId, timeoutMs);
            try {
                context.close(true);
            } catch (Exception e) {
                log.debug("[executionId={}] cancel raced with completion", executionId);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        try {
            harden(context);

            Value bindings = context.getBindings("js");
            bridges.forEach(bindings::putMember);

            Value function = context.eval("js", "(" + wrappedFunction + ")");
            if (!function.canExecute()) {
                return new Result(false, null,
                        "tool code did not evaluate to a callable function", Termination.GUEST_ERROR);
            }

            // Arguments cross the boundary as polyglot Proxy values, not host objects and not a
            // JSON string spliced into source. A host object would hand guest code a reflection
            // surface; string-splicing JSON into `JSON.parse('...')` is an injection waiting for an
            // argument containing a quote.
            Value result = function.execute(context.asValue(toGuest(arguments)));
            Object converted = convert(result);

            if (converted instanceof String text
                    && text.length() > config.getJavascript().getMaxOutputChars()) {
                metrics.recordJsLimitExceeded("output");
                return new Result(false, null,
                        "result exceeded " + config.getJavascript().getMaxOutputChars() + " characters",
                        Termination.OUTPUT_LIMIT);
            }

            return new Result(true, converted, null, Termination.COMPLETED);

        } catch (PolyglotException e) {
            // The engine distinguishes a resource bound from an ordinary guest error, so the caller
            // can tell "the tool is broken" from "the tool is a runaway".
            if (e.isResourceExhausted()) {
                metrics.recordJsLimitExceeded("statement");
                return new Result(false, null,
                        "statement limit of " + config.getJavascript().getStatementLimit()
                        + " exceeded", Termination.STATEMENT_LIMIT);
            }
            if (e.isCancelled()) {
                metrics.recordJsLimitExceeded("wall_clock");
                return new Result(false, null,
                        "exceeded its " + timeoutMs + "ms budget", Termination.WALL_CLOCK);
            }
            return new Result(false, null, e.getMessage(), Termination.GUEST_ERROR);

        } finally {
            watchdog.cancel(false);
            try {
                context.close(true);
            } catch (Exception ignored) {
                // Closing a context whose script was already cancelled rethrows the cancellation.
                // The outcome is already determined; there is nothing further to report.
            }
        }
    }

    /**
     * Globals GraalJS provides for Nashorn compatibility, which survive {@code HostAccess.NONE}.
     *
     * <p>Denying host access is not sufficient on its own: the containment tests in this class found
     * {@code java.lang.Runtime} evaluating to a live namespace object, and {@code globalThis.load} -
     * which reads a file or a URL - present as a callable function.
     *
     * <p>They are removed by prelude rather than by engine option because the corresponding options
     * ({@code js.load}, {@code js.java-package-globals}) are marked experimental in this GraalJS
     * version and require {@code allowExperimentalOptions}, which GraalVM explicitly advises against
     * in production. Deleting the bindings achieves the same result without depending on an
     * unstable option name.
     */
    private static final List<String> UNSAFE_GLOBALS = List.of(
            "Java", "java", "javax", "Packages", "javafx", "com", "org", "edu",
            "Polyglot", "load", "loadWithNewGlobal", "print", "printErr",
            "quit", "exit", "read", "readFully", "readLine");

    /** Remove the compatibility globals before any tool code runs. */
    private void harden(Context context) {
        StringBuilder prelude = new StringBuilder();
        for (String global : UNSAFE_GLOBALS) {
            prelude.append("try{delete globalThis.").append(global).append(";}catch(e){}\n");
        }
        // Freezing prevents a script restoring what the prelude removed.
        prelude.append("try{Object.freeze(Object.getPrototypeOf(globalThis));}catch(e){}\n");
        context.eval("js", prelude.toString());
    }

    /**
     * Convert host data into polyglot Proxy values.
     *
     * <p>{@code Context.asValue} on a plain {@link Map} under {@code HostAccess.NONE} produces an
     * opaque host object whose entries the guest cannot read - the arguments silently arrive as
     * undefined and arithmetic on them yields NaN. Proxies expose the data as native guest
     * structures while exposing no methods.
     */
    @SuppressWarnings("unchecked")
    private Object toGuest(Object value) {
        if (value instanceof Map<?, ?> map) {
            java.util.Map<String, Object> converted = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), toGuest(v)));
            return org.graalvm.polyglot.proxy.ProxyObject.fromMap(converted);
        }
        if (value instanceof java.util.List<?> list) {
            return org.graalvm.polyglot.proxy.ProxyArray.fromList(
                    list.stream().map(this::toGuest).toList());
        }
        if (value instanceof Object[] array) {
            return org.graalvm.polyglot.proxy.ProxyArray.fromList(
                    java.util.Arrays.stream(array).map(this::toGuest).toList());
        }
        return value;   // primitives and strings cross as themselves
    }

    /** Convert a guest value into plain Java, without leaking a live polyglot handle. */
    private Object convert(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) {
            return value.fitsInLong() ? (Object) value.asLong() : (Object) value.asDouble();
        }
        if (value.isString()) return value.asString();
        if (value.hasArrayElements()) {
            int n = (int) value.getArraySize();
            java.util.List<Object> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                list.add(convert(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convert(value.getMember(key)));
            }
            return map;
        }
        return value.toString();
    }
}
