package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;
import java.util.Map;

public interface ToolDecorator {

    sealed interface McpServerToolStateChange {

        record RemoveTool(
                McpServerToolStates.DeployedService server,
                String toRemove)  implements McpServerToolStateChange {}

        record AddTool(
                McpServerToolStates.DeployedService server,
                McpServerFeatures.SyncToolSpecification toAddTools,
                ToolCallbackProvider toolCallbackProvider) implements McpServerToolStateChange {
        }

        record AddCallbacks(
                McpServerToolStates.DeployedService name,
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
                return List.of(new McpServerToolStateChange.AddCallbacks(new McpServerToolStates.DeployedService(name, ToolDecoratorService.SYSTEM_ID), before, after));
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
