package com.hayden.mcptoolgateway.tool;

import java.util.Map;

public interface ToolDecorator {

    record ToolDecoratorToolStateUpdate(String name, ToolDecoratorService.McpServerToolState toolStates) {}

    ToolDecoratorToolStateUpdate decorate(Map<String, ToolDecoratorService.McpServerToolState> newMcpServerState);

    boolean isEnabled();
}
