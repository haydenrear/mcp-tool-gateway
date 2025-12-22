package com.hayden.mcptoolgateway.tool.tool_state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.security.IdentityResolver;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.utilitymodule.concurrent.striped.StripedLock;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.free.Free;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.jwt.Jwt;
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
    @Autowired
    IdentityResolver identityResolver;

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

    boolean clientHasServer(String client, String toolName) {
        return syncClients.get(client).client().listTools().tools()
                .stream().anyMatch(t -> t.name().equals(toolName));
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
        var d = new McpServerToolStates.DeployedService(deployService, identityResolver.resolveUserOrDefault(mcpServerToolState));
        return Free.<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult>
                        liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.BuildClient(d, mcpServerToolState, null))
                .flatMap(sc -> Free
                        .<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult>liftF(
                                new ToolDecoratorInterpreter.ToolDecoratorEffect.AddMcpServerToolState(
                                        new ToolDecorator.ToolDecoratorToolStateUpdate.AddToolToolStateUpdate(deployService, mcpServerToolState, sc.getToolStateChanges())))
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
        return createSetClientErr(getAuthDeployedService(service, mcpServerToolState), m, mcpServerToolState);
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
                    .name(service)
                    .toolsRemoved(removed)
                    .toolState(mcpServerToolState)
                    .tools(tools)
                    .err(m.getMessage())
                    .build();
        }

        return ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult.builder()
                .name(service)
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

    DelegateMcpClient doSetMcpClient(McpSyncClient m,
                                     McpServerToolStates.DeployedService deployService,
                                     ToolDecoratorService.McpServerToolState mcpServerToolState) {
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

    public McpServerToolStates.@NotNull DeployedService getAuthDeployedService(String deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) {
        return new McpServerToolStates.DeployedService(deployService, identityResolver.resolveUserOrDefault(mcpServerToolState));
    }

    public void addCallbacks(ToolDecorator.McpServerToolStateChange.AddCallbacks addCallbacks) {
        throw new RuntimeException("Not implemented!");
    }


    public interface DelegateMcpClient {


        McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest);


        McpSchema.ListToolsResult listTools();

        void setClient(McpSyncClient client);

        void clearError();

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

        private IdentityResolver identityResolver;

        private final ToolDecoratorService.McpServerToolState toolState;

        public MultipleClientDelegateMcpClient(IdentityResolver identityResolver,
                                               ToolDecoratorService.McpServerToolState toolState) {
            this.identityResolver = identityResolver;
            this.toolState = toolState;
        }

        @Override
        public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
            var resolved = identityResolver.resolveUserOrDefault(this.toolState);
            return clients.get(resolved).callTool(callToolRequest);
        }

        @Override
        public McpSchema.ListToolsResult listTools() {
            var resolved = identityResolver.resolveUserOrDefault(this.toolState);
            return clients.get(resolved).listTools();
        }

        @Override
        public void setClient(McpSyncClient client) {
            var resolved = identityResolver.resolveUserOrDefault(this.toolState);
            this.clients.compute(resolved, (key, prev) -> {
                if (prev == null) {
                    prev = DelegateMcpClientFactory.getSingleDelegateMcpClient(this.toolState, identityResolver);
                }

                prev.setClient(client);
                prev.clearError();
                return prev;
            });
        }

        @Override
        public void clearError() {
            String s = identityResolver.resolveUserOrDefault(this.toolState);
            this.clients.computeIfPresent(s, (key, prev) -> {
                prev.clearError();
                return prev;
            });
        }

        @Override
        public void setError(String error) {
            String s = identityResolver.resolveUserOrDefault(this.toolState);
            this.clients.compute(s, (key, prev) -> {
                if (prev == null) {
                    prev = DelegateMcpClientFactory.getSingleDelegateMcpClient(this.toolState, identityResolver);
                }

                prev.setClient(null);
                prev.setError(error);
                return prev;
            });
        }

        @Override
        public McpSyncClient client() {
            var resolved = identityResolver.resolveUserOrDefault(this.toolState);
            return this.clients.get(resolved).client();
        }

        @Override
        public McpSchema.Implementation getClientInfo() {
            var resolved = identityResolver.resolveUserOrDefault(this.toolState);
            return this.clients.get(resolved).getClientInfo();
        }

        @Override
        public boolean isInitialized() {
            var resolved = identityResolver.resolveUserOrDefault(this.toolState);
            return this.clients.get(resolved).isInitialized();
        }

        @Override
        public String error() {
            var resolved = identityResolver.resolveUserOrDefault(this.toolState);
            return this.clients.get(resolved).error();
        }
    }

    @Data
    public static class SingleDelegateMcpClient implements DelegateMcpClient {

        private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

        volatile Instant lastAccessed;

        McpSyncClient client;

        boolean isStdio;

        IdentityResolver identityResolver;

        List<ToolDecoratorService.BeforeToolCallback> beforeToolCallback;

        List<ToolDecoratorService.AfterToolCallback> afterToolCallback;

        ToolDecoratorService.McpServerToolState toolState;

        public SingleDelegateMcpClient(List<ToolDecoratorService.AfterToolCallback> afterToolCallback,
                                       List<ToolDecoratorService.BeforeToolCallback> beforeToolCallback,
                                       IdentityResolver identityResolver,
                                       ToolDecoratorService.McpServerToolState toolState) {
            this.afterToolCallback = afterToolCallback;
            this.beforeToolCallback = beforeToolCallback;
            this.identityResolver = identityResolver;
            this.toolState = toolState;
        }


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

                var resolved = identityResolver.resolveIdentityToken(this.toolState);


                if (resolved != null) {
                    callToolRequest.arguments().put(ToolDecoratorService.AUTH_BODY_FIELD, resolved);
                }

                Optional<Jwt> jwt = Optional.empty();

                if (!CollectionUtils.isEmpty(beforeToolCallback)
                    || !CollectionUtils.isEmpty(afterToolCallback)) {
                    jwt = identityResolver.resolveJwt(this.toolState);
                }

                for (var beforeToolCallback : beforeToolCallback) {
                    beforeToolCallback.on(callToolRequest, jwt.orElse(null));
                }

                var called = client.callTool(callToolRequest);

                for (var after : afterToolCallback) {
                    after.on(callToolRequest, called, jwt.orElse(null));
                }

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
        public void clearError() {
            try {
                this.readWriteLock.writeLock().lock();
                this.error = null;
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
            if (toolState == null)
                throw new RuntimeException("Could not find tool state!");

            return toolState.deployableMcpServer().isStdio();
        }
    }
}
