package com.chatbot.agent.service.tools;

import com.chatbot.agent.config.ToolExecutionProperties;
import com.chatbot.agent.exception.ToolNotFoundException;
import com.chatbot.agent.model.ToolModel;
import com.chatbot.agent.model.ToolRegistryCacheEntry;
import com.chatbot.agent.repository.ToolRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ToolRegistryService - Fast O(1) tool lookup with caching
 *
 * Features:
 * - Caffeine cache for performance
 * - Automatic refresh
 * - Manual invalidation
 * - Preloading on startup
 * - Per-chatbot caching
 */
@Service
@Slf4j
public class ToolRegistryService {

    private final ToolRepository toolRepository;
    private final ToolExecutionProperties config;

    // Cache: chatbotId -> (toolId -> Tool)
    private final Cache<String, Map<String, ToolRegistryCacheEntry>> toolCache;

    public ToolRegistryService(ToolRepository toolRepository, ToolExecutionProperties config) {
        this.toolRepository = toolRepository;
        this.config = config;

        // Initialize Caffeine cache
        this.toolCache = Caffeine.newBuilder()
                .maximumSize(config.getToolRegistry().getMaxCacheSize())
                .expireAfterWrite(config.getToolRegistry().getCacheTtlSeconds(), TimeUnit.SECONDS)
                .recordStats()
                .build();

        log.info("ToolRegistryService initialized. Cache enabled: {}, TTL: {}s",
                config.getToolRegistry().isCacheEnabled(),
                config.getToolRegistry().getCacheTtlSeconds());
    }

    /**
     * Initialize cache on startup
     */
    @PostConstruct
    public void init() {
        if (config.getToolRegistry().isPreloadOnStartup()) {
            log.info("Preloading tool registry cache...");
            refreshAllCaches();
            log.info("Tool registry cache preloaded successfully");
        }
    }

    /**
     * Get a tool by chatbot ID and tool ID
     *
     * @param chatbotId The chatbot ID
     * @param toolId The tool ID
     * @return The tool
     * @throws ToolNotFoundException if tool not found
     */
    public ToolModel.Tool getTool(Long chatbotId, String toolId) {
        return getTool(chatbotId, toolId, false);
    }

    /**
     * Get a tool with optional force refresh
     *
     * @param chatbotId The chatbot ID
     * @param toolId The tool ID
     * @param forceRefresh If true, bypass cache
     * @return The tool
     * @throws ToolNotFoundException if tool not found
     */
    public ToolModel.Tool getTool(Long chatbotId, String toolId, boolean forceRefresh) {
        if (!config.getToolRegistry().isCacheEnabled() || forceRefresh) {
            // Bypass cache
            return fetchToolFromDatabase(chatbotId, toolId);
        }

        String cacheKey = getCacheKey(chatbotId);

        // Get from cache or load
        Map<String, ToolRegistryCacheEntry> chatbotTools = toolCache.get(cacheKey, key -> {
            log.debug("Cache miss for chatbot: {}. Loading from database", chatbotId);
            return loadToolsForChatbot(chatbotId);
        });

        if (chatbotTools == null || !chatbotTools.containsKey(toolId)) {
            // Tool not in cache, try refreshing
            log.debug("Tool '{}' not found in cache for chatbot: {}. Refreshing cache", toolId, chatbotId);
            invalidateCache(chatbotId);
            chatbotTools = toolCache.get(cacheKey, key -> loadToolsForChatbot(chatbotId));

            if (chatbotTools == null || !chatbotTools.containsKey(toolId)) {
                throw new ToolNotFoundException(
                        String.format("Tool '%s' not found for chatbot %d", toolId, chatbotId),
                        toolId,
                        chatbotId
                );
            }
        }

        // Update access count and return
        ToolRegistryCacheEntry entry = chatbotTools.get(toolId);
        entry.incrementAccessCount();

        log.debug("Tool '{}' retrieved from cache. Access count: {}", toolId, entry.getAccessCount());

        return entry.getTool();
    }

    /**
     * Get all tools for a chatbot
     *
     * @param chatbotId The chatbot ID
     * @return Map of tool ID to Tool
     */
    public Map<String, ToolModel.Tool> getAllTools(Long chatbotId) {
        return getAllTools(chatbotId, false);
    }

    /**
     * Get all tools for a chatbot with optional force refresh
     *
     * @param chatbotId The chatbot ID
     * @param forceRefresh If true, bypass cache
     * @return Map of tool ID to Tool
     */
    public Map<String, ToolModel.Tool> getAllTools(Long chatbotId, boolean forceRefresh) {
        if (!config.getToolRegistry().isCacheEnabled() || forceRefresh) {
            // Bypass cache
            Map<String, ToolRegistryCacheEntry> tools = loadToolsForChatbot(chatbotId);
            Map<String, ToolModel.Tool> result = new HashMap<>();
            tools.forEach((id, entry) -> result.put(id, entry.getTool()));
            return result;
        }

        String cacheKey = getCacheKey(chatbotId);

        Map<String, ToolRegistryCacheEntry> chatbotTools = toolCache.get(cacheKey, key -> {
            log.debug("Cache miss for chatbot: {}. Loading from database", chatbotId);
            return loadToolsForChatbot(chatbotId);
        });

        Map<String, ToolModel.Tool> result = new HashMap<>();
        if (chatbotTools != null) {
            chatbotTools.forEach((id, entry) -> result.put(id, entry.getTool()));
        }

        return result;
    }

    /**
     * Check if a tool exists
     *
     * @param chatbotId The chatbot ID
     * @param toolId The tool ID
     * @return true if tool exists
     */
    public boolean toolExists(Long chatbotId, String toolId) {
        try {
            getTool(chatbotId, toolId);
            return true;
        } catch (ToolNotFoundException e) {
            return false;
        }
    }

    /**
     * Invalidate cache for a specific chatbot
     *
     * @param chatbotId The chatbot ID
     */
    public void invalidateCache(Long chatbotId) {
        String cacheKey = getCacheKey(chatbotId);
        toolCache.invalidate(cacheKey);
        log.debug("Cache invalidated for chatbot: {}", chatbotId);
    }

    /**
     * Invalidate entire cache
     */
    public void invalidateAll() {
        toolCache.invalidateAll();
        log.info("Entire tool registry cache invalidated");
    }

    /**
     * Refresh cache for a specific chatbot
     *
     * @param chatbotId The chatbot ID
     */
    public void refreshCache(Long chatbotId) {
        String cacheKey = getCacheKey(chatbotId);
        Map<String, ToolRegistryCacheEntry> tools = loadToolsForChatbot(chatbotId);
        toolCache.put(cacheKey, tools);
        log.debug("Cache refreshed for chatbot: {}. Tools loaded: {}", chatbotId, tools.size());
    }

    /**
     * Refresh all caches
     * Scheduled to run periodically
     */
    @Scheduled(fixedDelayString = "${tool-execution.tool-registry.cache-ttl-seconds}000")
    public void refreshAllCaches() {
        if (!config.getToolRegistry().isCacheEnabled()) {
            return;
        }

        log.debug("Starting scheduled cache refresh");

        try {
            // Get all unique chatbot IDs from cache
            toolCache.asMap().keySet().forEach(cacheKey -> {
                try {
                    Long chatbotId = extractChatbotId(cacheKey);
                    refreshCache(chatbotId);
                } catch (Exception e) {
                    log.error("Error refreshing cache for key: {}", cacheKey, e);
                }
            });

            log.debug("Scheduled cache refresh completed");
        } catch (Exception e) {
            log.error("Error during scheduled cache refresh", e);
        }
    }

    /**
     * Load tools for a chatbot from database
     *
     * @param chatbotId The chatbot ID
     * @return Map of tool ID to ToolRegistryCacheEntry
     */
    private Map<String, ToolRegistryCacheEntry> loadToolsForChatbot(Long chatbotId) {
        List<ToolModel.Tool> tools = toolRepository.findByChatbotId(chatbotId);

        Map<String, ToolRegistryCacheEntry> toolMap = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (ToolModel.Tool tool : tools) {
            ToolRegistryCacheEntry entry = ToolRegistryCacheEntry.builder()
                    .tool(tool)
                    .cachedAt(now)
                    .accessCount(0L)
                    .lastAccessedAt(now)
                    .build();

            toolMap.put(tool.getFuncNameKey(), entry);
        }

        log.debug("Loaded {} tools for chatbot: {}", toolMap.size(), chatbotId);

        return toolMap;
    }

    /**
     * Fetch tool directly from database (bypassing cache)
     *
     * @param chatbotId The chatbot ID
     * @param toolId The tool ID
     * @return The tool
     * @throws ToolNotFoundException if tool not found
     */
    private ToolModel.Tool fetchToolFromDatabase(Long chatbotId, String toolId) {
        return toolRepository.findByChatbotIdAndFuncName(chatbotId, toolId)
                .orElseThrow(() -> new ToolNotFoundException(
                        String.format("Tool '%s' not found for chatbot %d", toolId, chatbotId),
                        toolId,
                        chatbotId
                ));
    }

    /**
     * Get cache key for a chatbot
     *
     * @param chatbotId The chatbot ID
     * @return Cache key
     */
    private String getCacheKey(Long chatbotId) {
        return "chatbot:" + chatbotId;
    }

    /**
     * Extract chatbot ID from cache key
     *
     * @param cacheKey The cache key
     * @return Chatbot ID
     */
    private Long extractChatbotId(String cacheKey) {
        return Long.parseLong(cacheKey.replace("chatbot:", ""));
    }

    /**
     * Get cache statistics
     *
     * @return Map of statistics
     */
    public Map<String, Object> getCacheStatistics() {
        Map<String, Object> stats = new HashMap<>();

        com.github.benmanes.caffeine.cache.stats.CacheStats cacheStats = toolCache.stats();

        stats.put("hitCount", cacheStats.hitCount());
        stats.put("missCount", cacheStats.missCount());
        stats.put("hitRate", cacheStats.hitRate());
        stats.put("evictionCount", cacheStats.evictionCount());
        stats.put("estimatedSize", toolCache.estimatedSize());
        stats.put("cacheEnabled", config.getToolRegistry().isCacheEnabled());
        stats.put("cacheTtlSeconds", config.getToolRegistry().getCacheTtlSeconds());

        return stats;
    }

    /**
     * Get detailed cache info for monitoring
     *
     * @return Map of cache info
     */
    public Map<String, Object> getCacheInfo() {
        Map<String, Object> info = new HashMap<>();

        info.put("statistics", getCacheStatistics());
        info.put("cachedChatbots", toolCache.asMap().size());

        // Per-chatbot tool counts
        Map<String, Integer> toolCounts = new HashMap<>();
        toolCache.asMap().forEach((key, tools) -> {
            toolCounts.put(key, tools.size());
        });
        info.put("toolCountsByChatbot", toolCounts);

        return info;
    }
}
