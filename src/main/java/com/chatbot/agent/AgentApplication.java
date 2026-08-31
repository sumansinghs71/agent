package com.chatbot.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NOTE: {@code @EnableScheduling} was missing until now. Spring Boot does NOT enable scheduling
 * automatically, so every {@code @Scheduled} method in this codebase was silently dead:
 * <ul>
 *   <li>{@code ExecutionContextFactory.detectLeaks} - leaked contexts were never reclaimed</li>
 *   <li>{@code ToolRegistryService.refreshAllCaches} - only the Caffeine TTL was doing anything</li>
 *   <li>{@code MetricsCollectionScheduler} - would not have run at all</li>
 * </ul>
 * Those first two now actually execute. Both are wanted, but it IS a behaviour change: leak
 * detection runs every 60s and the tool-registry cache refresh hits the database every 300s.
 */
@SpringBootApplication
@EnableScheduling
public class AgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentApplication.class, args);
	}

}
