package com.hayden.mcptoolgateway.tool.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.tool_state.McpSyncServerDelegate;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("rollback-tests")
class ToolDecoratorRedeployTests {

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
    private RedeployToolDecorator redeployToolDecorator;

    @Autowired
    private McpServerToolStates setClients;


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

    @Test
    void shouldInitializeSuccessfullyWithValidServers() {
        // Given
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        // When
        toolDecoratorService.doPerformInit();

        // Then
        verify(mcpSyncServer, atLeastOnce()).addTool(any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    @Test
    void shouldHandleInitializationFailureGracefully() {
        // Given - failOnMcpClientInit is already set to false in yml
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenThrow(new RuntimeException("Connection failed"));

        // When & Then
        assertThatCode(() -> toolDecoratorService.doPerformInit())
                .doesNotThrowAnyException();
    }

    @Test
    void shouldThrowExceptionWhenFailOnInitIsTrue() {
        // Given
        toolGatewayConfigProperties.setFailOnMcpClientInit(true);
        
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenThrow(new RuntimeException("Connection failed"));

        // When & Then
        assertThatThrownBy(() -> toolDecoratorService.doPerformInit())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Connection failed");
    }

    @Test
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
        ToolDecoratorService.McpServerToolState redeployState = redeployToolDecorator.decorate(mockStates).toolStates();

        // Then
        assertThat(redeployState.toolCallbackProviders()).hasSize(1);
        verify(mcpSyncServer).addTool(any());
    }

    @Test
    void shouldHandleRedeployOfValidServer() throws Exception {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");
        
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper mockResult = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper.builder()
                .redeployResult(DeployModels.RedeployResult.builder()
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
                    return doCall(invocation);
                });

        when(redeployFunction.performRedeploy(testServer))
                .thenReturn(ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder().isSuccess(true).build());

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        // Setup doPerformInitial state
        toolDecoratorService.doPerformInit();

        // When
        String jsonResult = objectMapper.writeValueAsString(
                redeployToolDecorator.doRedeploy(redeployRequest, testServer));

        // Then
        assertThat(jsonResult).isNotNull();
        verify(redeployFunction).performRedeploy(testServer);
    }

    @Test
    void shouldHandleRedeployOfInvalidServer() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("non-existent-server");

        // Setup initial state
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.doPerformInit();

        // When
        DeployModels.RedeployResult result = redeployToolDecorator.doRedeploy(redeployRequest, testServer);

        // Then
        assertThat(result.deployErr()).contains("non-existent-server was not contained in set of deployable MCP servers");
    }

    @Test
    void shouldHandleSingleServerFallbackScenario() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("wrong-server-name");
        
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper mockResult = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper.builder()
                .redeployResult(DeployModels.RedeployResult.builder()
                        .tools(new HashSet<>())
                        .build())
                .newToolState(ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(new ArrayList<>())
                        .build())
                .redeploy(redeployRequest)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(any(), any()))
                .thenAnswer(invocation -> {
                    return doCall(invocation);
                });

        when(redeployFunction.performRedeploy(testServer))
                .thenReturn(ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder().isSuccess(true).build());

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.doPerformInit();

        // When (single server should trigger fallback)
        DeployModels.RedeployResult result = redeployToolDecorator.doRedeploy(redeployRequest, testServer);

        assertThat(result.deployErr()).isNotBlank();

    }

    @Test
    void shouldGenerateCorrectErrorInfoForFailedServers() {
        // Given
        ToolDecoratorService.McpServerToolState failedState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(null) // Empty providers
                .lastDeploy(ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
                        .isSuccess(false)
                        .err("Deploy failed")
                        .build())
                .build();

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Connection error")));

        Map<String, ToolDecoratorService.McpServerToolState> mockStates = new HashMap<>();
        mockStates.put("test-rollback-server", failedState);

        // When
        ToolDecoratorService.McpServerToolState redeployState = redeployToolDecorator.decorate(mockStates).toolStates();

        // Then
        assertThat(redeployState.toolCallbackProviders()).hasSize(1);
        // The description should contain error information
        verify(mcpSyncServer).addTool(any());
    }

    @Test
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
        ToolDecoratorService.McpServerToolState redeployState = redeployToolDecorator.decorate(mockStates).toolStates();

        // Then
        assertThat(redeployState.toolCallbackProviders()).hasSize(1);
        verify(mcpSyncServer).addTool(any());
    }

    @Test
    void shouldParseErrorCorrectlyForVariousFailureScenarios() {
        // Given
        ToolDecoratorService.McpServerToolState stateWithDeployError = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(new ArrayList<>())
                .lastDeploy(ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
        StringBuilder error = redeployToolDecorator.parseErr(stateWithDeployError, "test-rollback-server");

        // Then
        assertThat(error.toString()).contains("MCP server connection error");
        assertThat(error.toString()).contains("Sync error");
        assertThat(error.toString()).contains("MCP server deployment error");
        assertThat(error.toString()).contains("Deployment error");
    }

    @Test
    void shouldNotifyToolsListChangedAfterSuccessfulRedeploy() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");
        
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper mockResult = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper.builder()
                .redeployResult(DeployModels.RedeployResult.builder()
                        .tools(Set.of("new-tool"))
                        .deployState(DeployModels.DeployState.DEPLOY_FAIL)
                        .rollbackState(DeployModels.DeployState.ROLLBACK_FAIL)
                        .build())
                .newToolState(ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(new ArrayList<>())
                        .build())
                .redeploy(redeployRequest)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    return doCall(invocation);
                });

        when(redeployFunction.performRedeploy(testServer))
                .thenReturn(ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder().isSuccess(true).build());

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.doPerformInit();

        // When
        redeployToolDecorator.doRedeploy(redeployRequest, testServer);

        // Then
        verify(mcpSyncServer, atLeast(2)).notifyToolsListChanged(); // Once in init, once after redeploy
    }

    @Test
    void shouldNotNotifyToolsListChangedAfterRollback() {
        // Given
        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(ToolDecoratorRedeployTests::doCall);

        AtomicInteger  counter = new AtomicInteger(0);
        when(redeployFunction.performRedeploy(testServer))
                .thenAnswer(i -> {
                    if (counter.getAndIncrement() == 0)
                        return ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder().isSuccess(false).err("Deploy failed").build();
                    return ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder().isSuccess(true)
                            .build();
                });

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.isInitialized()).thenReturn(true);
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        toolDecoratorService.doPerformInit();

        // When
        redeployToolDecorator.doRedeploy(redeployRequest, testServer);

        // Then
        verify(mcpSyncServer, times(1)).notifyToolsListChanged(); // Only once in init, not after rollback
    }

    private static Object doCall(InvocationOnMock invocation) {
        Supplier callback = invocation.getArgument(1);
        return callback.get();
    }

    @Test
    void shouldHandleToolsWithValidConfiguration() {
        // Given
        McpSchema.Tool testTool = new McpSchema.Tool("test-tool", "A test tool", JsonSchemaGenerator.generateForType(String.class));
        
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(testTool), null));

        // When
        toolDecoratorService.doPerformInit();

        // Then
        verify(mcpSyncServer, atLeastOnce()).addTool(any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }
}