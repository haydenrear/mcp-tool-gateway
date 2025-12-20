package com.hayden.mcptoolgateway.tool.tool_state;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.deploy.DeployModels;
import com.hayden.mcptoolgateway.tool.deploy.DeployService;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.deploy.fn.RollbackFunction;
import com.hayden.utilitymodule.MapFunctions;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.free.Effect;
import com.hayden.utilitymodule.free.Free;
import com.hayden.utilitymodule.free.Interpreter;
import com.hayden.utilitymodule.result.Result;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.micrometer.common.util.StringUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ToolDecoratorInterpreter
        implements Interpreter<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> {

    @Override
    public FreeErrorMapper<ToolDecoratorEffect, ToolDecoratorResult> mapErr() {
        return s -> {
            if(s.error().t() instanceof RuntimeException r) {
                throw r;
            }

            return Interpreter.super.mapErr().apply(s);
        };
    }

    public sealed interface ToolDecoratorEffect extends Effect {

        record PrepareRollback(
                ToolGatewayConfigProperties.DecoratedMcpServer d) implements ToolDecoratorEffect {
        }

        record PerformRedeploy(
                ToolGatewayConfigProperties.DecoratedMcpServer redeployMcpServer) implements ToolDecoratorEffect {
        }

        record PerformRollback(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DecoratedMcpServer d,
                ToolDecoratorResult.RedeployDescriptor r,
                ToolDecoratorService.McpServerToolState remove,
                DeployModels.DeployState deployState) implements ToolDecoratorEffect {
        }

        record UpdatingExistingToolDecorator(
                SetClients.DelegateMcpClient m,
                ToolDecoratorService.McpServerToolState removedState,
                McpServerToolStates.DeployedService deployService) implements ToolDecoratorEffect {
        }

        record PerformToolDecorators(
                AddManyMcpServerToolState manyMcpServerToolState) implements ToolDecoratorEffect {
        }

        record CreateNewToolDecorator(
                SetClients.DelegateMcpClient mcpClient,
                McpServerToolStates.DeployedService deployService,
                ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorEffect {
        }

        record SetErrorMcp(
                McpServerToolStates.DeployedService service,
                DynamicMcpToolCallbackProvider.McpError err,
                ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorEffect {
        }


        record SetClient(McpSyncClient m,
                         McpServerToolStates.DeployedService deployService,
                         ToolDecoratorService.McpServerToolState mcpServerToolState) implements ToolDecoratorEffect {
        }

        record SetMcpClientUpdateTools(
                McpSyncClient client,
                McpServerToolStates.DeployedService service,
                ToolDecoratorService.McpServerToolState toolState,
                NamedClientMcpTransport namedClientMcpTransport) implements ToolDecoratorEffect {
        }

        record AddMcpServerToolState(
                ToolDecorator.ToolDecoratorToolStateUpdate stateUpdate) implements ToolDecoratorEffect {
        }

        record AddManyMcpServerToolState(
                Map<String, AddMcpServerToolState> stateUpdate) implements ToolDecoratorEffect {
        }

        record UpdateMcpServerWithToolChanges(
                List<ToolDecorator.McpServerToolStateChange> toolStateChanges) implements ToolDecoratorEffect {
        }

        record DoRedeploy(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DecoratedMcpServer redeployMcpServer,
                ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorEffect {
        }

        record DoToolSearch(
                ToolModels.Add toSearch,
                ToolGatewayConfigProperties.DecoratedMcpServer redeployMcpServer,
                ToolDecoratorService.McpServerToolState toolState)  {
        }

        record KillClientAndRedeploy(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DecoratedMcpServer redeployMcpServer,
                ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorEffect {
        }

        record BuildClient(
                McpServerToolStates.DeployedService deployService,
                ToolDecoratorService.McpServerToolState toolState,
                NamedClientMcpTransport namedClientMcpTransport)
                implements ToolDecoratorEffect {
        }

        sealed interface AfterDeployHandleMcp extends ToolDecoratorEffect {
            @Builder
            record AfterSuccessfulRedeploy(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DecoratedMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState toolState) implements AfterDeployHandleMcp {
            }

            @Builder
            record AfterFailedDeployTryRollback(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DecoratedMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState remove,
                    DeployModels.DeployState deployState) implements AfterDeployHandleMcp {
            }

            @Builder
            record AfterRollbackSuccess(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DecoratedMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState remove,
                    DeployModels.DeployState deployState,
                    ToolDecoratorResult.RedeployResultWrapper rollbackResult)
                    implements AfterDeployHandleMcp {
            }

            @Builder
            record AfterRollbackFail(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DecoratedMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState remove,
                    DeployModels.DeployState deployState,
                    ToolDecoratorResult.RedeployResultWrapper rollbackResult)
                    implements AfterDeployHandleMcp {
            }
        }

    }

    public interface ToolDecoratorResult {

        record PrepareRollbackResult() implements ToolDecoratorResult {
        }

        record SetMcpClientResult(
                SetClients.DelegateMcpClient delegate) implements ToolDecoratorResult {
        }

        record UpdatedToolState(
                ToolDecorator.ToolDecoratorToolStateUpdate stateUpdate) implements ToolDecoratorResult {
        }

        record UpdatedToolMcp(
                List<ToolDecorator.McpServerToolStateChange> changes) implements ToolDecoratorResult {
        }

        @Builder(toBuilder = true)
        record SearchResultWrapper(
                ToolDecoratorService.McpServerToolState newToolState,
                List<ToolDecorator.McpServerToolStateChange> toolStateChanges,
                List<McpServerFeatures.SyncToolSpecification> added,
                String err) implements ToolDecoratorResult {
            public boolean didToolListChange() {
                return !toolStateChanges.isEmpty();
            }
        }

        @Builder(toBuilder = true)
        record RedeployResultWrapper(
                DeployModels.RedeployResult redeployResult,
                ToolDecoratorService.McpServerToolState newToolState,
                ToolModels.Redeploy redeploy,
                List<ToolDecorator.McpServerToolStateChange> toolStateChanges) implements ToolDecoratorResult {

            public boolean didToolListChange() {
                return Optional.ofNullable(redeployResult)
                        .flatMap(r -> Optional.ofNullable(r.rollbackState()))
                        .map(DeployModels.DeployState::didToolListChange)
                        .or(() -> {
                            return Optional.ofNullable(redeployResult)
                                    .flatMap(r -> Optional.ofNullable(r.deployState()))
                                    .map(DeployModels.DeployState::didToolListChange);
                        })
                        .orElse(false);
            }


            public boolean didRollback() {
                return Optional.ofNullable(redeployResult)
                        .flatMap(r -> Optional.ofNullable(r.rollbackState()))
                        .map(DeployModels.DeployState::didRollback)
                        .orElse(false);
            }

            public boolean didDeploy() {
                return Optional.ofNullable(redeployResult)
                        .flatMap(r -> Optional.ofNullable(r.deployState()))
                        .map(DeployModels.DeployState::didRedeploy)
                        .orElse(false);
            }

        }

        @Builder(toBuilder = true)
        record SetSyncClientResult(
                Set<String> tools,
                Set<String> toolsAdded,
                Set<String> toolsRemoved,
                String err,
                List<ToolCallbackProvider> providers,
                List<McpServerFeatures.SyncToolSpecification> toAddTools,
                Set<String> toRemoveTools,
                ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorResult {

            public boolean wasSuccessful() {
                return StringUtils.isBlank(err);
            }

            public List<ToolDecorator.McpServerToolStateChange> getToolStateChanges() {
                List<ToolDecorator.McpServerToolStateChange> changes = new ArrayList<>();
                StreamUtil.toStream(toAddTools).forEach(c -> changes.add(new ToolDecorator.McpServerToolStateChange.AddTool(c)));
                StreamUtil.toStream(toRemoveTools).forEach(c -> changes.add(new ToolDecorator.McpServerToolStateChange.RemoveTool(c)));
                return changes;
            }
        }

        @Builder
        record RedeployDescriptor(
                boolean isSuccess, int exitCode,
                Path binary, Path log,
                String err) implements ToolDecoratorResult {
        }
    }

    private final DeployService deployService;

    private final McpServerToolStates ts;

    private final DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    private final McpServerToolStates toolStates;

    private final RollbackFunction rollbackFunction;

    private final RedeployFunction redeployFunction;

    @Autowired
    List<ToolDecorator> toolDecorators;


    @Override
    public Free<ToolDecoratorEffect, ToolDecoratorResult> apply(ToolDecoratorEffect toolDecoratorEffect) {
        return switch (toolDecoratorEffect) {
            case ToolDecoratorEffect.PerformToolDecorators dec -> {
                throw new RuntimeException(".");
            }
            case ToolDecoratorEffect.AddManyMcpServerToolState addMcpServerToolState -> {
//                TODO: tool decorators should be effectful also
                yield this.toolStates.doOverWriteState(() -> {

                    record StateUpdate(String name,
                                       ToolDecoratorService.McpServerToolState toolStates,
                                       List<ToolDecorator.McpServerToolStateChange> toolStateChanges) {}

                    var toDecorate = addMcpServerToolState.stateUpdate
                                    .entrySet()
                                    .stream()
                                    .map(e -> {
                                        ToolDecorator.ToolDecoratorToolStateUpdate stateUpdate = e.getValue().stateUpdate;
                                        switch(stateUpdate) {
                                            case ToolDecorator.ToolDecoratorToolStateUpdate.AddToolToolStateUpdate addToolStateUpdate -> {
                                                return new StateUpdate(addToolStateUpdate.name(), addToolStateUpdate.toolStates(), addToolStateUpdate.toolStateChanges());
                                            }
                                            case ToolDecorator.ToolDecoratorToolStateUpdate.AddCallbacksToToolState addBeforeAfterToolCallbacks -> {
                                                return new StateUpdate(
                                                        addBeforeAfterToolCallbacks.name(),
                                                        addBeforeAfterToolCallbacks.toolStates(),
                                                        addBeforeAfterToolCallbacks.toolStateChanges());
                                            }
                                        }
                                    })
                            .toList();

                    var m = MapFunctions.CollectMap(toDecorate.stream().map(su -> Map.entry(su.name, su.toolStates)));
                    var c = toDecorate.stream().flatMap(su -> su.toolStateChanges.stream())
                            .collect(Collectors.toCollection(ArrayList::new));

                    var d = new ToolDecorator.ToolDecoratorState(m, c);

                    for (var t : toolDecorators) {
                        if (t.isEnabled())  {
                            try {
                                var next = t.decorate(d);
                                d = d.update(next);
                            } catch (Exception e) {
                                log.error("Error attempting to add.");
                            }
                        }
                    }

                    this.toolStates.addAllUpdates(d.newMcpServerState());

                    return Free.liftF(new ToolDecoratorEffect.UpdateMcpServerWithToolChanges(d.stateChanges()));
                });

            }
            case ToolDecoratorEffect.AddMcpServerToolState addMcpServerToolState -> {
                yield this.toolStates.doOverWriteState(() -> {
                    this.toolStates.addUpdateToolState(addMcpServerToolState.stateUpdate);
                    return Free.<ToolDecoratorEffect, ToolDecoratorResult>
                                    liftF(new ToolDecoratorEffect.UpdateMcpServerWithToolChanges(addMcpServerToolState.stateUpdate().toolStateChanges()))
                            .flatMap(s -> Free.pure(new ToolDecoratorResult.UpdatedToolState(addMcpServerToolState.stateUpdate)));
                });
            }
            case ToolDecoratorEffect.UpdateMcpServerWithToolChanges toolChanges -> {
                yield this.toolStates.doOverWriteState(() -> {
                    toolStates.executeToolStateChanges(toolChanges.toolStateChanges);
                    return Free.pure(new ToolDecoratorResult.UpdatedToolMcp(toolChanges.toolStateChanges));
                });
            }
            case ToolDecoratorEffect.SetErrorMcp setErrorMcp ->
                    this.toolStates.doOverReadState(() -> {
                        return Free.pure(ts.createSetClientErr(setErrorMcp.service, setErrorMcp.err, setErrorMcp.toolState));
                    });
            case ToolDecoratorEffect.SetMcpClientUpdateTools setMcpClient ->
                    this.toolStates.doOverReadState(() -> {
                        return toolStates.setMcpClientUpdateTools(setMcpClient.client, setMcpClient.service, setMcpClient.toolState)
                                .flatMap(Free::pure);
                    });
            case ToolDecoratorEffect.BuildClient buildClient -> {
                yield this.toolStates.doOverReadState(() -> {
                    Result<McpSyncClient, DynamicMcpToolCallbackProvider.McpError> client;
                    if (buildClient.namedClientMcpTransport != null) {
                        client = dynamicMcpToolCallbackProvider.buildClient(buildClient.deployService().clientId(), buildClient.namedClientMcpTransport);
                    } else {
                        client = dynamicMcpToolCallbackProvider.buildClient(buildClient.deployService().clientId());
                    }
                    if (client.isOk())
                        return Free.liftF(new ToolDecoratorEffect.SetMcpClientUpdateTools(client.unwrap(), buildClient.deployService, buildClient.toolState, buildClient.namedClientMcpTransport));
                    else
                        return Free.liftF(new ToolDecoratorEffect.SetErrorMcp(buildClient.deployService, client.unwrapError(), buildClient.toolState));
                });
            }
            case ToolDecoratorEffect.UpdatingExistingToolDecorator updatingExistingToolDecorator ->
                    this.toolStates.doOverReadState(() -> {
                        return Free.pure(toolStates.updateExisting(updatingExistingToolDecorator));
                    });
            case ToolDecoratorEffect.CreateNewToolDecorator createNewToolDecorator ->
                    this.toolStates.doOverReadState(() -> {
                        return Free.pure(toolStates.createNew(createNewToolDecorator));
                    });
            case ToolDecoratorEffect.SetClient s ->
                    Free.pure(toolStates.doSetMcpClient(s.m, s.deployService, s.mcpServerToolState));
            case ToolDecoratorEffect.KillClientAndRedeploy killClientAndRedeploy -> {
                yield this.toolStates.doOverWriteState(() -> {
                    return toolStates.killClientAndThenFree(killClientAndRedeploy.toolState, killClientAndRedeploy.redeploy.deployService(), () -> {
                        Free<ToolDecoratorEffect, ToolDecoratorResult> f = Free.<ToolDecoratorEffect, ToolDecoratorResult>liftF(new ToolDecoratorEffect.PrepareRollback(killClientAndRedeploy.redeployMcpServer))
                                .flatMap(prep -> {
                                    return Free.<ToolDecoratorEffect, ToolDecoratorResult.RedeployDescriptor>
                                            liftF(new ToolDecoratorEffect.PerformRedeploy(killClientAndRedeploy.redeployMcpServer));
                                })
                                .flatMap(dep -> {
                                    if (!dep.isSuccess()) {
                                        log.debug("Failed to perform toSearch {} - copying old artifact and restarting.", dep);
                                        return Free.<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper>liftF(new ToolDecoratorEffect.PerformRollback(killClientAndRedeploy.redeploy, killClientAndRedeploy.redeployMcpServer, dep, killClientAndRedeploy.toolState, DeployModels.DeployState.DEPLOY_FAIL))
                                                .flatMap(redep -> doHandleRedeployOrRollback(killClientAndRedeploy, dep, redep))
                                                .flatMap(Free::pure);
                                    } else {
                                        return Free.<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper>liftF(
                                                        ToolDecoratorEffect.AfterDeployHandleMcp.AfterSuccessfulRedeploy.builder()
                                                                .r(dep)
                                                                .redeploy(killClientAndRedeploy.redeploy)
                                                                .toolState(killClientAndRedeploy.toolState)
                                                                .build())
                                                .flatMap(redep -> doHandleRedeployOrRollback(killClientAndRedeploy, dep, redep))
                                                .flatMap(Free::pure);
                                    }
                                });

                        return f;
                    });
                });
            }
            case ToolDecoratorEffect.AfterDeployHandleMcp deployDescription ->
                    toolStates.doOverWriteState(() -> {
                        return deployService.apply(deployDescription)
                                .flatMap(Free::pure);
                    });
            case ToolDecoratorEffect.DoRedeploy doRedeploy ->
                    toolStates.doOverWriteState(() -> {
                        return Free.liftF(new ToolDecoratorEffect.KillClientAndRedeploy(doRedeploy.redeploy, doRedeploy.redeployMcpServer, doRedeploy.toolState));
                    });
            case ToolDecoratorEffect.PerformRedeploy performRedeploy ->
                    toolStates.doOverWriteState(() -> {
                        return Free.pure(redeployFunction.performRedeploy(performRedeploy.redeployMcpServer));
                    });
            case ToolDecoratorEffect.PrepareRollback prepareRollback ->
                    toolStates.doOverWriteState(() -> {
                        return Free.pure(rollbackFunction.prepareRollback(prepareRollback.d));
                    });
            case ToolDecoratorEffect.PerformRollback performRollback ->
                    toolStates.doOverWriteState(() -> {
                        return rollbackFunction.rollback(performRollback);
                    });
        };
    }

    private static @NotNull Free<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper> doHandleRedeployOrRollback(ToolDecoratorEffect.KillClientAndRedeploy killClientAndRedeploy, ToolDecoratorResult.RedeployDescriptor dep, ToolDecoratorResult.RedeployResultWrapper redep) {
        if (redep.redeployResult().deployState().didRedeploy()) {
            return Free.pure(redep);
        }

        return Free.liftF(new ToolDecoratorEffect.PerformRollback(killClientAndRedeploy.redeploy, killClientAndRedeploy.redeployMcpServer,
                dep, killClientAndRedeploy.toolState, redep.redeployResult.deployState()));
    }

}
