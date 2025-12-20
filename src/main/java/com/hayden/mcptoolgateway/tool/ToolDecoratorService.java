package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.free.Free;
import io.modelcontextprotocol.client.transport.AuthAwareHttpSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
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

    public record ToolCallbackDescriptor(ToolCallbackProvider provider, org.springframework.ai.tool.ToolCallback toolCallback) {}

    public interface BeforeToolCallback {
        void on(McpSchema.CallToolRequest callToolRequest, @Nullable Jwt id);
    }

    public interface AfterToolCallback {
        void on(McpSchema.CallToolRequest callToolRequest, McpSchema.CallToolResult result, @Nullable Jwt id);
    }

    @Builder(toBuilder = true)
    public record McpServerToolState(
            List<ToolCallbackProvider> toolCallbackProviders,
            ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor lastDeploy,
            ToolGatewayConfigProperties.DecoratedMcpServer deployableMcpServer,
            List<AddClient> added,
            List<BeforeToolCallback> beforeToolCallback,
            List<AfterToolCallback> afterToolCallback) { }

    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;
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

    public record AddToolSearch(String name, String userName)  {}

    public record AddSyncClientResult(boolean success, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult underlying) {
        public AddSyncClientResult(boolean success) {
            this(success, null);
        }
    }

    public AddSyncClientResult createAddServer(ToolDecoratorInterpreter.ToolDecoratorEffect.DoToolSearch add) {
        return createAddServer(new AddToolSearch(add.toSearch().tool(), null)) ;
    }

    public AddSyncClientResult createAddServer(AddToolSearch add) {
        String name = add.name + add.userName;

        if (!this.toolStates.contains(name)) {
            return new AddSyncClientResult(false);
        }

        var a = this.toolGatewayConfigProperties.getAddableMcpServers().get(add.name);

        if (a == null)
            return new AddSyncClientResult(false);

        var setE = toolStates.setMcpClient(
                new McpServerToolStates.DeployedService(name, null),
                this.toolStates.copyOf().get(name));

        var set = Free.parse(setE, toolDecoratorInterpreter);

        if (set instanceof ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult m) {
            if (m.wasSuccessful())
                this.toolStates.addClient(new AddClient(add.name, name, null));

            Optional.of(m)
                    .map(ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult::toolState)
                    .ifPresent(toolState -> this.toolStates.addUpdateToolState(name, toolState));

            return new AddSyncClientResult(m.wasSuccessful(), m);
        }

        return new AddSyncClientResult(false);
    }

    public AddSyncClientResult createAddTool(AddClient serverName) {
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
                    .map(ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult::toolState)
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
                                                    new ToolDecorator.ToolDecoratorToolStateUpdate.AddToolToolStateUpdate(d.getKey(), s.toolState(), s.getToolStateChanges()))));
                        } else {
                            log.error("Found unknown tool decorator result while executing buildTools {}", toolDecoratorResult.getClass().getName());
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

        if (res instanceof ToolDecoratorInterpreter.ToolDecoratorResult.UpdatedToolState
                || res instanceof ToolDecoratorInterpreter.ToolDecoratorResult.UpdatedToolMcp) {
            log.info("Found updated tool decorator result {}", res.getClass().getName());
        } else {
            log.error("Found unknown tool decorator result after making update {}", res.getClass().getName());
        }
   }

}