package com.hayden.mcptoolgateway.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@SpringBootTest
@ActiveProfiles("rollback-tests")
class RedeployTest {

    @Autowired
    private RedeployFunction redeployFunction;

    @Autowired
    private DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    @Autowired
    private McpSyncServerDelegate mcpSyncServerDelegate;

    @Autowired
    private McpSyncClient mockClient;

    @Autowired
    private Redeploy redeploy;

    @Autowired
    private ToolDecoratorService toolDecoratorService;

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    private ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer;
    private ToolDecoratorService.McpServerToolState toolState;
    private ToolModels.Redeploy redeployRequest;

    @BeforeEach
    void setUp() throws IOException {
        // Use the deployable server from yml configuration
        deployableMcpServer = toolGatewayConfigProperties.getDeployableMcpServers().get("test-rollback-server");

        // Ensure the binary directory and cache directory exist
        Files.createDirectories(deployableMcpServer.directory());
        Files.createDirectories(toolGatewayConfigProperties.getArtifactCache());
        
        // Create test binary file if it doesn't exist
        if (!Files.exists(deployableMcpServer.binary())) {
            Files.createDirectories(deployableMcpServer.binary().getParent());
            Files.write(deployableMcpServer.binary(), "test binary content".getBytes());
        }

        // Setup tool state
        toolState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(new ArrayList<>())
                .build();

        // Setup redeploy request
        redeployRequest = new ToolModels.Redeploy("test-rollback-server");

        // Reset mocks
        reset(redeployFunction, dynamicMcpToolCallbackProvider, mcpSyncServerDelegate, mockClient);

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(new McpSchema.Tool("some-tool", "a tool", JsonSchemaGenerator.generateForType(String.class))), null));

        toolDecoratorService.doPerformInit();
    }

    @Test
    void shouldSuccessfullyRedeployWhenNoErrors() {
        // Given
        RedeployFunction.RedeployDescriptor successDescriptor = RedeployFunction.RedeployDescriptor.builder()
                .isSuccess(true)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(deployableMcpServer))
                .thenReturn(successDescriptor);

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(new McpSchema.Tool("some-tool", "a tool", JsonSchemaGenerator.generateForType(String.class))), null));

        // When
        Redeploy.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        assertThat(this.toolGatewayConfigProperties.getArtifactCache().resolve(this.toolGatewayConfigProperties.getDeployableMcpServers().get("test-rollback-server")
                .binary().toFile().getName()).toFile()).exists();

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
        verify(mcpSyncServerDelegate, atLeastOnce()).addTool(any());
    }

    @Test
    void shouldRollbackWhenRedeployFailsAndBinaryExists() throws IOException {
        // Given - Ensure binary file exists
        Files.write(deployableMcpServer.binary(), "original content".getBytes());

        RedeployFunction.RedeployDescriptor failedDescriptor = RedeployFunction.RedeployDescriptor.builder()
                .isSuccess(false)
                .err("Deploy failed")
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(deployableMcpServer))
                .thenReturn(failedDescriptor);

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenReturn(true);

        // When
        Redeploy.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
        // Verify that rollback preparation occurred by checking cache directory
        assertThat(Files.exists(toolGatewayConfigProperties.getArtifactCache().resolve(deployableMcpServer.binary().getFileName().toString())))
                .isTrue();
    }

    @Test
    void shouldHandleFailedRedeployWithoutRollbackWhenBinaryDoesNotExist() throws IOException {
        // Given - Use a non-existent binary path
        Path nonExistentBinary = deployableMcpServer.directory().resolve("non-existent.jar");
        ToolGatewayConfigProperties.DeployableMcpServer serverWithoutBinary = 
                new ToolGatewayConfigProperties.DeployableMcpServer(
                        "test-rollback-server",
                        "echo 'deploy'",
                        deployableMcpServer.directory(),
                        nonExistentBinary);

        RedeployFunction.RedeployDescriptor failedDescriptor = RedeployFunction.RedeployDescriptor.builder()
                .isSuccess(false)
                .err("Deploy failed")
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(serverWithoutBinary))
                .thenReturn(failedDescriptor);

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Deploy failed")));

        // When
        Redeploy.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, serverWithoutBinary, toolState);
        
        // Then
        verify(redeployFunction).performRedeploy(serverWithoutBinary);
        verify(mcpSyncServerDelegate, atLeastOnce()).removeTool(any());
    }

    @Test
    void shouldHandleConnectionErrorAfterSuccessfulDeploy() {
        // Given
        RedeployFunction.RedeployDescriptor successDescriptor = RedeployFunction.RedeployDescriptor.builder()
                .isSuccess(true)
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(deployableMcpServer))
                .thenReturn(successDescriptor);

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Connection failed")));

        // When
        Redeploy.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
    }

    @Test
    void shouldHandleRollbackWithConnectionFailure() throws IOException {
        // Given - Create binary that exists
        Files.write(deployableMcpServer.binary(), "original content".getBytes());

        RedeployFunction.RedeployDescriptor failedDescriptor = RedeployFunction.RedeployDescriptor.builder()
                .isSuccess(false)
                .err("Deploy failed")
                .build();

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(deployableMcpServer))
                .thenReturn(failedDescriptor);

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenReturn(false);

        // When
        Redeploy.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
    }

    @Test
    void shouldHandleSuccessfulRedeployWithToolsAdded() {
        // Given
        RedeployFunction.RedeployDescriptor successDescriptor = RedeployFunction.RedeployDescriptor.builder()
                .isSuccess(true)
                .build();

        McpSchema.Tool testTool = new McpSchema.Tool("test-tool", "A test tool", JsonSchemaGenerator.generateForType(String.class));

        when(dynamicMcpToolCallbackProvider.killClientAndThen(eq("test-rollback-server"), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> callback = invocation.getArgument(1);
                    return callback.get();
                });

        when(redeployFunction.performRedeploy(deployableMcpServer))
                .thenReturn(successDescriptor);

        when(dynamicMcpToolCallbackProvider.buildClient("test-rollback-server"))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(testTool), null));

        // When
        Redeploy.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
        verify(mcpSyncServerDelegate, atLeastOnce()).addTool(any());
    }
}