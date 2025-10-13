package com.hayden.mcptoolgateway.tool.tool_state;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.result.Result;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

@SpringBootTest
@ActiveProfiles("rollback-tests")
class SetClientsTest {

    @Autowired
    private McpSyncServerDelegate mcpSyncServer;

    @Autowired
    private DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    @Autowired
    private McpSyncClient mockClient;

    @Autowired
    private SetClients setClients;

    @Autowired
    private ToolGatewayConfigProperties toolGatewayConfigProperties;

    private ToolDecoratorService.McpServerToolState mockToolState;
    private ToolGatewayConfigProperties.DeployableMcpServer testServer;

    @BeforeEach
    void setUp() throws IOException {

        // Use the test server from yml configuration
        testServer = toolGatewayConfigProperties.getDeployableMcpServers().get("test-rollback-server");
        testServer.setHasMany(false);

        mockToolState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(new ArrayList<>())
                .deployableMcpServer(testServer)
                .build();

        // Ensure directories exist
        Files.createDirectories(testServer.directory());
        Files.createDirectories(toolGatewayConfigProperties.getArtifactCache());
        
        // Create test copyToArtifactPath if it doesn't exist
        if (!Files.exists(testServer.copyToArtifactPath())) {
            Files.createDirectories(testServer.copyToArtifactPath().getParent());
            Files.write(testServer.copyToArtifactPath(), "test copyToArtifactPath content".getBytes());
        }
        
        // Reset all mocks
        reset(mcpSyncServer, dynamicMcpToolCallbackProvider, mockClient);
    }

    @Test
    void shouldReturnTrueWhenClientHasError() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Connection failed")));

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(result.wasSuccessful()).isFalse();
        assertThat(result.err()).isEqualTo("Connection failed");
        assertThat(setClients.clientHasError(clientName)).isTrue();
    }

    @Test
    void shouldReturnFalseWhenClientHasNoError() {
        // Given
        String clientName = "test-rollback-server";

        // When & Then
        assertThat(setClients.clientHasError(new McpServerToolStates.DeployedService(clientName, ToolDecoratorService.SYSTEM_ID).clientId())).isFalse();
    }

    @Test
    void shouldReturnTrueWhenHasClient() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));

        // When
        setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(setClients.hasClient(new McpServerToolStates.DeployedService(clientName, ToolDecoratorService.SYSTEM_ID).clientId())).isTrue();
    }

    @Test
    void shouldReturnTrueWhenNoMcpClient() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError("Connection failed")));

        // When
        setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(setClients.noMcpClient(clientName)).isTrue();
    }

    @Test
    void shouldReturnTrueWhenNoClientKey() {
        // Given
        String clientName = "non-existent-client";

        // When & Then
        assertThat(setClients.noClientKey(clientName)).isTrue();
    }

    @Test
    void shouldReturnTrueWhenClientInitialized() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenReturn(true);

        // When
        setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(setClients.clientInitialized(clientName)).isTrue();
    }

    @Test
    void shouldReturnErrorMessage() {
        // Given
        String clientName = "test-rollback-server";
        String errorMessage = "Connection timeout";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.err(new DynamicMcpToolCallbackProvider.McpError(errorMessage)));

        // When
        setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);
        String error = setClients.getError(clientName);

        // Then
        assertThat(error).isEqualTo(errorMessage);
    }

    @Test
    void shouldReturnFalseWhenMcpServerNotAvailable() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenReturn(false);

        // When
        setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(setClients.isMcpServerAvailable(clientName)).isFalse();
    }

    @Test
    void shouldSuccessfullySetMcpClientWithTools() {
        // Given
        String clientName = "test-rollback-server";
        McpSchema.Tool testTool = new McpSchema.Tool("test-tool", "A test tool", JsonSchemaGenerator.generateForType(String.class));
        List<McpSchema.Tool> tools = List.of(testTool);
        
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(tools, null));

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(result.tools()).contains("test-rollback-server-test-tool");
        assertThat(result.toolsAdded()).contains("test-rollback-server-test-tool");
        assertThat(result.providers()).hasSize(1);
        verify(mcpSyncServer).addTool(any());
    }

    @Test
    void shouldHandleToolCreationException() {
        // Given
        String clientName = "test-rollback-server";
        McpSchema.Tool testTool = new McpSchema.Tool("test-tool", "A test tool", JsonSchemaGenerator.generateForType(String.class));
        List<McpSchema.Tool> tools = List.of(testTool);
        
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(tools, null));

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then - Even with JSON processing issues, the operation should handle gracefully
        assertThat(result.wasSuccessful()).isTrue();
    }

    @Test
    void shouldUpdateExistingToolsCorrectly() throws Exception {
        // Given
        String clientName = "test-rollback-server";
        
        // Setup existing state with one tool
        ToolCallbackProvider existingProvider = mock(ToolCallbackProvider.class);
        org.springframework.ai.tool.ToolCallback existingCallback = mock(org.springframework.ai.tool.ToolCallback.class);
        ToolDefinition existingDefinition = mock(ToolDefinition.class);
        
        when(existingProvider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[]{existingCallback});
        when(existingCallback.getToolDefinition()).thenReturn(existingDefinition);
        when(existingDefinition.name()).thenReturn("test-rollback-server-old-tool");
        
        mockToolState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(List.of(existingProvider))
                .build();

        // Setup new tools (one new tool, existing one removed)
        McpSchema.Tool newTool = new McpSchema.Tool("new-tool", "A new tool", JsonSchemaGenerator.generateForType(String.class));
        List<McpSchema.Tool> newTools = List.of(newTool);

        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(newTools, null));

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(result.wasSuccessful()).isTrue();
        assertThat(result.toolsAdded()).contains("test-rollback-server-new-tool");
        assertThat(result.toolsRemoved()).contains("test-rollback-server-old-tool");
        verify(mcpSyncServer).addTool(any());
        verify(mcpSyncServer).removeTool("test-rollback-server-old-tool");
    }

    @Test
    void shouldCreateSetClientErrorWhenBuildClientFails() {
        // Given
        String clientName = "test-rollback-server";
        DynamicMcpToolCallbackProvider.McpError error = new DynamicMcpToolCallbackProvider.McpError("Build failed");

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.createSetClientErr(clientName, error, mockToolState);

        // Then
        assertThat(result.wasSuccessful()).isFalse();
        assertThat(result.err()).isEqualTo("Build failed");
    }

    @Test
    void shouldRemoveToolsWhenCreatingErrorWithExistingState() {
        // Given
        String clientName = "test-rollback-server";
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        org.springframework.ai.tool.ToolCallback toolCallback = mock(org.springframework.ai.tool.ToolCallback.class);
        ToolDefinition toolDefinition = mock(ToolDefinition.class);
        
        when(provider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[]{toolCallback});
        when(toolCallback.getToolDefinition()).thenReturn(toolDefinition);
        when(toolDefinition.name()).thenReturn("existing-tool");

        mockToolState = ToolDecoratorService.McpServerToolState.builder()
                .toolCallbackProviders(List.of(provider))
                .build();

        DynamicMcpToolCallbackProvider.McpError error = new DynamicMcpToolCallbackProvider.McpError("Build failed");

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.createSetClientErr(clientName, error, mockToolState);

        // Then
        assertThat(result.wasSuccessful()).isFalse();
        assertThat(result.toolsRemoved()).contains("existing-tool");
        verify(mcpSyncServer).removeTool("existing-tool");
    }

    @Test
    void shouldHandleListToolsException() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenThrow(new RuntimeException("List tools failed"));

        // When
        ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult result = setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(result.wasSuccessful()).isFalse();
        assertThat(result.err()).isEqualTo("List tools failed");
    }

    @Test
    void shouldReturnCorrectErrorMessageForNoError() {
        // Given
        String clientName = "test-rollback-server";

        // When
        String error = setClients.getError(clientName);

        // Then
        assertThat(error).isEqualTo("Client has no error.");
    }

    @Test
    void shouldHandleMcpServerAvailabilityCheck() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenReturn(true);

        // When
        setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // Then
        assertThat(setClients.isMcpServerAvailable(clientName)).isTrue();
    }

    @Test
    void shouldHandleExceptionInMcpServerAvailabilityCheck() {
        // Given
        String clientName = "test-rollback-server";
        when(dynamicMcpToolCallbackProvider.buildClient(clientName))
                .thenReturn(Result.ok(mockClient));
        when(mockClient.getClientInfo()).thenReturn(new McpSchema.Implementation("test-rollback-server", "1.0.0"));
        when(mockClient.listTools()).thenReturn(new McpSchema.ListToolsResult(Collections.emptyList(), null));
        when(mockClient.isInitialized()).thenThrow(new RuntimeException("Connection error"));

        setClients.setParseMcpClientUpdateToolState(clientName, mockToolState);

        // When & Then
        assertThat(setClients.isMcpServerAvailable(clientName)).isFalse();
    }
}