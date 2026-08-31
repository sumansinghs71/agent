package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.*;
import com.chatbot.agent.model.ExecutionMetadata;
import com.chatbot.agent.model.ToolCallInfo;
import com.chatbot.agent.security.InvocationPrincipal;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * ExecutionContext - Manages the lifecycle of a tool execution chain
 * 
 * CRITICAL: This class MUST be used with try-with-resources to ensure cleanup
 * Each request gets its own instance - NEVER shared between requests
 * 
 * Thread-safe for concurrent operations within same context
 */
@Slf4j
public class ExecutionContext implements AutoCloseable {
    
    // Identity
    private final String executionId;
    private final Long chatbotId;
    private final String userId;
    /**
     * The authority every tool call in this chain is made on behalf of. Held on the context so that
     * nested eztool() calls are evaluated against the same principal as the outermost call - a
     * nested call must never gain authority the original caller did not have.
     */
    private final InvocationPrincipal principal;
    private final String requestId;
    private final LocalDateTime createdAt;
    
    // Call chain tracking
    private final Deque<ToolCallInfo> callStack;
    private final Set<String> calledTools;
    private int totalToolCalls;
    
    // Timing and limits
    private final long startTimeMs;
    private final long aggregateTimeoutMs;
    private final int maxDepth;
    private final int maxTotalCalls;
    
    // State tracking
    private volatile ExecutionMetadata.ExecutionState state;
    private volatile boolean closed;
    
    // Resource tracking (for cleanup)
    private final List<Process> activeProcesses;
    private final List<AutoCloseable> resources;
    
    // Thread safety
    private final ReentrantLock lock;
    
    // Configuration
    private final ToolExecutionProperties config;
    
    /**
     * Constructor - Creates new execution context
     * 
     * @param chatbotId The chatbot ID
     * @param userId User making the request
     * @param config Configuration properties
     */
    public ExecutionContext(Long chatbotId, String userId, String requestId, ToolExecutionProperties config) {
        this(chatbotId, InvocationPrincipal.of(userId), requestId, config);
    }

    public ExecutionContext(Long chatbotId, InvocationPrincipal principal, String requestId,
                            ToolExecutionProperties config) {
        this.requestId = requestId;
        this.executionId = UUID.randomUUID().toString();
        this.chatbotId = chatbotId;
        this.principal = principal;
        this.userId = principal.getName();
        this.config = config;
        this.createdAt = LocalDateTime.now();
        
        this.callStack = new ArrayDeque<>();
        this.calledTools = new HashSet<>();
        this.totalToolCalls = 0;
        
        this.startTimeMs = System.currentTimeMillis();
        this.aggregateTimeoutMs = config.getTimeout().getAggregateTimeoutMs();
        this.maxDepth = config.getInterToolCommunication().getMaxChainDepth();
        this.maxTotalCalls = config.getSecurity().getMaxToolCallsPerRequest();
        
        this.state = ExecutionMetadata.ExecutionState.ACTIVE;
        this.closed = false;
        
        this.activeProcesses = new CopyOnWriteArrayList<>();
        this.resources = new CopyOnWriteArrayList<>();
        
        this.lock = new ReentrantLock();
        
        log.info("[executionId={}] ExecutionContext created for chatbot={}, user={}", 
                executionId, chatbotId, userId);
    }
    
    /**
     * Register a tool call - validates depth, circular deps, timeout
     * MUST be called before executing any tool
     * 
     * @param toolId The tool being called
     * @throws MaxCallsExceededException if too many total calls
     * @throws MaxDepthExceededException if chain too deep
     * @throws CircularDependencyException if circular dependency
     * @throws ToolExecutionTimeoutException if timeout exceeded
     */
    public void registerToolCall(String toolId) {
        lock.lock();
        try {
            checkNotClosed();
            // Re-enabled: was commented out in 1a5f39f, which disabled aggregate-timeout
            // enforcement at the point a new tool call enters the chain.
            checkTimeout();

            // Check max total calls
            totalToolCalls++;
            if (totalToolCalls > maxTotalCalls) {
                throw new MaxCallsExceededException(
                    String.format("Maximum tool calls (%d) exceeded. Possible abuse detected.", maxTotalCalls),
                    maxTotalCalls,
                    totalToolCalls
                );
            }
            
            // Check depth
            if (callStack.size() >= maxDepth) {
                throw new MaxDepthExceededException(
                    String.format("Maximum call depth (%d) exceeded. Chain: %s", 
                        maxDepth, getCallChainString()),
                    maxDepth,
                    callStack.size(),
                    getCallChainList()
                );
            }
            
            // Check circular dependency
            if (config.getInterToolCommunication().isCircularDependencyCheck() && calledTools.contains(toolId)) {
                boolean isAlreadyOnStack = callStack.stream()
                        .anyMatch(call -> call.getToolId().equals(toolId));

                if (isAlreadyOnStack) {
                    throw new CircularDependencyException(
                            String.format("Circular dependency detected: '%s' already in call chain: %s",
                                    toolId, getCallChainString()),
                            toolId,
                            getCallChainList()
                    );
                }
            }
            
            // Register the call
            ToolCallInfo callInfo = new ToolCallInfo(toolId, System.currentTimeMillis());
            callStack.push(callInfo);
            calledTools.add(toolId);
            
            log.debug("[executionId={}] Tool '{}' registered. Depth: {}, Total calls: {}", 
                    executionId, toolId, callStack.size(), totalToolCalls);
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Unregister a tool call when it completes successfully
     * MUST be called in finally block after tool execution
     * 
     * @param toolId The tool that completed
     */
    public void unregisterToolCall(String toolId) {
        lock.lock();
        try {
            if (callStack.isEmpty()) {
                log.warn("[executionId={}] Attempted to unregister tool '{}' but call stack is empty", 
                        executionId, toolId);
                return;
            }
            
            ToolCallInfo removed = callStack.pop();
            if (!removed.getToolId().equals(toolId)) {
                log.error("[executionId={}] Tool call stack corrupted! Expected '{}', got '{}'", 
                        executionId, toolId, removed.getToolId());
            }
            
            // Mark as completed
            removed.complete(System.currentTimeMillis());

            calledTools.remove(toolId);

            long duration = removed.getDurationMs();
            log.debug("[executionId={}] Tool '{}' unregistered. Duration: {}ms, Remaining depth: {}", 
                    executionId, toolId, duration, callStack.size());
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Unregister a tool call when it fails
     * 
     * @param toolId The tool that failed
     * @param error Error message
     */
    public void unregisterToolCallWithError(String toolId, String error) {
        lock.lock();
        try {
            if (callStack.isEmpty()) {
                log.warn("[executionId={}] Attempted to unregister failed tool '{}' but call stack is empty", 
                        executionId, toolId);
                return;
            }
            
            ToolCallInfo removed = callStack.pop();
            if (!removed.getToolId().equals(toolId)) {
                log.error("[executionId={}] Tool call stack corrupted! Expected '{}', got '{}'", 
                        executionId, toolId, removed.getToolId());
            }
            
            // Mark as failed
            removed.fail(System.currentTimeMillis(), error);
            
            log.debug("[executionId={}] Tool '{}' unregistered with error. Duration: {}ms", 
                    executionId, toolId, removed.getDurationMs());
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Register a process for cleanup
     * Called when starting Python subprocess
     * 
     * @param process The process to track
     */
    public void registerProcess(Process process) {
        lock.lock();
        try {
            checkNotClosed();
            activeProcesses.add(process);
            log.debug("[executionId={}] Process registered. Active processes: {}", 
                    executionId, activeProcesses.size());
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Unregister a process after completion
     * 
     * @param process The process to untrack
     */
    public void unregisterProcess(Process process) {
        lock.lock();
        try {
            activeProcesses.remove(process);
            log.debug("[executionId={}] Process unregistered. Active processes: {}", 
                    executionId, activeProcesses.size());
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Register any resource that needs cleanup
     * 
     * @param resource Resource to track
     */
    public void registerResource(AutoCloseable resource) {
        lock.lock();
        try {
            checkNotClosed();
            resources.add(resource);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Check if aggregate timeout has been exceeded
     * 
     * @throws ToolExecutionTimeoutException if timeout exceeded
     */
    public void checkTimeout() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed > aggregateTimeoutMs) {
            state = ExecutionMetadata.ExecutionState.TIMEOUT;
            throw new ToolExecutionTimeoutException(
                String.format("Aggregate timeout (%dms) exceeded. Elapsed: %dms. Chain: %s", 
                    aggregateTimeoutMs, elapsed, getCallChainString()),
                aggregateTimeoutMs,
                elapsed,
                getCurrentToolId(),
                getCallChainList()
            );
        }
    }
    
    /**
     * Get remaining time in milliseconds
     * 
     * @return Remaining time before timeout
     */
    public long getRemainingTimeMs() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        return Math.max(0, aggregateTimeoutMs - elapsed);
    }
    
    /**
     * Get elapsed time in milliseconds
     * 
     * @return Time elapsed since context creation
     */
    public long getElapsedTimeMs() {
        return System.currentTimeMillis() - startTimeMs;
    }
    
    /**
     * Check if context has been closed
     * 
     * @throws ContextClosedException if context is closed
     */
    private void checkNotClosed() {
        if (closed) {
            throw new ContextClosedException(
                String.format("ExecutionContext already closed: %s", executionId),
                executionId
            );
        }
    }
    
    /**
     * Get current call chain as string for error messages
     * 
     * @return Call chain as "toolA → toolB → toolC"
     */
    public String getCallChainString() {
        lock.lock();
        try {
            if (callStack.isEmpty()) {
                return "[empty]";
            }
            // Reverse order for display (oldest to newest)
            List<String> chain = new ArrayList<>();
            Iterator<ToolCallInfo> it = callStack.descendingIterator();
            while (it.hasNext()) {
                chain.add(it.next().getToolId());
            }
            return String.join(" → ", chain);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get current call chain as list
     * 
     * @return List of tool IDs in chain
     */
    public List<String> getCallChainList() {
        lock.lock();
        try {
            List<String> chain = new ArrayList<>();
            Iterator<ToolCallInfo> it = callStack.descendingIterator();
            while (it.hasNext()) {
                chain.add(it.next().getToolId());
            }
            return chain;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get current tool ID (top of stack)
     * 
     * @return Current tool ID or null if stack empty
     */
    public String getCurrentToolId() {
        lock.lock();
        try {
            return callStack.isEmpty() ? null : callStack.peek().getToolId();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get current depth
     * 
     * @return Current call chain depth
     */
    public int getCurrentDepth() {
        lock.lock();
        try {
            return callStack.size();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get execution metadata for logging/debugging
     * 
     * @return ExecutionMetadata snapshot
     */
    public ExecutionMetadata getMetadata() {
        lock.lock();
        try {
            return ExecutionMetadata.builder()
                .executionId(executionId)
                .chatbotId(chatbotId)
                .userId(userId)
                .totalToolCalls(totalToolCalls)
                .currentDepth(callStack.size())
                .elapsedTimeMs(getElapsedTimeMs())
                .remainingTimeMs(getRemainingTimeMs())
                .callChain(new ArrayList<>(callStack))
                .state(state)
                .startTime(createdAt)
                .build();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * CRITICAL: Cleanup all resources
     * Called automatically by try-with-resources or manually in finally
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                log.debug("[executionId={}] ExecutionContext already closed, skipping", executionId);
                return;
            }
            
            closed = true;
            if (state == ExecutionMetadata.ExecutionState.ACTIVE) {
                state = ExecutionMetadata.ExecutionState.COMPLETED;
            }
            
            log.info("[executionId={}] Closing ExecutionContext. Total tools called: {}, Duration: {}ms, Final state: {}", 
                    executionId, totalToolCalls, getElapsedTimeMs(), state);
            
            // 1. Kill all active processes
            for (Process process : activeProcesses) {
                try {
                    if (process.isAlive()) {
                        log.warn("[executionId={}] Forcibly destroying active process", executionId);
                        process.destroyForcibly();
                        boolean terminated = process.waitFor(
                            config.getTimeout().getProcessWaitTimeoutMs(), 
                            java.util.concurrent.TimeUnit.MILLISECONDS
                        );
                        if (!terminated) {
                            log.error("[executionId={}] Process did not terminate within timeout", executionId);
                        }
                    }
                } catch (Exception e) {
                    log.error("[executionId={}] Error destroying process", executionId, e);
                }
            }
            activeProcesses.clear();
            
            // 2. Close all registered resources
            for (AutoCloseable resource : resources) {
                try {
                    resource.close();
                } catch (Exception e) {
                    log.error("[executionId={}] Error closing resource", executionId, e);
                }
            }
            resources.clear();
            
            // 3. Clear tracking structures
            callStack.clear();
            calledTools.clear();
            
            // 4. Mark as closed
            state = ExecutionMetadata.ExecutionState.CLOSED;
            
            log.info("[executionId={}] ExecutionContext closed successfully", executionId);
            
        } finally {
            lock.unlock();
        }
    }
    
    // Getters
    public String getExecutionId() { return executionId; }
    public Long getChatbotId() { return chatbotId; }
    public String getUserId() { return userId; }
    public InvocationPrincipal getPrincipal() { return principal; }
    public String getRequestId() { return requestId; }
    public int getTotalToolCalls() { return totalToolCalls; }
    public ExecutionMetadata.ExecutionState getState() { return state; }
    public boolean isClosed() { return closed; }
    public ToolExecutionProperties getConfig() { return config; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
