package com.hayden.mcptoolgateway.tool;

import io.modelcontextprotocol.server.McpServerFeatures;

import java.util.List;
import java.util.Map;

public interface ToolDecorator {

    sealed interface McpServerToolStateChange {

        record RemoveTool(String toRemove)  implements McpServerToolStateChange {}

        record AddTool(McpServerFeatures.SyncToolSpecification toAddTools)  implements McpServerToolStateChange {}

        record AddCallbacks(String name,
                            List<ToolDecoratorService.BeforeToolCallback> beforeToolCallback,
                            List<ToolDecoratorService.AfterToolCallback> afterToolCallback)
                implements McpServerToolStateChange {}

    }

    sealed interface ToolDecoratorToolStateUpdate {

        ToolDecoratorService.McpServerToolState toolStates();

        String name();

        List<McpServerToolStateChange> toolStateChanges();

        record AddToolToolStateUpdate(String name,
                                      ToolDecoratorService.McpServerToolState toolStates,
                                      List<McpServerToolStateChange> toolStateChanges) implements ToolDecoratorToolStateUpdate {
            public AddToolToolStateUpdate(String name, ToolDecoratorService.McpServerToolState toolStates) {
                this(name, toolStates, List.of());
            }
        }

        record AddCallbacksToToolState(
                String name,
                ToolDecoratorService.McpServerToolState toolStates,
                List<ToolDecoratorService.BeforeToolCallback> before,
                List<ToolDecoratorService.AfterToolCallback> after) implements ToolDecoratorToolStateUpdate {
            @Override
            public List<McpServerToolStateChange> toolStateChanges() {
                return List.of(new McpServerToolStateChange.AddCallbacks(name, before, after));
            }
        }

    }

    record ToolDecoratorState(Map<String, ToolDecoratorService.McpServerToolState> newMcpServerState,
                              List<McpServerToolStateChange> stateChanges) {
        public ToolDecoratorState update(ToolDecoratorToolStateUpdate update) {
            switch (update) {
                case ToolDecoratorToolStateUpdate.AddToolToolStateUpdate(
                        String name,
                        ToolDecoratorService.McpServerToolState toolStates,
                        List<McpServerToolStateChange> toolStateChanges
                ) -> {
                    if (this.newMcpServerState.containsKey(name)) {
                        throw new IllegalArgumentException("Already contained.");
                    }
                    this.newMcpServerState.put(name, toolStates);
                    stateChanges.addAll(toolStateChanges);
                }
                case ToolDecoratorToolStateUpdate.AddCallbacksToToolState addBeforeAfterToolCallbacks -> {
                    if (!this.newMcpServerState.containsKey(addBeforeAfterToolCallbacks.name)) {
                        throw new IllegalArgumentException("Already contained.");
                    }
                    stateChanges.addAll(addBeforeAfterToolCallbacks.toolStateChanges());
                }
            }

            return this;
        }
    }

    ToolDecoratorToolStateUpdate decorate(ToolDecoratorState newMcpServerState);

    boolean isEnabled();
}
