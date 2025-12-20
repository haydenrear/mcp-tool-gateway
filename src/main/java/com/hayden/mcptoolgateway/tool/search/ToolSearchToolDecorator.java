package com.hayden.mcptoolgateway.tool.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import io.micrometer.common.util.StringUtils;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolSearchToolDecorator implements ToolDecorator {

    private static final String TOOL_ADD = "add-tool-server";

    private final ToolGatewayConfigProperties toolGatewayConfigProperties;

    private final McpServerToolStates ts;

    private final ObjectMapper objectMapper;

    private final ToolSearch search;

    @Override
    public ToolDecoratorToolStateUpdate decorate(ToolDecoratorState newMcpServerState) {
        return new ToolDecoratorToolStateUpdate.AddToolToolStateUpdate(TOOL_ADD, getToolAdd(newMcpServerState.newMcpServerState()));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    ToolDecoratorService.McpServerToolState getToolAdd(Map<String, ToolDecoratorService.McpServerToolState> newMcpServerState) {

        StaticToolCallbackProvider redeployToolCallbackProvider = new StaticToolCallbackProvider(
                FunctionToolCallback
                        .<ToolModels.Add, SearchModels.SearchResult>builder(TOOL_ADD, (i, o) -> ts.doOverWriteState(() -> {
                            if (!toolGatewayConfigProperties.getAddableMcpServers()
                                    .containsKey(i.tool())) {
                                log.error("MCP server name {} was not contained in options {}.",
                                        i.tool(), toolGatewayConfigProperties.getAddableMcpServers().keySet());
                                if (toolGatewayConfigProperties.getAddableMcpServers().size() == 1) {
                                    ToolGatewayConfigProperties.DecoratedMcpServer toRedeploy = toolGatewayConfigProperties.getAddableMcpServers()
                                            .entrySet().stream()
                                            .findFirst().orElseThrow()
                                            .getValue();
                                    log.error("Deploying only deployable MCP server with request - assuming mistake - redeploying existing {}.",
                                            toRedeploy.name());
                                    return doToolAdd(i, toRedeploy);
                                } else {
                                    return SearchModels.SearchResult.builder()
                                            .addErr("%s was not contained in set of deployable MCP servers %s - please update."
                                                    .formatted(i.tool(), toolGatewayConfigProperties.getAddableMcpServers().keySet()))
                                            .build();
                                }
                            } else {
                                return doToolAdd(i, toolGatewayConfigProperties.getAddableMcpServers().get(i.tool()));
                            }
                        }))
                        .description("""
                                # Tool Add Description
                                At runtime, you may discover skills that expose MCP tools. You then call this tool to add
                                those tools to your context, with their ID.
                                """)
                        .inputType(ToolModels.Add.class)
                        .toolCallResultConverter((result, returnType) -> {
                            try {
                                return objectMapper.writeValueAsString(result);
                            } catch (JsonProcessingException e) {
                                return "Failed to process result %s with error getMessage %s"
                                        .formatted(returnType, e.getMessage());
                            }
                        })
                        .build());

        if (ts.isInitialized()) {
            ts.removeTool(TOOL_ADD);
        }

        ts.addTool(McpToolUtils.toSyncToolSpecification(redeployToolCallbackProvider.getToolCallbacks()[0]));

        return ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(Lists.newArrayList(redeployToolCallbackProvider))
                .build();
    }

    SearchModels.SearchResult doToolAdd(ToolModels.Add i,
                                        ToolGatewayConfigProperties.DecoratedMcpServer toRedeploy) {
        if (!toolGatewayConfigProperties.getAddableMcpServers().containsKey(i.tool())) {
            return SearchModels.SearchResult.builder()
                    .addErr("%s was not contained in set of addable MCP servers %s - please update."
                            .formatted(i.tool(), toolGatewayConfigProperties.getAddableMcpServers().keySet()))
                    .build();
        }

        var r = search.doToolSearch(new ToolDecoratorInterpreter.ToolDecoratorEffect.DoToolSearch(i, toRedeploy, this.ts.removeToolState(i.tool())));

        this.ts.addUpdateToolState(i.tool(), r.newToolState());

        if (r.didToolListChange()) {
            var redeployed = this.decorate(new ToolDecoratorState(this.ts.copyOf(), new ArrayList<>()));
            this.ts.addUpdateToolState(redeployed);
            ts.notifyToolsListChanged();
            var addedSchema = tryParseSchema(r);
            return new SearchModels.SearchResult(
                    i.tool(),
                    addedSchema.schema,
                    String.join(", ", r.err(), addedSchema.err));
        }

        return new SearchModels.SearchResult(null, null, r.err());
    }

    @Builder
    public record ParseSchemaResult(String schema, String err) {}

    private @NotNull ParseSchemaResult tryParseSchema(ToolDecoratorInterpreter.ToolDecoratorResult.SearchResultWrapper r) {
        if (r.added().isEmpty()) {
            return new ParseSchemaResult(null, null);
        }

        var p = ParseSchemaResult.builder();
        if (r.added().size() > 1) {
            p = p.err("Returned multiple schemas - expected only one.");
        }
        var j = r.added().getFirst();
        try {
            return p.schema(objectMapper.writeValueAsString(j.tool().inputSchema())).build();
        } catch (
                JsonProcessingException e) {
            log.error("Could not parse schema - {}", j);
            return p.err("Could not parse schema - %s - %s".formatted(j.tool().inputSchema().toString(),  e.getMessage())).build();
        }
    }

}
