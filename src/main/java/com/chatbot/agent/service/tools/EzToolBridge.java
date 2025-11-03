package com.chatbot.agent.service.tools;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

import java.util.HashMap;
import java.util.Map;

/**
 * EzToolBridge - Java object that bridges JavaScript to tool execution
 * 
 * This object is injected into GraalVM JavaScript context as "eztool"
 * JavaScript code can call: eztool('toolId', {params})
 * This invokes the execute() method which calls ToolExecutionService
 */
@Slf4j
public class EzToolBridge implements ProxyExecutable {
    
    private final ExecutionContext context;
    private final ToolExecutionService toolExecutionService;
    
    /**
     * Constructor
     * 
     * @param context The execution context
     * @param toolExecutionService Service to execute tools
     */
    public EzToolBridge(ExecutionContext context, ToolExecutionService toolExecutionService) {
        this.context = context;
        this.toolExecutionService = toolExecutionService;
    }
    
    /**
     * Execute method - called when JavaScript invokes eztool()
     * 
     * @param arguments Arguments from JavaScript: [toolId, params]
     * @return Result from tool execution
     */
    @Override
    public Object execute(Value... arguments) {
        try {
            // Validate arguments
            if (arguments.length == 0) {
                throw new IllegalArgumentException("eztool() requires at least 1 argument: toolId");
            }
            
            // Extract tool ID (required)
            if (!arguments[0].isString()) {
                throw new IllegalArgumentException("First argument to eztool() must be a string (toolId)");
            }
            String toolId = arguments[0].asString();
            
            // Extract params (optional)
            Map<String, Object> params = new HashMap<>();
            if (arguments.length > 1 && !arguments[1].isNull()) {
                params = convertValueToMap(arguments[1]);
            }
            
            log.debug("[executionId={}] JavaScript calling eztool('{}') with params: {}",
                    context.getExecutionId(), toolId, params.keySet());
            
            // Execute the tool through the service
            Object result = toolExecutionService.handleEzToolCall(context, toolId, params);
            
            log.debug("[executionId={}] eztool('{}') completed successfully",
                    context.getExecutionId(), toolId);
            
            return result;
            
        } catch (Exception e) {
            log.error("[executionId={}] Error in eztool()", context.getExecutionId(), e);
            
            // Return error object that JavaScript can handle
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", e.getMessage());
            errorResult.put("errorType", e.getClass().getSimpleName());
            
            return errorResult;
        }
    }
    
    /**
     * Convert GraalVM Value to Java Map
     * 
     * @param value The GraalVM value
     * @return Java Map
     */
    private Map<String, Object> convertValueToMap(Value value) {
        Map<String, Object> map = new HashMap<>();
        
        if (value.isNull()) {
            return map;
        }
        
        if (!value.hasMembers()) {
            // Not an object, wrap it
            map.put("value", convertValue(value));
            return map;
        }
        
        // Convert object members
        for (String key : value.getMemberKeys()) {
            Value memberValue = value.getMember(key);
            map.put(key, convertValue(memberValue));
        }
        
        return map;
    }
    
    /**
     * Convert GraalVM Value to Java Object (recursive)
     * 
     * @param value The GraalVM value
     * @return Java object
     */
    private Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        
        // Arrays
        if (value.hasArrayElements()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (long i = 0; i < value.getArraySize(); i++) {
                list.add(convertValue(value.getArrayElement(i)));
            }
            return list;
        }
        
        // Objects
        if (value.hasMembers()) {
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, convertValue(value.getMember(key)));
            }
            return map;
        }
        
        // Primitives
        if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            } else if (value.fitsInLong()) {
                return value.asLong();
            } else {
                return value.asDouble();
            }
        }
        
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        
        if (value.isString()) {
            return value.asString();
        }
        
        // Default: convert to string
        return value.toString();
    }
}
