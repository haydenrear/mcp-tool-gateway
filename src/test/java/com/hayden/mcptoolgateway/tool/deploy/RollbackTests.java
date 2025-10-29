package com.hayden.mcptoolgateway.tool.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.*;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.McpSyncServerDelegate;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;


@SpringBootTest
@ActiveProfiles("rollback-tests")
public class RollbackTests {

    @Autowired
    private RedeployFunction redeployFunction;

    @Autowired
    private DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    @Autowired
    private McpSyncServerDelegate mcpSyncServerDelegate;

    @Autowired
    private McpSyncClient mockClient;

    @Autowired
    private ToolDecoratorService toolDecoratorService;

    @Autowired
    private McpServerToolStates setClients;

    @Autowired
    private Redeploy redeploy;

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    private ToolGatewayConfigProperties.DecoratedMcpServer testServer;
    @Autowired
    private McpServerToolStates mcpServerToolStates;

    @BeforeEach
    void setUp() throws IOException {
        // Use the test server from yml configuration
        testServer = toolGatewayConfigProperties.getDeployableMcpServers().get("test-rollback-server");
        testServer.setHasMany(false);
        
        // Ensure directories exist
        Files.createDirectories(testServer.directory());
        Files.createDirectories(toolGatewayConfigProperties.getArtifactCache());
        
        // Create test copyToArtifactPath if it doesn't exist
        if (!Files.exists(testServer.copyToArtifactPath())) {
            Files.createDirectories(testServer.copyToArtifactPath().getParent());
            Files.write(testServer.copyToArtifactPath(), "test copyToArtifactPath content".getBytes());
        }

        mcpServerToolStates.addUpdateToolState(
                "test-rollback-server",
                ToolDecoratorService.McpServerToolState.builder()
                        .deployableMcpServer(testServer)
                        .toolCallbackProviders(new ArrayList<>())
                        .build());
        // Reset mocks
        reset(redeployFunction, dynamicMcpToolCallbackProvider, mcpSyncServerDelegate, mockClient);
    }

    @Test
    void shouldInitializeToolDecoratorServiceSuccessfully() {
        // Given
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        McpSchema.Tool testTool = new McpSchema.Tool("init-tool", "Test tool for init", JsonSchemaGenerator.generateForType(String.class));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(testTool), null));

        // When
        toolDecoratorService.doPerformInit();

        // Then
        verify(mcpSyncServerDelegate, atLeastOnce()).addTool(any());
        verify(mcpSyncServerDelegate).notifyToolsListChanged();
    }

    @Test
    void shouldHandleRedeployWithRollbackScenario() throws IOException {
        // Given
        TestUtil.writeToCopyTo("original copyToArtifactPath content".getBytes(), testServer);

        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");
        
        // Mock failed deployment
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor failedDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
                .isSuccess(false)
                .err("Deployment failed")
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(testServer)).thenReturn(failedDescriptor);
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenReturn(true);

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(
                redeployRequest, 
                testServer,
                getToolState()
        );

        // Then
        verify(redeployFunction).performRedeploy(testServer);
        // Verify that rollback preparation would have occurred (copyToArtifactPath backup)
        assertThat(Files.exists(toolGatewayConfigProperties.getArtifactCache().resolve(testServer.getCopyFromArtifactPath().getFileName().toString())))
                .isTrue();
    }

    @Test
    void shouldHandleSetClientsWithConnectionFailure() {
        // Given
        String clientName = "test-rollback-server";
        DynamicMcpToolCallbackProvider.McpError connectionError = 
                new DynamicMcpToolCallbackProvider.McpError("Connection timeout");

        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.err(connectionError));

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.setParseMcpClient(
                clientName,
                getToolState()
        );

        // Then
        assertThat(result.wasSuccessful()).isFalse();
        assertThat(result.err()).isEqualTo("Connection timeout");
        assertThat(setClients.clientHasError(clientName)).isTrue();
    }

    @Test
    void shouldHandleSuccessfulClientConnection() {
        // Given
        String clientName = "test-rollback-server";
        McpSchema.Tool testTool = new McpSchema.Tool("test-tool", "A test tool", JsonSchemaGenerator.generateForType(String.class));

        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation(clientName, "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(testTool), null));
        when(mockClient.isInitialized()).thenReturn(true);

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.setParseMcpClient(
                clientName,
                getToolState()
        );

        // Then
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(result.tools()).contains("test-rollback-server-test-tool");
        assertThat(setClients.hasClient(clientName)).isTrue();
        assertThat(setClients.clientInitialized(clientName)).isTrue();
        verify(mcpSyncServerDelegate).addTool(any());
    }

    @Test
    void shouldHandleRedeployWithoutRollbackWhenBinaryNotExists() throws IOException {
        // Given
        Path nonExistentBinary = testServer.directory().resolve("non-existent.jar");
        ToolGatewayConfigProperties.DecoratedMcpServer serverWithoutBinary =
                new ToolGatewayConfigProperties.DecoratedMcpServer(
                        "test-rollback-server",
                        "echo 'deploy'",
                        testServer.directory(),
                        nonExistentBinary
                );

        ToolModels.Redeploy redeployRequest = new ToolModels.Redeploy("test-rollback-server");
        
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor failedDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
                .isSuccess(false)
                .err("Deploy failed")
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(serverWithoutBinary)).thenReturn(failedDescriptor);
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Deploy failed")));

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(
                redeployRequest, 
                serverWithoutBinary, 
                getToolState()
        );

        // Then
        verify(redeployFunction).performRedeploy(serverWithoutBinary);
        // Verify no rollback backup was created since copyToArtifactPath doesn't exist
        assertThat(Files.exists(toolGatewayConfigProperties.getArtifactCache().resolve(nonExistentBinary.getFileName().toString())))
                .isFalse();
        assertThat(result.redeployResult().deployState().didRollback()).isFalse();
    }

    @Test
    void shouldIntegrateAllComponentsInRedeployFlow() {
        // Given
        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        McpSchema.Tool redeployTool = new McpSchema.Tool("redeploy-tool", "Test redeploy tool", JsonSchemaGenerator.generateForType(String.class));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(redeployTool), null));

        // When
        toolDecoratorService.doPerformInit();

        // Then - Verify the full integration works
        assertThat(setClients.noClientKey("test-rollback-server")).isFalse();
        verify(mcpSyncServerDelegate, atLeastOnce()).addTool(any()); // Redeploy tool should be added
        verify(mcpSyncServerDelegate).notifyToolsListChanged();
    }

    @Test
    void shouldHandleToolCreationAndRemoval() {
        // Given
        String clientName = "test-rollback-server";
        McpSchema.Tool tool1 = new McpSchema.Tool("tool1", "First tool", JsonSchemaGenerator.generateForType(String.class));
        McpSchema.Tool tool2 = new McpSchema.Tool("tool2", "Second tool", JsonSchemaGenerator.generateForType(String.class));

        // First setup with one tool
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation(clientName, "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool1), null));
        when(mockClient.isInitialized()).thenReturn(true);

        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult firstResult = setClients.setParseMcpClient(
                clientName,
                getToolState()
        );

        // Then update with different tool
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool2), null));

        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult secondResult = setClients.setParseMcpClient(
                clientName,
                ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(firstResult.providers())
                        .build()
        );

        // Then
        assertThat(firstResult.wasSuccessful()).isTrue();
        assertThat(firstResult.toolsAdded()).contains("test-rollback-server-tool1");
        
        assertThat(secondResult.wasSuccessful()).isTrue();
        assertThat(secondResult.toolsAdded()).contains("test-rollback-server-tool2");
        assertThat(secondResult.toolsRemoved()).contains("test-rollback-server-tool1");
        
        verify(mcpSyncServerDelegate, times(2)).addTool(any());
        verify(mcpSyncServerDelegate).removeTool("test-rollback-server-tool1");
    }

    private ToolDecoratorService.McpServerToolState getToolState() {
        return ToolDecoratorService.McpServerToolState.builder().deployableMcpServer(testServer).toolCallbackProviders(new ArrayList<>()).build();
    }
}