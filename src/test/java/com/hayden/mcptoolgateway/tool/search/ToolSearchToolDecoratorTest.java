package com.hayden.mcptoolgateway.tool.search;

import io.modelcontextprotocol.json.McpJsonMapper;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.McpSyncServerDelegate;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.acp_cdc_ai.mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.free.Free;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("rollback-tests")
class ToolSearchToolDecoratorTest {

    @Autowired
    private ToolSearchToolDecorator toolSearchToolDecorator;

    @Autowired
    private ToolDecoratorService toolDecoratorService;

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    @Autowired
    private McpServerToolStates toolStates;

    @Autowired
    private McpSyncServerDelegate mcpSyncServerDelegate;

    @Autowired
    private DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    @Autowired
    private McpSyncClient mockClient;

    @Autowired
    private ToolDecoratorInterpreter toolDecoratorInterpreter;

    private ToolGatewayConfigProperties.DecoratedMcpServer server;

    @BeforeEach
    void setUp() {
        reset(dynamicMcpToolCallbackProvider, mockClient, mcpSyncServerDelegate);
        toolGatewayConfigProperties.getAddableMcpServers().clear();
        toolStates.copyOf().keySet().forEach(toolStates::removeToolState);
        server = toolGatewayConfigProperties.getDeployableMcpServers().get("test-rollback-server");
        toolGatewayConfigProperties.getAddableMcpServers().put(server.name(), server);
        toolStates.addUpdateToolState(server.name(), ToolDecoratorService.McpServerToolState.builder()
                .added(new ArrayList<>())
                .toolCallbackProviders(new ArrayList<>())
                .deployableMcpServer(server)
                .build()
                .initialize());
    }

    @Test
    void shouldReturnErrorForUnknownToolWhenMultipleAddableServersExist() {
        toolGatewayConfigProperties.getAddableMcpServers()
                .put("addable-a", ToolGatewayConfigProperties.DecoratedMcpServer.builder().name("addable-a").build());
        toolGatewayConfigProperties.getAddableMcpServers()
                .put("addable-b", ToolGatewayConfigProperties.DecoratedMcpServer.builder().name("addable-b").build());

        ToolGatewayConfigProperties.DecoratedMcpServer server =
                toolGatewayConfigProperties.getAddableMcpServers().get("addable-a");

        SearchModels.SearchResult result = toolSearchToolDecorator.doToolAdd(new ToolModels.Add("missing-server"), server);

        assertThat(result.addErr()).contains("missing-server was not contained in set of addable MCP servers");
    }

    @Test
    void shouldReturnSchemaAndNotifyWhenToolListChanges() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("tool-one").title("tool-one").description("test tool").inputSchema(McpJsonMapper.getDefault(), JsonSchemaGenerator.generateForType(String.class)).build();
        when(dynamicMcpToolCallbackProvider.buildClient(server.name()))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo())
                .thenReturn(new McpSchema.Implementation(server.name(), "1.0.0"));
        when(mockClient.listTools())
                .thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));

        SearchModels.SearchResult result = toolSearchToolDecorator.doToolAdd(new ToolModels.Add(server.name()), server);

        assertThat(result.added()).isEqualTo(server.name());
        assertThat(result.addedSchema()).isNotBlank();
        assertThat(result.addErr()).isBlank();
        assertThat(toolStates.contains(server.name())).isTrue();
    }

    @Test
    void shouldCallAddedToolCallback() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("tool-one").title("tool-one").description("test tool").inputSchema(McpJsonMapper.getDefault(), JsonSchemaGenerator.generateForType(String.class)).build();
        when(dynamicMcpToolCallbackProvider.buildClient(server.name()))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo())
                .thenReturn(new McpSchema.Implementation(server.name(), "1.0.0"));
        when(mockClient.listTools())
                .thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));

        McpSchema.CallToolResult callToolResult = org.mockito.Mockito.mock(McpSchema.CallToolResult.class);
        when(callToolResult.isError()).thenReturn(true);
        when(callToolResult.content()).thenReturn(List.of());
        when(mockClient.callTool(any())).thenReturn(callToolResult);

        ToolDecoratorService.AddSyncClientResult addResult = toolDecoratorService.createAddServer(
                new ToolDecoratorService.AddToolSearch(server.name(), ToolDecoratorService.SYSTEM_ID));

        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult underlying = addResult.underlying();

        assertThat(underlying.providers().size()).isOne();

        verify(mockClient, times(0)).callTool(any());
        var callback = underlying.providers().getFirst().getToolCallbacks()[0];
        String response = callback.call("{\"input\":\"value\"}");

        assertThat(response).contains("\"error\"");
        verify(mockClient).callTool(any());
    }

    @Test
    void shouldNotInterfereWithExistingDecorators() {
        ToolDecoratorService.McpServerToolState baseState = ToolDecoratorService.McpServerToolState.builder()
                .added(new ArrayList<>())
                .toolCallbackProviders(new ArrayList<>())
                .deployableMcpServer(server)
                .build()
                .initialize();

        Map<String, ToolDecoratorInterpreter.ToolDecoratorEffect.AddMcpServerToolState> decoratedTools = Map.of(
                server.name(),
                new ToolDecoratorInterpreter.ToolDecoratorEffect.AddMcpServerToolState(
                        new ToolDecorator.ToolDecoratorToolStateUpdate.AddToolToolStateUpdate(server.name(), baseState)));

        Free.parse(Free.liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.AddManyMcpServerToolState(decoratedTools)),
                toolDecoratorInterpreter);

        assertThat(toolStates.contains(server.name())).isTrue();
        assertThat(toolStates.contains(RedeployFunction.REDEPLOY_MCP_SERVER)).isTrue();
        assertThat(toolStates.contains("add-tool-server")).isTrue();
        verify(mcpSyncServerDelegate, atLeast(2)).addTool(any(McpServerFeatures.SyncToolSpecification.class));
    }
}
