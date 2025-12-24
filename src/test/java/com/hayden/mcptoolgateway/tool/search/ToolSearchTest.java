package com.hayden.mcptoolgateway.tool.search;

import io.modelcontextprotocol.json.McpJsonMapper;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("rollback-tests")
class ToolSearchTest {

    @Autowired
    private ToolSearch toolSearch;

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    @Autowired
    private McpServerToolStates toolStates;

    @Autowired
    private DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    @Autowired
    private McpSyncClient mockClient;

    private ToolGatewayConfigProperties.DecoratedMcpServer server;

    @BeforeEach
    void setUp() {
        reset(dynamicMcpToolCallbackProvider, mockClient);
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
    void shouldMapAddServerResultIntoSearchWrapper() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("search-tool").title("search-tool").description("search tool")
                .inputSchema(McpJsonMapper.getDefault(), JsonSchemaGenerator.generateForType(String.class)).build();
        when(dynamicMcpToolCallbackProvider.buildClient(server.name()))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo())
                .thenReturn(new McpSchema.Implementation(server.name(), "1.0.0"));
        when(mockClient.listTools())
                .thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));

        ToolDecoratorInterpreter.ToolDecoratorEffect.DoToolSearch effect =
                new ToolDecoratorInterpreter.ToolDecoratorEffect.DoToolSearch(
                        new ToolModels.Add(server.name()),
                        server,
                        toolStates.copyOf().get(server.name()));

        ToolDecoratorInterpreter.ToolDecoratorResult.SearchResultWrapper wrapper = toolSearch.doToolSearch(effect);

        assertThat(wrapper.added()).hasSize(1);
        assertThat(wrapper.err()).isNullOrEmpty();
        assertThat(wrapper.toolStateChanges()).hasSize(1);
    }
}
