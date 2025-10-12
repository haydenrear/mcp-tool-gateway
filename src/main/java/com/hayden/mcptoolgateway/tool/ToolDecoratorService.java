package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.transport.AuthAwareHttpSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class ToolDecoratorService {

    public static final String AUTH_BODY_FIELD = "_bearerToken";
    public static final String SYSTEM_ID = "default";

    @Builder(toBuilder = true)
    public record CreateToolCallbackProviderResult(ToolCallbackProvider provider, McpSchema.Tool toolName, Exception e) { }

    public record ToolCallbackDescriptor(ToolCallbackProvider provider, ToolCallback toolCallback) {}

    @Builder(toBuilder = true)
    public record McpServerToolState(
            List<ToolCallbackProvider> toolCallbackProviders,
            RedeployFunction.RedeployDescriptor lastDeploy,
            ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer,
            List<AddClient> added) { }

    @Builder(toBuilder = true)
    public record SetSyncClientResult(
            Set<String> tools,
            Set<String> toolsAdded,
            Set<String> toolsRemoved,
            String err,
            List<ToolCallbackProvider> providers) {
        public boolean wasSuccessful() {
            return StringUtils.isBlank(err);
        }
    }

    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;
    @Autowired
    List<ToolDecorator> toolDecorators;
    @Autowired
    McpServerToolStates toolStates;

    @PostConstruct
    public void init() {
        if (this.toolGatewayConfigProperties.isStartMcpServerOnInitialize())
            doPerformInit();
    }

    public void doPerformInit() {
        toolStates.doPerformInitialization(() -> {
            buildTools();
            return true;
        });
    }

    public record AddClient(String serverName, String userName, String hostName)  {}

    public record AddSyncClientResult(boolean success, SetSyncClientResult underlying) {
        public AddSyncClientResult(boolean success) {
            this(success, null);
        }
    }

    public AddSyncClientResult createAddClient(AddClient serverName) {
        String name = serverName.serverName + serverName.userName;

        if (this.toolStates.contains(name)) {
            return new AddSyncClientResult(true);
        }

        var m = toolStates.setMcpClient(
                new McpServerToolStates.DeployedService(name, serverName.userName),
                this.toolStates.copyOf().get(serverName.serverName),
                new NamedClientMcpTransport(
                        name,
                        AuthAwareHttpSseClientTransport.authAwareBuilder(serverName.hostName)
                                .build()));

        if (m != null && m.wasSuccessful())
            this.toolStates.addClient(serverName);

        Optional.ofNullable(m)
                .map(s -> McpServerToolState.builder().toolCallbackProviders(m.providers).build())
                .ifPresent(toolState -> this.toolStates.addUpdateToolState(name, toolState));
        return new AddSyncClientResult(m != null && m.wasSuccessful(), m);
    }

    private void buildTools() {
        Map<String, McpServerToolState> decoratedTools = toolGatewayConfigProperties
                .getDeployableMcpServers()
                .entrySet()
                .stream()
                .flatMap(d -> {
                    try {
                        var m = toolStates.setMcpClient(new McpServerToolStates.DeployedService(d.getKey(), SYSTEM_ID), McpServerToolState.builder().build());
                        return Optional.ofNullable(m)
                                .stream()
                                .flatMap(s -> Stream.of(
                                        Map.entry(d.getKey(), McpServerToolState.builder().deployableMcpServer(d.getValue()).toolCallbackProviders(m.providers).build())));
                    } catch (Exception e) {
                        log.error("Could not build MCP tools {} with {}.",
                                d.getKey(), e.getMessage(), e);
                        if (this.toolGatewayConfigProperties.isFailOnMcpClientInit())
                            throw e;

                        return Stream.empty();
                    }
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        this.toolStates.addUpdateToolState(decoratedTools);

        this.toolDecorators.stream()
                .filter(ToolDecorator::isEnabled)
                .map(td -> td.decorate(decoratedTools))
                .forEach(this.toolStates::addUpdateToolState);
   }

}