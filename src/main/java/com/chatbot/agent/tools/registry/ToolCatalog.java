package com.chatbot.agent.tools.registry;

import com.chatbot.agent.tools.contract.ToolDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of tools the runtime will consider, keyed by tenant and tool id.
 *
 * <p>Every protocol registers here - SQL, REST, sandbox and MCP alike - so that the authority gate,
 * the planner and the audit trail all see one kind of tool. A protocol that bypassed this would also
 * bypass every policy expressed in terms of {@link ToolDefinition}, which is how "we added an
 * integration and it skipped authorisation" happens.
 *
 * <p>Lookup is tenant-scoped, not global. A global registry with a tenant field checked by callers
 * relies on every caller remembering; scoping the lookup itself does not.
 */
@Slf4j
public class ToolCatalog {

    /** tenantId -> (toolId -> definition) */
    private final Map<Long, Map<String, ToolDefinition>> byTenant = new ConcurrentHashMap<>();

    public void register(ToolDefinition definition) {
        Long tenant = definition.tenantId();
        byTenant.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                .put(definition.toolId(), definition);
        log.debug("Registered tool {} v{} ({} / {}) for tenant {}",
                definition.toolId(), definition.version(),
                definition.protocol(), definition.sideEffectClass(), tenant);
    }

    public void registerAll(Collection<ToolDefinition> definitions) {
        definitions.forEach(this::register);
    }

    /**
     * @return the definition, or empty if this tenant has no such tool. A tool belonging to another
     * tenant is indistinguishable from one that does not exist, which is the intended behaviour.
     */
    public Optional<ToolDefinition> find(Long tenantId, String toolId) {
        return Optional.ofNullable(byTenant.getOrDefault(tenantId, Map.of()).get(toolId));
    }

    /** Enabled tools for a tenant, in stable id order so planning prompts are reproducible. */
    public List<ToolDefinition> enabledFor(Long tenantId) {
        return byTenant.getOrDefault(tenantId, Map.of()).values().stream()
                .filter(ToolDefinition::enabled)
                .sorted(java.util.Comparator.comparing(ToolDefinition::toolId))
                .toList();
    }

    /** Remove every tool served by a protocol - used when an MCP server disconnects. */
    public int removeByProtocol(Long tenantId, com.chatbot.agent.tools.contract.ToolProtocol protocol) {
        Map<String, ToolDefinition> tools = byTenant.get(tenantId);
        if (tools == null) {
            return 0;
        }
        List<String> removed = tools.values().stream()
                .filter(t -> t.protocol() == protocol)
                .map(ToolDefinition::toolId)
                .toList();
        removed.forEach(tools::remove);
        return removed.size();
    }

    public int size() {
        return byTenant.values().stream().mapToInt(Map::size).sum();
    }

    public void clear() {
        byTenant.clear();
    }
}
