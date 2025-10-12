package com.hayden.mcptoolgateway.tool;

import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public interface ToolDecorator {

    sealed interface McpServerToolStateChange {

        record RemoveTool(String toRemove)  implements McpServerToolStateChange {}

        record AddTool(McpServerFeatures.SyncToolSpecification toAddTools)  implements McpServerToolStateChange {}

    }

    record ToolDecoratorToolStateUpdate(String name, ToolDecoratorService.McpServerToolState toolStates,
                                        List<McpServerToolStateChange> toolStateChanges) {
        public ToolDecoratorToolStateUpdate(String name, ToolDecoratorService.McpServerToolState toolStates) {
            this(name, toolStates, List.of());
        }
    }

    ToolDecoratorToolStateUpdate decorate(Map<String, ToolDecoratorService.McpServerToolState> newMcpServerState);

    boolean isEnabled();
}
