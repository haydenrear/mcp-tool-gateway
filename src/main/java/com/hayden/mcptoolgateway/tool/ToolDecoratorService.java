package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
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


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class DelegateMcpSyncClient {
        McpSyncClient client;

        public synchronized McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            return client.callTool(callToolRequest);
        }

        /**
         * TODO: last error to return - or last error log file
         */
        String error;

        public synchronized void setClient(McpSyncClient client) {
            this.client = client;
        }

        public synchronized void setError(String error) {
            this.error = error;
        }

        public DelegateMcpSyncClient(McpSyncClient client) {
            this.client = client;
        }

        public DelegateMcpSyncClient(String error) {
            this.error = error;
        }
    }

    @Builder(toBuilder = true)
    record CreateToolCallbackProviderResult(ToolCallbackProvider provider, McpSchema.Tool toolName, Exception e) { }

    public record ToolCallbackDescriptor(ToolCallbackProvider provider, ToolCallback toolCallback) {}

    @Builder(toBuilder = true)
    public record McpServerToolState(
            List<ToolCallbackProvider> toolCallbackProviders,
            RedeployFunction.RedeployDescriptor lastDeploy) { }

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
    SetClients setMcpClient;
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

    private void buildTools() {
        Map<String, McpServerToolState> decoratedTools = toolGatewayConfigProperties
                .getDeployableMcpServers()
                .entrySet()
                .stream()
                .flatMap(d -> {
                    try {
                        var m = setMcpClient.setMcpClient(d.getKey(), McpServerToolState.builder().build());
                        return Optional.ofNullable(m)
                                .stream()
                                .flatMap(s -> Stream.of(
                                        Map.entry(d.getKey(), McpServerToolState.builder().toolCallbackProviders(m.providers).build())));
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