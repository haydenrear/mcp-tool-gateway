package com.hayden.mcptoolgateway.tool.deploy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.tool_state.McpSyncServerDelegate;
import com.hayden.mcptoolgateway.tool.TestUtil;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
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

        // Ensure the copyToArtifactPath directory and cache directory exist
        Files.createDirectories(deployableMcpServer.directory());
        Files.createDirectories(toolGatewayConfigProperties.getArtifactCache());
        
        // Create test copyToArtifactPath file if it doesn't exist
        if (!Files.exists(deployableMcpServer.copyToArtifactPath())) {
            Files.createDirectories(deployableMcpServer.copyToArtifactPath().getParent());
            Files.write(deployableMcpServer.copyToArtifactPath(), "test copyToArtifactPath content".getBytes());
        }

        // Setup tool state
        toolState = ToolDecoratorService.McpServerToolState
                .builder()
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
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor successDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        assertThat(this.toolGatewayConfigProperties.getArtifactCache()
                .resolve(this.toolGatewayConfigProperties.getDeployableMcpServers().get("test-rollback-server")
                .getCopyFromArtifactPath().toFile().getName()).toFile()).exists();

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
        verify(mcpSyncServerDelegate, atLeastOnce()).addTool(any());
    }

    @Test
    void shouldRollbackWhenRedeployFailsAndBinaryExists() throws IOException {
        // Given - Ensure copyToArtifactPath file exists
        TestUtil.writeToCopyTo("original content".getBytes(), deployableMcpServer);

        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor failedDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
        // Verify that rollback preparation occurred by checking cache directory
        assertThat(Files.exists(toolGatewayConfigProperties.getArtifactCache().resolve(deployableMcpServer.getCopyFromArtifactPath().getFileName().toString())))
                .isTrue();
    }

    @Test
    void shouldHandleFailedRedeployWithoutRollbackWhenBinaryDoesNotExist() {
        // Given - Use a non-existent copyToArtifactPath path
        Path nonExistentBinary = deployableMcpServer.directory().resolve("non-existent.jar");
        ToolGatewayConfigProperties.DeployableMcpServer serverWithoutBinary =
                ToolGatewayConfigProperties.DeployableMcpServer.builder().name(
                                "test-rollback-server")
                        .command(
                                "echo 'deploy'").directory(
                                deployableMcpServer.directory())
                        .copyToArtifactPath(nonExistentBinary)
                        .build();

        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor failedDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, serverWithoutBinary, toolState);
        
        // Then
        verify(redeployFunction).performRedeploy(serverWithoutBinary);
        verify(mcpSyncServerDelegate, atLeastOnce()).removeTool(any());
    }

    @Test
    void shouldHandleConnectionErrorAfterSuccessfulDeploy() {
        // Given
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor successDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
    }

    @Test
    void shouldHandleRollbackWithConnectionFailure() throws IOException {
        // Given - Create copyToArtifactPath that exists
        TestUtil.writeToCopyTo("original content".getBytes(), deployableMcpServer);

        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor failedDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
    }

    @Test
    void shouldHandleSuccessfulRedeployWithToolsAdded() {
        // Given
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor successDescriptor = ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor.builder()
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
        ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper result = redeploy.doRedeploy(redeployRequest, deployableMcpServer, toolState);

        // Then
        verify(redeployFunction).performRedeploy(deployableMcpServer);
        verify(mcpSyncServerDelegate, atLeastOnce()).addTool(any());
    }
}