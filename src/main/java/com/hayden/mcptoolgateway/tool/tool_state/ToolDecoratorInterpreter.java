package com.hayden.mcptoolgateway.tool.tool_state;

import com.google.common.util.concurrent.Striped;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.deploy.DeployModels;
import com.hayden.mcptoolgateway.tool.deploy.DeployService;
import com.hayden.mcptoolgateway.tool.deploy.Redeploy;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.deploy.fn.RollbackFunction;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;

@Component
@RequiredArgsConstructor
@Slf4j
public class ToolDecoratorInterpreter
        implements Interpreter<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> {

    public sealed interface ToolDecoratorEffect extends Effect {

        record PrepareRollback(ToolGatewayConfigProperties.DeployableMcpServer d) implements ToolDecoratorEffect {}

        record PerformRedeploy(ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer) implements ToolDecoratorEffect {}

        record PerformRollback(ToolModels.Redeploy redeploy,
                               ToolGatewayConfigProperties.DeployableMcpServer d,
                               ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor r,
                               ToolDecoratorService.McpServerToolState remove,
                               DeployModels.DeployState deployState) implements ToolDecoratorEffect {}

        record UpdatingExistingToolDecorator(
                SetClients.DelegateMcpClient m,
                ToolDecoratorService.McpServerToolState removedState,
                McpServerToolStates.DeployedService deployService) implements ToolDecoratorEffect {}

        record CreateNewToolDecorator(
                SetClients.DelegateMcpClient mcpClient,
                McpServerToolStates.DeployedService deployService) implements ToolDecoratorEffect {}

        record SetErrorMcp(
                McpServerToolStates.DeployedService service, DynamicMcpToolCallbackProvider.McpError err,
                ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorEffect {}


       record SetClient(McpSyncClient m, McpServerToolStates.DeployedService deployService, ToolDecoratorService.McpServerToolState mcpServerToolState) implements ToolDecoratorEffect  {}

        record SetMcpClientUpdateTools(McpSyncClient client, McpServerToolStates.DeployedService service,
                                       ToolDecoratorService.McpServerToolState toolState, NamedClientMcpTransport namedClientMcpTransport) implements ToolDecoratorEffect {}

        record AddMcpServerToolState(
                ToolDecorator.ToolDecoratorToolStateUpdate stateUpdate) implements ToolDecoratorEffect {}

        record DoRedeploy(ToolModels.Redeploy redeploy,
                          ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer,
                          ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorEffect {}

        record KillClientAndRedeploy(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer,
                ToolDecoratorService.McpServerToolState toolState) implements ToolDecoratorEffect { }

        record BuildClient(McpServerToolStates.DeployedService deployService, ToolDecoratorService.McpServerToolState toolState,
                           NamedClientMcpTransport namedClientMcpTransport)
                implements ToolDecoratorEffect {}

        sealed interface AfterDeployHandleMcp extends ToolDecoratorEffect {
            @Builder
            record AfterSuccessfulRedeploy(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DeployableMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState toolState) implements AfterDeployHandleMcp {
            }

            @Builder
            record AfterFailedDeployTryRollback(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DeployableMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState remove,
                    DeployModels.DeployState deployState) implements AfterDeployHandleMcp {
            }

            @Builder
            record AfterRollbackSuccess(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DeployableMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState remove,
                    DeployModels.DeployState deployState,
                    ToolDecoratorResult.RedeployResultWrapper rollbackResult)
                    implements AfterDeployHandleMcp {
            }

            @Builder
            record AfterRollbackFail(
                    ToolModels.Redeploy redeploy,
                    ToolGatewayConfigProperties.DeployableMcpServer d,
                    ToolDecoratorResult.RedeployDescriptor r,
                    ToolDecoratorService.McpServerToolState remove,
                    DeployModels.DeployState deployState,
                    ToolDecoratorResult.RedeployResultWrapper rollbackResult)
                    implements AfterDeployHandleMcp {
            }
        }

    }

    public interface ToolDecoratorResult {

        record PrepareRollbackResult() implements ToolDecoratorResult {}

        record SetMcpClientResult(
                SetClients.DelegateMcpClient delegate) implements ToolDecoratorResult {}

        record UpdatedToolState(
                ToolDecorator.ToolDecoratorToolStateUpdate stateUpdate) implements ToolDecoratorResult {}

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
                                    .map(DeployModels.DeployState::didToolListChange)    ;
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
                Set<String> toRemoveTools) implements ToolDecoratorResult {

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
        record RedeployDescriptor(boolean isSuccess, int exitCode, Path binary, Path log, String err) implements ToolDecoratorResult {}
    }

    private final DeployService deployService;

    @Autowired @Lazy
    private McpServerToolStates ts;

    private final DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;

    private final McpServerToolStates toolStates;

    private final RollbackFunction rollbackFunction;

    private final RedeployFunction redeployFunction;



    @Override
    public Free<ToolDecoratorEffect, ToolDecoratorResult> apply(ToolDecoratorEffect toolDecoratorEffect) {
        return switch(toolDecoratorEffect) {
            case ToolDecoratorEffect.AddMcpServerToolState addMcpServerToolState -> {
                    this.toolStates.addUpdateToolState(addMcpServerToolState.stateUpdate);
                    yield Free.pure(new ToolDecoratorResult.UpdatedToolState(addMcpServerToolState.stateUpdate));
            }
            case ToolDecoratorEffect.SetErrorMcp setErrorMcp ->
                    Free.pure(ts.createSetClientErr(setErrorMcp.service, setErrorMcp.err, setErrorMcp.toolState));
            case ToolDecoratorEffect.SetMcpClientUpdateTools setMcpClient ->
                    toolStates.setMcpClientUpdateTools(setMcpClient.client, setMcpClient.service, setMcpClient.toolState);
            case ToolDecoratorEffect.BuildClient buildClient -> {
                Result<McpSyncClient, DynamicMcpToolCallbackProvider.McpError> client;
                if (buildClient.namedClientMcpTransport != null) {
                    client = dynamicMcpToolCallbackProvider.buildClient(buildClient.deployService().clientId(),  buildClient.namedClientMcpTransport);
                } else {
                    client = dynamicMcpToolCallbackProvider.buildClient(buildClient.deployService().clientId());
                }
                if (client.isOk())
                    yield Free.liftF(new ToolDecoratorEffect.SetMcpClientUpdateTools(client.unwrap(), buildClient.deployService, buildClient.toolState,
                            buildClient.namedClientMcpTransport));
                else
                    yield Free.liftF(new ToolDecoratorEffect.SetErrorMcp(buildClient.deployService, client.unwrapError(), buildClient.toolState));
            }
            case ToolDecoratorEffect.UpdatingExistingToolDecorator updatingExistingToolDecorator ->
                    Free.pure(toolStates.updateExisting(updatingExistingToolDecorator));
            case ToolDecoratorEffect.CreateNewToolDecorator createNewToolDecorator ->
                    Free.pure(toolStates.createNew(createNewToolDecorator));
            case ToolDecoratorEffect.SetClient s ->
                    Free.pure(toolStates.doSetMcpClient(s.m, s.deployService, s.mcpServerToolState));
            case ToolDecoratorEffect.KillClientAndRedeploy killClientAndRedeploy -> {
                yield toolStates.killClientAndThenFree(killClientAndRedeploy.toolState, killClientAndRedeploy.redeploy.deployService(), () -> {
                    Free<ToolDecoratorEffect, ToolDecoratorResult> f = Free.<ToolDecoratorEffect, ToolDecoratorResult>liftF(new ToolDecoratorEffect.PrepareRollback(killClientAndRedeploy.redeployMcpServer))
                            .flatMap(prep -> {
                                return Free.<ToolDecoratorEffect, ToolDecoratorResult.RedeployDescriptor>
                                        liftF(new ToolDecoratorEffect.PerformRedeploy(killClientAndRedeploy.redeployMcpServer));
                            })
                            .flatMap(dep -> {
                                if (!dep.isSuccess()) {
                                    log.debug("Failed to perform redeploy {} - copying old artifact and restarting.", dep);
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
            }
            case ToolDecoratorEffect.AfterDeployHandleMcp deployDescription ->
                    deployService.apply(deployDescription);
            case ToolDecoratorEffect.DoRedeploy doRedeploy ->
                    Free.liftF(new ToolDecoratorEffect.KillClientAndRedeploy(doRedeploy.redeploy, doRedeploy.redeployMcpServer, doRedeploy.toolState));
            case ToolDecoratorEffect.PerformRedeploy performRedeploy ->
                    Free.pure(redeployFunction.performRedeploy(performRedeploy.redeployMcpServer));
            case ToolDecoratorEffect.PrepareRollback prepareRollback ->
                    Free.pure(rollbackFunction.prepareRollback(prepareRollback.d));
            case ToolDecoratorEffect.PerformRollback performRollback ->
                    rollbackFunction.rollback(performRollback);
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
