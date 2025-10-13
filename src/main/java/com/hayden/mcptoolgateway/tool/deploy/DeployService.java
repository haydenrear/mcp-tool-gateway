package com.hayden.mcptoolgateway.tool.deploy;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter.*;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter.ToolDecoratorEffect.*;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter.ToolDecoratorResult.*;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.free.Free;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class DeployService {

    @Autowired
    @Lazy
    McpServerToolStates ts;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;


    public Free<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper> apply(AfterDeployHandleMcp deployDescription) {
        return switch (deployDescription) {
            case AfterDeployHandleMcp.AfterFailedDeployTryRollback afterFailedDeployTryRollback ->
                    handleDeploy(afterFailedDeployTryRollback)
                            .flatMap(Free::pure);
            case AfterDeployHandleMcp.AfterRollbackFail afterRollbackFail ->
                    handleDeploy(afterRollbackFail)
                            .flatMap(Free::pure);
            case AfterDeployHandleMcp.AfterRollbackSuccess afterRollbackSuccess ->
                    handleDeploy(afterRollbackSuccess)
                            .flatMap(Free::pure);
            case AfterDeployHandleMcp.AfterSuccessfulRedeploy afterSuccessfulRedeploy ->
                    handleDeploy(afterSuccessfulRedeploy)
                            .flatMap(Free::pure);
        };
    }

    public Free<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper> handleDeploy(AfterDeployHandleMcp.AfterRollbackFail in) {
        DeployModels.DeployState rollbackState = Optional.ofNullable(in.rollbackResult())
                .flatMap(wr -> Optional.of(wr.redeployResult()))
                .flatMap(wr -> Optional.ofNullable(wr.rollbackState()))
                .orElse(DeployModels.DeployState.DEPLOY_FAIL);
        return handleFailedRedeployRollbackFail(in.redeploy(), in.r(), in.remove(), in.deployState(), rollbackState);
    }

    public Free<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper> handleDeploy(AfterDeployHandleMcp.AfterRollbackSuccess in) {
        return handleConnectAfterRollback(in.redeploy(), in.r(), in.remove(), in.deployState());
    }

    public Free<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper> handleDeploy(AfterDeployHandleMcp.AfterSuccessfulRedeploy in) {
        return handleConnectAfterSuccessfulRedeploy(in.redeploy(), in.r(), in.toolState());
    }

    public Free<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper> handleDeploy(AfterDeployHandleMcp.AfterFailedDeployTryRollback in) {
        return tryRollback(in.redeploy(), in.d(), in.r(), in.remove(), in.deployState());
    }

    private Free<ToolDecoratorEffect, ToolDecoratorResult.RedeployResultWrapper> tryRollback(ToolModels.Redeploy redeploy,
                                                                                             ToolGatewayConfigProperties.DeployableMcpServer d,
                                                                                             RedeployDescriptor r,
                                                                                             ToolDecoratorService.McpServerToolState toolState,
                                                                                             DeployModels.DeployState deployState) {
        if (!toolGatewayConfigProperties.getArtifactCache().resolve(d.copyToArtifactPath().toFile().getName()).toFile().exists()) {
            return doTryRollbackInner(redeploy, r, toolState, deployState);
        }

        try {
            Files.copy(
                    toolGatewayConfigProperties.getArtifactCache().resolve(d.copyToArtifactPath().toFile().getName()),
                    d.copyToArtifactPath(),
                    StandardCopyOption.REPLACE_EXISTING);

            return doTryRollbackInner(redeploy, r, toolState, deployState);
        } catch (IOException e) {
            log.error("Failed to copy MCP server artifact to cache.");
            return Free.pure(
                    new ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper(
                            DeployModels.RedeployResult
                                    .builder()
                                    .rollbackState(DeployModels.DeployState.ROLLBACK_FAIL)
                                    .rollbackErr("Failed to copy MCP server artifact to cache, %s."
                                            .formatted(e.getMessage()))
                                    .deployState(deployState)
                                    .build(),
                            toolState,
                            redeploy,
                            new ArrayList<>()
                    ));
        }
    }

    private Free<ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper> doTryRollbackInner(ToolModels.Redeploy redeploy,
                                                                                                                             RedeployDescriptor r,
                                                                                                                             ToolDecoratorService.McpServerToolState toolState,
                                                                                                                             DeployModels.DeployState deployState) {

        return ts.setMcpClientUpdateToolState(redeploy.deployService(), toolState)
                .flatMap(toSet -> {
                    if (toSet.wasSuccessful() && ts.clientInitialized(redeploy.deployService())) {
                        return Free.pure(
                                ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper
                                        .builder()
                                        .redeploy(redeploy)
                                        .redeployResult(
                                                DeployModels.RedeployResult
                                                        .builder()
                                                        .tools(toSet.tools())
                                                        .deployErr(r.err())
                                                        .deployLog(r.log())
                                                        .deployState(deployState)
                                                        .rollbackState(DeployModels.DeployState.ROLLBACK_SUCCESSFUL)
                                                        .build())
                                        .toolStateChanges(toSet.getToolStateChanges())
                                        .build()
                        );

                    }
                    return Free.pure(
                            ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper
                                    .builder()
                                    .redeploy(redeploy)
                                    .toolStateChanges(toSet.getToolStateChanges())
                                    .redeployResult(
                                            DeployModels.RedeployResult
                                                    .builder()
                                                    .deployErr(r.err())
                                                    .deployLog(r.log())
                                                    .rollbackState(DeployModels.DeployState.ROLLBACK_FAIL_NO_CONNECT_MCP)
                                                    .deployState(deployState)
                                                    .mcpConnectErr(toSet.err())
                                                    .build())
                                    .build());
                });


    }

    private Free<ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper> handleFailedRedeployRollbackFail(ToolModels.Redeploy redeploy,
                                                                                                                     RedeployDescriptor r,
                                                                                                                     ToolDecoratorService.McpServerToolState remove,
                                                                                                                     DeployModels.DeployState deployState,
                                                                                                                     DeployModels.DeployState rollbackState) {

        return Free.<ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.SetSyncClientResult>liftF(
                        new SetErrorMcp(new McpServerToolStates.DeployedService(redeploy.deployService(), ToolDecoratorService.SYSTEM_ID),
                                new DynamicMcpToolCallbackProvider.McpError(r.err()), remove))
                .flatMap(tc -> Free.pure(new RedeployResultWrapper(
                        DeployModels.RedeployResult.builder()
                                .deployErr(redeployFailedErr(redeploy, r))
                                .toolsRemoved(tc.toolsRemoved())
                                .deployLog(r.log())
                                .tools(tc.tools())
                                .rollbackState(rollbackState)
                                .deployErr("Deploy err: %s - tried to rollback but failed with unknown error.".formatted(r.err()))
                                .deployState(deployState)
                                .build(),
                        ToolDecoratorService.McpServerToolState.builder()
                                .toolCallbackProviders(tc.providers())
                                .lastDeploy(r)
                                .build(),
                        redeploy,
                        tc.getToolStateChanges()
                )));

    }

    public @NotNull Free<ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper> handleConnectAfterSuccessfulRedeploy(ToolModels.Redeploy redeploy, RedeployDescriptor r,
                                                                                                                                                       ToolDecoratorService.McpServerToolState remove) {
        return handleDidRedeployUpdateToolCallbackProviders(
                redeploy, r, remove,
                DeployModels.DeployState.DEPLOY_FAIL_NO_CONNECT_MCP,
                DeployModels.DeployState.DEPLOY_SUCCESSFUL,
                null, null);
    }

    public @NotNull Free<ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper> handleConnectAfterRollback(ToolModels.Redeploy redeploy,
                                                                                                                                             RedeployDescriptor r,
                                                                                                                                             ToolDecoratorService.McpServerToolState remove,
                                                                                                                                             DeployModels.DeployState deployState) {

        return handleDidRedeployUpdateToolCallbackProviders(
                redeploy,
                r,
                remove,
                deployState,
                deployState,
                DeployModels.DeployState.ROLLBACK_FAIL_NO_CONNECT_MCP,
                DeployModels.DeployState.ROLLBACK_SUCCESSFUL
        )
                .flatMap(w -> {
                    return Free.pure(
                            w.toBuilder()
                                    .redeployResult(
                                            w.redeployResult()
                                                    .toBuilder()
                                                    .deployLog(r.log())
                                                    .deployErr(performedRedeployResultRollback(redeploy, r))
                                                    .build())
                                    .build());
                });

    }

    private @NotNull Free<ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper> handleDidRedeployUpdateToolCallbackProviders(
            ToolModels.Redeploy redeploy,
            RedeployDescriptor r,
            ToolDecoratorService.McpServerToolState remove,
            DeployModels.DeployState failDeployState,
            DeployModels.DeployState successDeployState,
            DeployModels.DeployState failRollbackState,
            DeployModels.DeployState successRollbackState) {
        return Free.<ToolDecoratorEffect, SetSyncClientResult>liftF(new BuildClient(new McpServerToolStates.DeployedService(redeploy.deployService(), ToolDecoratorService.SYSTEM_ID), remove, null))
                .flatMap(setSyncClientResult -> {
                    var found = handleConnectMcpError(redeploy)
                            .map(err -> new ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper(
                                    DeployModels.RedeployResult.builder()
                                            .mcpConnectErr(err)
                                            .deployLog(r.log())
                                            .deployState(failDeployState)
                                            .rollbackState(failRollbackState)
                                            .tools(setSyncClientResult.tools())
                                            .toolsRemoved(setSyncClientResult.toolsRemoved())
                                            .toolsAdded(setSyncClientResult.toolsAdded())
                                            .deployErr(r.err())
                                            .build(),
                                    ToolDecoratorService.McpServerToolState.builder()
                                            .toolCallbackProviders(setSyncClientResult.providers())
                                            .lastDeploy(r)
                                            .build(),
                                    redeploy,
                                    setSyncClientResult.getToolStateChanges()
                            ))
                            .orElseGet(() -> new ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper(
                                    DeployModels.RedeployResult.builder()
                                            .deployMessage("Performed redeploy for %s: %s.".formatted(redeploy.deployService(), r))
                                            .deployLog(r.log())
                                            .deployState(successDeployState)
                                            .rollbackState(successRollbackState)
                                            .tools(setSyncClientResult.tools())
                                            .toolsRemoved(setSyncClientResult.toolsRemoved())
                                            .toolsAdded(setSyncClientResult.toolsAdded())
                                            .deployErr(r.err())
                                            .build(),
                                    ToolDecoratorService.McpServerToolState.builder()
                                            .toolCallbackProviders(setSyncClientResult.providers())
                                            .lastDeploy(r)
                                            .build(),
                                    redeploy,
                                    setSyncClientResult.getToolStateChanges())
                            );

                    return Free.pure(found);
                });
    }


    private @NotNull Optional<String> handleConnectMcpError(ToolModels.Redeploy redeploy) {
        if (ts.noClientKey(redeploy.deployService())) {
            return Optional.of("MCP client was not found for %s".formatted(redeploy.deployService()));
        } else if (ts.noMcpClient(redeploy.deployService())) {
            var err = ts.getError(redeploy.deployService());
            if (err != null) {
                return Optional.of("Error connecting to MCP client for %s after redeploy: %s".formatted(redeploy, err));
            } else {
                return Optional.of("Unknown connecting to MCP client for %s after redeploy".formatted(redeploy));
            }
        } else if (ts.clientExistsNotInitialized(redeploy.deployService())) {
            return Optional.of("Unknown connecting to MCP client for %s after redeploy - client was not initialized.".formatted(redeploy));
        } else {
            return Optional.empty();
        }
    }

    private static @NotNull String redeployFailedErr(ToolModels.Redeploy i, RedeployDescriptor r) {
        return "Error performing redeploy of %s with error:\n\n%s.".formatted(i.deployService(), r.err());
    }

    public static @NotNull String performedRedeployResultRollback(ToolModels.Redeploy i,
                                                                  RedeployDescriptor redeployResult) {
        return "Tried to redeploy %s, redeploy failed with err %s, and rolled back to previous version. Please see log and err."
                .formatted(i.deployService(), redeployResult.err());
    }


}
