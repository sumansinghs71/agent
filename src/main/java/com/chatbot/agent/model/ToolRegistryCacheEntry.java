package com.chatbot.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ToolRegistryCacheEntry - Cached tool information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRegistryCacheEntry {
    private ToolModel.Tool tool;
    private LocalDateTime cachedAt;
    private long accessCount;
    private LocalDateTime lastAccessedAt;

    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public boolean isExpired(long ttlSeconds) {
        return LocalDateTime.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
    }
}