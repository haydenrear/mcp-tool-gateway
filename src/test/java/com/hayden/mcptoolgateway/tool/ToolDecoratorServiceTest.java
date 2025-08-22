package com.hayden.mcptoolgateway.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;


import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@SpringBootTest
@ActiveProfiles("rollback-tests")
class ToolDecoratorServiceTest {

    @Autowired
    private McpSyncServerDelegate mcpSyncServer;

    @Autowired
    private RedeployFunction redeployFunction;

    @Autowired
    private DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    @Autowired
    private McpSyncClient mockClient;

    @Autowired
    private ToolDecoratorService toolDecoratorService;

    @Autowired
    private SetClients setClients;




    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private ToolGatewayConfigProperties.DeployableMcpServer testServer;

    @BeforeEach
    void setUp() throws IOException {
        // Use the test server from yml configuration
        testServer = toolGatewayConfigProperties.getDeployableMcpServers().get("test-rollback-server");
        
        // Ensure directories exist
        Files.createDirectories(testServer.directory());
        Files.createDirectories(toolGatewayConfigProperties.getArtifactCache());
        
        // Create test copyToArtifactPath if it doesn't exist
        if (!Files.exists(testServer.copyToArtifactPath())) {
            Files.createDirectories(testServer.copyToArtifactPath().getParent());
            Files.write(testServer.copyToArtifactPath(), "test copyToArtifactPath content".getBytes());
        }

        // Reset mocks before each test
        reset(mcpSyncServer, redeployFunction, dynamicMcpToolCallbackProvider, mockClient);
    }

    //@Test
    void shouldInitializeSuccessfullyWithValidServers() {
        // Given
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        // When
        toolDecoratorService.init();

        // Then
        verify(mcpSyncServer, atLeastOnce()).addTool(any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    //@Test
    void shouldHandleInitializationFailureGracefully() {
        // Given - failOnMcpClientInit is already set to false in yml
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenThrow(new RuntimeException("Connection failed"));

        // When & Then
        assertThatCode(() -> toolDecoratorService.init())
                .doesNotThrowAnyException();
    }

    //@Test
    void shouldThrowExceptionWhenFailOnInitIsTrue() {
        // Given
        toolGatewayConfigProperties.setFailOnMcpClientInit(true);
        
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenThrow(new RuntimeException("Connection failed"));

        // When & Then
        assertThatThrownBy(() -> toolDecoratorService.init())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Connection failed");
    }

    //@Test
    void shouldCreateRedeployToolWithCorrectDescription() {
        // Given
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        Map<String, ToolDecoratorService.McpServerToolState> mockStates = new HashMap<>();
        ToolDecoratorService.McpServerToolState testState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(new ArrayList<>())
                .build();
        mockStates.put("test-rollback-server", testState);

        // When
        ToolDecoratorService.McpServerToolState redeployState = toolDecoratorService.getRedeploy(mockStates);

        // Then
        assertThat(redeployState.toolCallbackProviders()).hasSize(1);
        verify(mcpSyncServer).addTool(any());
    }

    //@Test
    void shouldHandleRedeployOfValidServer() throws Exception {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");
        
        Redeploy.RedeployResultWrapper mockResult = Redeploy.RedeployResultWrapper.builder()
                .redeployResult(ToolDecoratorService.RedeployResult.builder()
                        .tools(Set.of("new-tool"))
                        .toolsAdded(Set.of("new-tool"))
                        .toolsRemoved(new HashSet<>())
                        .build())
                .newToolState(ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(new ArrayList<>())
                        .build())
                .redeploy(redeployRequest)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    Runnable callback = invocation.getArgument(1);
                    callback.run();
                    return mockResult;
                });

        when(redeployFunction.performRedeploy(testServer))
                .thenReturn(RedeployFunction.RedeployDescriptor.builder().isSuccess(true).build());

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        // Setup initial state
        toolDecoratorService.init();

        // When
        String jsonResult = objectMapper.writeValueAsString(
                toolDecoratorService.parseRedeployResult(redeployRequest, testServer));

        // Then
        assertThat(jsonResult).isNotNull();
        verify(redeployFunction).performRedeploy(testServer);
    }

    //@Test
    void shouldHandleRedeployOfInvalidServer() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("non-existent-server");

        // Setup initial state
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.init();

        // When
        ToolDecoratorService.RedeployResult result = toolDecoratorService.parseRedeployResult(redeployRequest, testServer);

        // Then
        assertThat(result.deployErr()).contains("non-existent-server was not contained in set of deployable MCP servers");
    }

    //@Test
    void shouldHandleSingleServerFallbackScenario() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("wrong-server-name");
        
        Redeploy.RedeployResultWrapper mockResult = Redeploy.RedeployResultWrapper.builder()
                .redeployResult(ToolDecoratorService.RedeployResult.builder()
                        .tools(new HashSet<>())
                        .build())
                .newToolState(ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(new ArrayList<>())
                        .build())
                .redeploy(redeployRequest)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(any(), any()))
                .thenAnswer(invocation -> {
                    Runnable callback = invocation.getArgument(1);
                    callback.run();
                    return mockResult;
                });

        when(redeployFunction.performRedeploy(testServer))
                .thenReturn(RedeployFunction.RedeployDescriptor.builder().isSuccess(true).build());

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.init();

        // When (single server should trigger fallback)
        ToolDecoratorService.RedeployResult result = toolDecoratorService.parseRedeployResult(redeployRequest, testServer);

        // Then
        verify(redeployFunction).performRedeploy(testServer);
    }

    //@Test
    void shouldGenerateCorrectErrorInfoForFailedServers() {
        // Given
        ToolDecoratorService.McpServerToolState failedState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(null) // Empty providers
                .lastDeploy(RedeployFunction.RedeployDescriptor.builder()
                        .isSuccess(false)
                        .err("Deploy failed")
                        .build())
                .build();

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Connection error")));

        Map<String, ToolDecoratorService.McpServerToolState> mockStates = new HashMap<>();
        mockStates.put("test-rollback-server", failedState);

        // When
        ToolDecoratorService.McpServerToolState redeployState = toolDecoratorService.getRedeploy(mockStates);

        // Then
        assertThat(redeployState.toolCallbackProviders()).hasSize(1);
        // The description should contain error information
        verify(mcpSyncServer).addTool(any());
    }

    //@Test
    void shouldHandleToolStateWithNoProviders() {
        // Given
        ToolDecoratorService.McpServerToolState emptyState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(new ArrayList<>())
                .build();

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenReturn(true);

        Map<String, ToolDecoratorService.McpServerToolState> mockStates = new HashMap<>();
        mockStates.put("test-rollback-server", emptyState);

        // When
        ToolDecoratorService.McpServerToolState redeployState = toolDecoratorService.getRedeploy(mockStates);

        // Then
        assertThat(redeployState.toolCallbackProviders()).hasSize(1);
        verify(mcpSyncServer).addTool(any());
    }

    //@Test
    void shouldParseErrorCorrectlyForVariousFailureScenarios() {
        // Given
        ToolDecoratorService.McpServerToolState stateWithDeployError = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(new ArrayList<>())
                .lastDeploy(RedeployFunction.RedeployDescriptor.builder()
                        .isSuccess(false)
                        .err("Deployment error")
                        .build())
                .build();

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Sync error")));

        // Simulate client has error state
        setClients.createSetClientErr("test-rollback-server", 
                new DynamicMcpToolCallbackProvider.McpError("Sync error"), 
                ToolDecoratorService.McpServerToolState.builder().toolCallbackProviders(new ArrayList<>()).build());

        // When
        StringBuilder error = toolDecoratorService.parseErr(stateWithDeployError, "test-rollback-server");

        // Then
        assertThat(error.toString()).contains("MCP server connection error");
        assertThat(error.toString()).contains("Sync error");
        assertThat(error.toString()).contains("MCP server deployment error");
        assertThat(error.toString()).contains("Deployment error");
    }

    //@Test
    void shouldNotifyToolsListChangedAfterSuccessfulRedeploy() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");
        
        Redeploy.RedeployResultWrapper mockResult = Redeploy.RedeployResultWrapper.builder()
                .redeployResult(ToolDecoratorService.RedeployResult.builder()
                        .tools(Set.of("new-tool"))
                        .didRollback(false)
                        .build())
                .newToolState(ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(new ArrayList<>())
                        .build())
                .redeploy(redeployRequest)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    Runnable callback = invocation.getArgument(1);
                    callback.run();
                    return mockResult;
                });

        when(redeployFunction.performRedeploy(testServer))
                .thenReturn(RedeployFunction.RedeployDescriptor.builder().isSuccess(true).build());

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.init();

        // When
        toolDecoratorService.parseRedeployResult(redeployRequest, testServer);

        // Then
        verify(mcpSyncServer, atLeast(2)).notifyToolsListChanged(); // Once in init, once after redeploy
    }

    //@Test
    void shouldNotNotifyToolsListChangedAfterRollback() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");
        
        Redeploy.RedeployResultWrapper mockResult = Redeploy.RedeployResultWrapper.builder()
                .redeployResult(ToolDecoratorService.RedeployResult.builder()
                        .tools(Set.of("rolled-back-tool"))
                        .didRollback(true)
                        .build())
                .newToolState(ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(new ArrayList<>())
                        .build())
                .redeploy(redeployRequest)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    Runnable callback = invocation.getArgument(1);
                    callback.run();
                    return mockResult;
                });

        when(redeployFunction.performRedeploy(testServer))
                .thenReturn(RedeployFunction.RedeployDescriptor.builder().isSuccess(false).err("Deploy failed").build());

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.init();

        // When
        toolDecoratorService.parseRedeployResult(redeployRequest, testServer);

        // Then
        verify(mcpSyncServer, times(1)).notifyToolsListChanged(); // Only once in init, not after rollback
    }

    //@Test
    void shouldHandleToolsWithValidConfiguration() {
        // Given
        McpSchema.Tool testTool = new McpSchema.Tool("test-tool", "A test tool", JsonSchemaGenerator.generateForType(String.class));
        
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(testTool), null));

        // When
        toolDecoratorService.init();

        // Then
        verify(mcpSyncServer, atLeastOnce()).addTool(any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }
}