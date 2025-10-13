package com.hayden.mcptoolgateway.tool;

import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.List;
import java.util.Map;

public interface ToolDecorator {

    sealed interface McpServerToolStateChange {

        record RemoveTool(String toRemove)  implements McpServerToolStateChange {}

        record AddTool(McpServerFeatures.SyncToolSpecification toAddTools)  implements McpServerToolStateChange {}

    }

    sealed interface  ToolDecoratorToolStateUpdate {

        ToolDecoratorService.McpServerToolState toolStates();

        List<McpServerToolStateChange> toolStateChanges();



        record AddToolStateUpdate(String name, ToolDecoratorService.McpServerToolState toolStates,
                                  List<McpServerToolStateChange> toolStateChanges) implements ToolDecoratorToolStateUpdate {
            public AddToolStateUpdate(String name, ToolDecoratorService.McpServerToolState toolStates) {
                this(name, toolStates, List.of());
            }
        }


    }

    record ToolDecoratorState(Map<String, ToolDecoratorService.McpServerToolState> newMcpServerState,
                              List<McpServerToolStateChange> stateChanges) {
        public ToolDecoratorState update(ToolDecoratorToolStateUpdate update) {
            switch (update) {
                case ToolDecoratorToolStateUpdate.AddToolStateUpdate(
                        String name,
                        ToolDecoratorService.McpServerToolState toolStates,
                        List<McpServerToolStateChange> toolStateChanges
                ) -> {
                    this.newMcpServerState.put(name, toolStates);
                    stateChanges.addAll(toolStateChanges);
                }
            }

            return this;
        }
    }

    ToolDecoratorToolStateUpdate decorate(ToolDecoratorState newMcpServerState);

    boolean isEnabled();
}
