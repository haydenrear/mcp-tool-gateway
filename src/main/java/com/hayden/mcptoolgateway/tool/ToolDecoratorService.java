package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.free.Free;
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
            ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor lastDeploy,
            ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer,
            List<AddClient> added) { }

    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;
    @Autowired
    List<ToolDecorator> toolDecorators;
    @Autowired
    McpServerToolStates toolStates;
    @Autowired
    ToolDecoratorInterpreter toolDecoratorInterpreter;

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

    public record AddSyncClientResult(boolean success, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult underlying) {
        public AddSyncClientResult(boolean success) {
            this(success, null);
        }
    }

    public AddSyncClientResult createAddClient(AddClient serverName) {
        String name = serverName.serverName + serverName.userName;

        if (this.toolStates.contains(name)) {
            return new AddSyncClientResult(true);
        }

        var setE = toolStates.setMcpClient(
                new McpServerToolStates.DeployedService(name, serverName.userName),
                this.toolStates.copyOf().get(serverName.serverName),
                new NamedClientMcpTransport(
                        name,
                        AuthAwareHttpSseClientTransport.authAwareBuilder(serverName.hostName)
                                .build()));

        var set = Free.parse(setE, toolDecoratorInterpreter);

        if (set instanceof ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult m) {
            if (m.wasSuccessful())
                this.toolStates.addClient(serverName);

            Optional.of(m)
                    .map(s -> McpServerToolState.builder().toolCallbackProviders(m.providers()).build())
                    .ifPresent(toolState -> this.toolStates.addUpdateToolState(name, toolState));
            return new AddSyncClientResult(m.wasSuccessful(), m);
        }

        return new AddSyncClientResult(false);

    }

    private void buildTools() {
        Map<String, ToolDecoratorInterpreter.ToolDecoratorEffect.AddMcpServerToolState> decoratedTools = toolGatewayConfigProperties
                .getDeployableMcpServers()
                .entrySet()
                .stream()
                .flatMap(d -> {
                    try {
                        var m = toolStates.setMcpClient(
                                new McpServerToolStates.DeployedService(d.getKey(), SYSTEM_ID),
                                McpServerToolState.builder()
                                        .deployableMcpServer(d.getValue())
                                        .build());
                        var toolDecoratorResult = Free.parse(m, toolDecoratorInterpreter);
                        if (toolDecoratorResult instanceof ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult s) {
                            return Stream.of(
                                    Map.entry(
                                            d.getKey(),
                                            new ToolDecoratorInterpreter.ToolDecoratorEffect.AddMcpServerToolState(
                                                    new ToolDecorator.ToolDecoratorToolStateUpdate(d.getKey(), s.toolState(), s.getToolStateChanges()))));
                        } else {
                            log.error("Found unknown tool decorator result {}", toolDecoratorResult);
                        }

                        return Stream.empty();
                    } catch (Exception e) {
                        log.error("Could not build MCP tools {} with {}.",
                                d.getKey(), e.getMessage(), e);
                        if (this.toolGatewayConfigProperties.isFailOnMcpClientInit())
                            throw e;

                        return Stream.empty();
                    }
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));


        var res = Free.parse(Free.liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.AddManyMcpServerToolState(decoratedTools)), toolDecoratorInterpreter);

        if (res instanceof ToolDecoratorInterpreter.ToolDecoratorResult.UpdatedToolState tc) {
            log.info("Found updated tool decorator result {}", tc);
        } else {
            log.error("Found unknown tool decorator result {}", res);
        }
   }

}