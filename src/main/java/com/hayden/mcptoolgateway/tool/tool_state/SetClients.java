package com.hayden.mcptoolgateway.tool.tool_state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.free.Free;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import com.hayden.mcptoolgateway.security.AuthResolver;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ReflectionUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Component
@Slf4j
class SetClients {

    /**
     * TODO: this should just contain the specifications in the return types, do it all in the other one.
     */
    @Autowired
    McpSyncServerDelegate mcpSyncServerDelegate;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @Autowired
    DelegateMcpClientFactory delegateMcpClientFactory;
    @Autowired
    @Lazy
    ToolDecoratorInterpreter toolDecoratorInterpreter;

    final Map<String, DelegateMcpClient> syncClients = new ConcurrentHashMap<>();

    boolean clientHasError(String clientName) {
        return syncClients.containsKey(clientName) && StringUtils.isNotBlank(syncClients.get(clientName).error());
    }

    boolean hasClient(String clientName) {
        return syncClients.containsKey(clientName) && syncClients.get(clientName).client() != null;
    }

    boolean noMcpClient(String clientName) {
        return syncClients.containsKey(clientName) && syncClients.get(clientName).client() == null;
    }

    boolean noClientKey(String clientName) {
        return !syncClients.containsKey(clientName);
    }

    public boolean clientExistsNotInitialized(String service) {
        return hasClient(service)
                && !this.syncClients.get(service).isInitialized();
    }

    public boolean clientInitialized(String service) {
        return isMcpServerAvailable(service);
    }

    String getError(String clientName) {
        return clientHasError(clientName) ? syncClients.get(clientName).error() : "Client has no error.";
    }

    boolean isMcpServerAvailable(String key) {
        try {
            var isInit = Optional.ofNullable(this.syncClients.get(key))
                    .map(s -> s.isInitialized())
                    .orElse(false);
            return isInit;
        } catch (Exception e) {
            return false;
        }
    }

    @StripedLock
    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> setMcpClient(McpServerToolStates.DeployedService deployService,
                                                                                                                         ToolDecoratorService.McpServerToolState mcpServerToolState,
                                                                                                                         NamedClientMcpTransport transport) {
        return Free.liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.BuildClient(deployService, mcpServerToolState, transport));
    }

    @StripedLock
    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult setParseMcpClientUpdateToolState(String deployService,
                                                                                                             ToolDecoratorService.McpServerToolState mcpServerToolState) {
        var parsed = Free.parse(
                setMcpClientUpdateToolState(deployService, mcpServerToolState)
                        .flatMap(Free::pure),
                toolDecoratorInterpreter);

        if (parsed instanceof ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult r) {
            return r;
        }

        return null;
    }

    @StripedLock
    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult> setMcpClientUpdateToolState(
            String deployService,
            ToolDecoratorService.McpServerToolState mcpServerToolState) {
        var d = new McpServerToolStates.DeployedService(deployService, AuthResolver.resolveUserOrDefault());
        return Free.<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult>
                        liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.BuildClient(d, mcpServerToolState, null))
                .flatMap(sc -> Free
                        .<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult>liftF(
                                new ToolDecoratorInterpreter.ToolDecoratorEffect.AddMcpServerToolState(
                                        new ToolDecorator.ToolDecoratorToolStateUpdate(deployService, mcpServerToolState, sc.getToolStateChanges())))
                        .flatMap(s -> Free.pure(sc)));
    }

    @StripedLock
    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> setMcpClient(McpServerToolStates.DeployedService deployService,
                                                                                                                         ToolDecoratorService.McpServerToolState mcpServerToolState) {

        return Free.liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.BuildClient(deployService, mcpServerToolState, null));
    }

    @StripedLock
    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> killClientAndThenFree(
            ToolDecoratorService.McpServerToolState toolState,
            String clientName,
            Supplier<Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult>> toDo) {
        if (toolState == null || CollectionUtils.isEmpty(toolState.added())) {
            return this.dynamicMcpToolCallbackProvider.killClientAndThen(clientName, toDo);
        } else {
            toolState.added()
                    .forEach(aClient -> {
                        var found = new McpServerToolStates.DeployedService(aClient.serverName(), aClient.userName());
                        this.dynamicMcpToolCallbackProvider.killClientAndThen(found.clientId(), () -> true);
                    });

            return this.dynamicMcpToolCallbackProvider.killClientAndThen(clientName, toDo);
        }
    }

    @StripedLock
    public <T> T killClientAndThen(ToolDecoratorService.McpServerToolState toolState,
                                   String clientName,
                                   Supplier<T> toDo) {
        if (CollectionUtils.isEmpty(toolState.added())) {
            return this.dynamicMcpToolCallbackProvider.killClientAndThen(clientName, toDo);
        } else {
            toolState.added()
                    .forEach(aClient -> {
                        var found = new McpServerToolStates.DeployedService(aClient.serverName(), aClient.userName());
                        this.dynamicMcpToolCallbackProvider.killClientAndThen(found.clientId(), () -> true);
                    });

            return this.dynamicMcpToolCallbackProvider.killClientAndThen(clientName, toDo);
        }
    }

    @StripedLock
    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult createSetClientErr(String service,
                                                                                               DynamicMcpToolCallbackProvider.McpError m,
                                                                                               ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return createSetClientErr(McpServerToolStates.getAuthDeployedService(service), m, mcpServerToolState);
    }

    @StripedLock
    public ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult createSetClientErr(McpServerToolStates.DeployedService service,
                                                                                               DynamicMcpToolCallbackProvider.McpError m,
                                                                                               ToolDecoratorService.McpServerToolState mcpServerToolState) {
        this.syncClients.compute(service.deployService(), (key, prev) -> {
            if (prev == null) {
                var created = delegateMcpClientFactory.clientFactory(mcpServerToolState);
                created.setError(m.getMessage());
                return created;
            }

            prev.setError(m.getMessage());
            return prev;
        });

        Set<String> removed = new HashSet<>();
        Set<String> tools = new HashSet<>();

        if (containsToolCallbackProviders(mcpServerToolState)) {

            for (var tcp : mcpServerToolState.toolCallbackProviders()) {
                for (var tc : tcp.getToolCallbacks()) {
                    mcpSyncServerDelegate.removeTool(tc.getToolDefinition().name());
                    removed.add(tc.getToolDefinition().name());
                }
            }

            return ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult.builder()
                    .toolsRemoved(removed)
                    .toolState(mcpServerToolState)
                    .tools(tools)
                    .err(m.getMessage())
                    .build();
        }

        return ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult.builder()
                .tools(tools)
                .toolState(mcpServerToolState)
                .err(m.getMessage())
                .build();
    }


    @NotNull Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult> setMcpClientUpdateTools(
            McpSyncClient m,
            McpServerToolStates.DeployedService deployService,
            ToolDecoratorService.McpServerToolState mcpServerToolState,
            ToolDecoratorInterpreter.ToolDecoratorResult.SetMcpClientResult s) {
        var mcpClient = s.delegate();
        if (containsToolCallbackProviders(mcpServerToolState)) {
            return Free.liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.UpdatingExistingToolDecorator(mcpClient, mcpServerToolState, deployService));
        } else {
            return Free.liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.CreateNewToolDecorator(mcpClient, deployService, mcpServerToolState));
        }
    }

    DelegateMcpClient doSetMcpClient(McpSyncClient m, McpServerToolStates.DeployedService deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        var mcpClient = this.syncClients.compute(deployService.deployService(), (key, prev) -> {
            if (prev == null) {
                prev = this.delegateMcpClientFactory.clientFactory(mcpServerToolState);
            }

            prev.setClient(m);
            return prev;
        });
        return mcpClient;
    }

    private boolean containsToolCallbackProviders(ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return mcpServerToolState != null
                && !CollectionUtils.isEmpty(mcpServerToolState.toolCallbackProviders());
    }


    public interface DelegateMcpClient {


        McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest);


        McpSchema.ListToolsResult listTools();

        void setClient(McpSyncClient client);

        void setError(String error);

        McpSyncClient client();


        McpSchema.Implementation getClientInfo();


        boolean isInitialized();


        String error();
    }

    /**
     * Fan out to multiple servers.
     */
    @Data
    public static class MultipleClientDelegateMcpClient implements DelegateMcpClient {

        private final Map<String, SingleDelegateMcpClient> clients = new ConcurrentHashMap<>();

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            var resolved = AuthResolver.resolveUser();
            return clients.get(resolved).callTool(callToolRequest);
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            var resolved = AuthResolver.resolveUser();
            return clients.get(resolved).listTools();
        }

        @Override
        public void setClient(McpSyncClient client) {
            var resolved = AuthResolver.resolveUser();
            this.clients.compute(resolved, (key, prev) -> {
                if (prev == null) {
                    prev = new SingleDelegateMcpClient();
                }

                prev.setClient(client);
                prev.setError(null);
                return prev;
            });
        }

        @Override
        public void setError(String error) {
            this.clients.compute(AuthResolver.resolveUser(), (key, prev) -> {
                if (prev == null) {
                    prev = new SingleDelegateMcpClient();
                }

                prev.setClient(null);
                prev.setError(error);
                return prev;
            });
        }

        @Override
        public McpSyncClient client() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).client();
        }

        @Override
        public McpSchema.Implementation getClientInfo() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).getClientInfo();
        }

        @Override
        public boolean isInitialized() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).isInitialized();
        }

        @Override
        public String error() {
            var resolved = AuthResolver.resolveUser();
            return this.clients.get(resolved).error();
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SingleDelegateMcpClient implements DelegateMcpClient {

        private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

        volatile Instant lastAccessed;

        McpSyncClient client;

        boolean isStdio;

        /**
         * TODO: last error to return - or last error log file
         */
        String error;

        @Override
        public String error() {
            return error;
        }

        public McpSchema.Implementation getClientInfo() {
            lastAccessed = Instant.now();
            return client.getClientInfo();
        }

        public McpSchema.ClientCapabilities getClientCapabilities() {
            lastAccessed = Instant.now();
            return client.getClientCapabilities();
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            lastAccessed = Instant.now();
            return client.listTools();
        }

        @Override
        public boolean isInitialized() {
            lastAccessed = Instant.now();
            return client.isInitialized();
        }

        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            try {
                if (isStdio) {
                    readWriteLock.writeLock().lock();
                } else {
                    readWriteLock.readLock().lock();
                }

                var resolved = AuthResolver.resolveBearerHeader();

                if (resolved != null) {
                    callToolRequest.arguments().put(ToolDecoratorService.AUTH_BODY_FIELD, resolved);
                }

                var called = client.callTool(callToolRequest);

                return called;
            } finally {
                lastAccessed = Instant.now();
                if (isStdio) {
                    readWriteLock.writeLock().unlock();
                } else {
                    readWriteLock.readLock().unlock();
                }
            }
        }

        public void setClient(McpSyncClient client) {
            try {
                this.readWriteLock.writeLock().lock();
                this.client = client;
                this.error = null;
                this.isStdio = client == null || isStdio(client);
            } finally {
                lastAccessed = Instant.now();
                this.readWriteLock.writeLock().unlock();
            }
        }

        @Override
        public void setError(String error) {
            try {
                this.readWriteLock.writeLock().lock();
                this.error = error;
                this.client = null;
            } finally {
                lastAccessed = Instant.now();
                this.readWriteLock.writeLock().unlock();
            }
        }

        @Override
        public McpSyncClient client() {
            lastAccessed = Instant.now();
            return client;
        }

        private boolean isStdio(McpSyncClient client) {
            try {
                var delegate = client.getClass().getDeclaredField("delegate");
                ReflectionUtils.makeAccessible(delegate);
                McpAsyncClient asyncClient = (McpAsyncClient) ReflectionUtils.getField(delegate, client);
                var clientTransportField = McpAsyncClient.class.getDeclaredField("transport");
                ReflectionUtils.makeAccessible(clientTransportField);
                var clientTransport = (McpClientTransport) ReflectionUtils.getField(clientTransportField, asyncClient);
                return clientTransport instanceof StdioClientTransport;
            } catch (NoSuchFieldException e) {
                log.error("Could not get delegate field.", e);
                return true;
            } catch (Exception e){
                log.error("Could not get delegate field.", e);
                return true;
            }
        }



    }
}
