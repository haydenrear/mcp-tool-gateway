package com.hayden.mcptoolgateway.tool.deploy;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Component
@Slf4j
public class DeployService {

    @Autowired
    McpServerToolStates ts;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;

    public sealed interface DeployDescription {
        @Builder
        record AfterSuccessfulRedeploy(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DeployableMcpServer d,
                RedeployFunction.RedeployDescriptor r,
                ToolDecoratorService.McpServerToolState toolState) implements DeployDescription {
        }

        @Builder
        record AfterFailedDeployTryRollback(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DeployableMcpServer d,
                RedeployFunction.RedeployDescriptor r,
                ToolDecoratorService.McpServerToolState remove,
                DeployModels.DeployState deployState) implements DeployDescription {
        }

        @Builder
        record AfterRollbackSuccess(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DeployableMcpServer d,
                RedeployFunction.RedeployDescriptor r,
                ToolDecoratorService.McpServerToolState remove,
                DeployModels.DeployState deployState,
                Redeploy.RedeployResultWrapper rollbackResult)
                implements DeployDescription {
        }

        @Builder
        record AfterRollbackFail(
                ToolModels.Redeploy redeploy,
                ToolGatewayConfigProperties.DeployableMcpServer d,
                RedeployFunction.RedeployDescriptor r,
                ToolDecoratorService.McpServerToolState remove,
                DeployModels.DeployState deployState,
                Redeploy.RedeployResultWrapper rollbackResult)
                implements DeployDescription {
        }
    }

    public Redeploy.RedeployResultWrapper handleDeploy(DeployDescription.AfterRollbackFail in) {
        DeployModels.DeployState rollbackState = Optional.ofNullable(in.rollbackResult)
                .flatMap(wr -> Optional.of(wr.redeployResult()))
                .flatMap(wr -> Optional.ofNullable(wr.rollbackState()))
                .orElse(DeployModels.DeployState.DEPLOY_FAIL);
        return handleFailedRedeployRollbackFail(in.redeploy, in.r, in.remove, in.deployState, rollbackState);
    }

    public Redeploy.RedeployResultWrapper handleDeploy(DeployDescription.AfterRollbackSuccess in) {
        return handleConnectAfterRollback(in.redeploy, in.r, in.remove, in.deployState);
    }

    public Redeploy.RedeployResultWrapper handleDeploy(DeployDescription.AfterSuccessfulRedeploy in) {
        return handleConnectAfterSuccessfulRedeploy(in.redeploy, in.r, in.toolState);
    }

    public Redeploy.RedeployResultWrapper handleDeploy(DeployDescription.AfterFailedDeployTryRollback in) {
        return tryRollback(in.redeploy, in.d, in.r, in.remove, in.deployState);
    }

    private Redeploy.RedeployResultWrapper tryRollback(ToolModels.Redeploy redeploy,
                                                       ToolGatewayConfigProperties.DeployableMcpServer d,
                                                       RedeployFunction.RedeployDescriptor r,
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
            return new Redeploy.RedeployResultWrapper(
                    DeployModels.RedeployResult
                            .builder()
                            .rollbackState(DeployModels.DeployState.ROLLBACK_FAIL)
                            .rollbackErr("Failed to copy MCP server artifact to cache, %s."
                                    .formatted(e.getMessage()))
                            .deployState(deployState)
                            .build(),
                    toolState,
                    redeploy
            );
        }
    }

    private Redeploy.RedeployResultWrapper doTryRollbackInner(ToolModels.Redeploy redeploy,
                                                              RedeployFunction.RedeployDescriptor r,
                                                              ToolDecoratorService.McpServerToolState toolState,
                                                              DeployModels.DeployState deployState) {
        var toSet = ts.setMcpClient(redeploy.deployService(), toolState);

        if (toSet.wasSuccessful() && ts.clientInitialized(redeploy.deployService())) {
            return Redeploy.RedeployResultWrapper
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
                    .build();
        }

        return Redeploy.RedeployResultWrapper
                .builder()
                .redeploy(redeploy)
                .redeployResult(
                        DeployModels.RedeployResult
                                .builder()
                                .deployErr(r.err())
                                .deployLog(r.log())
                                .rollbackState(DeployModels.DeployState.ROLLBACK_FAIL_NO_CONNECT_MCP)
                                .deployState(deployState)
                                .mcpConnectErr(toSet.err())
                                .build())
                .build();
    }

    private Redeploy.RedeployResultWrapper handleFailedRedeployRollbackFail(ToolModels.Redeploy redeploy,
                                                                            RedeployFunction.RedeployDescriptor r,
                                                                            ToolDecoratorService.McpServerToolState remove,
                                                                            DeployModels.DeployState deployState,
                                                                            DeployModels.DeployState rollbackState) {
        var tc = ts.createSetClientErr(
                redeploy.deployService(), new DynamicMcpToolCallbackProvider.McpError(r.err()), remove);

        return new Redeploy.RedeployResultWrapper(
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
                redeploy);
    }

    public @NotNull Redeploy.RedeployResultWrapper handleConnectAfterSuccessfulRedeploy(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r,
                                                                                        ToolDecoratorService.McpServerToolState remove) {
        return handleDidRedeployUpdateToolCallbackProviders(
                redeploy, r, remove,
                DeployModels.DeployState.DEPLOY_FAIL_NO_CONNECT_MCP,
                DeployModels.DeployState.DEPLOY_SUCCESSFUL,
                null, null);
    }

    public @NotNull Redeploy.RedeployResultWrapper handleConnectAfterRollback(ToolModels.Redeploy redeploy,
                                                                              RedeployFunction.RedeployDescriptor r,
                                                                              ToolDecoratorService.McpServerToolState remove,
                                                                              DeployModels.DeployState deployState) {

        var w = handleDidRedeployUpdateToolCallbackProviders(
                redeploy, r, remove,
                deployState, deployState,
                DeployModels.DeployState.ROLLBACK_FAIL_NO_CONNECT_MCP,
                DeployModels.DeployState.ROLLBACK_SUCCESSFUL);

        return w.toBuilder()
                .redeployResult(
                        w.redeployResult()
                                .toBuilder()
                                .deployLog(r.log())
                                .deployErr(performedRedeployResultRollback(redeploy, r))
                                .build())
                .build();
    }

    private @NotNull Redeploy.RedeployResultWrapper handleDidRedeployUpdateToolCallbackProviders(ToolModels.Redeploy redeploy,
                                                                                                 RedeployFunction.RedeployDescriptor r,
                                                                                                 ToolDecoratorService.McpServerToolState remove,
                                                                                                 DeployModels.DeployState failDeployState,
                                                                                                 DeployModels.DeployState successDeployState,
                                                                                                 DeployModels.DeployState failRollbackState,
                                                                                                 DeployModels.DeployState successRollbackState) {
        ToolDecoratorService.SetSyncClientResult setSyncClientResult = ts.setMcpClient(redeploy.deployService(), remove);
        return handleConnectMcpError(redeploy)
                .map(err -> new Redeploy.RedeployResultWrapper(
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
                        redeploy))
                .orElseGet(() -> new Redeploy.RedeployResultWrapper(
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
                        redeploy));
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

    private static @NotNull String redeployFailedErr(ToolModels.Redeploy i, RedeployFunction.RedeployDescriptor r) {
        return "Error performing redeploy of %s with error:\n\n%s.".formatted(i.deployService(), r.err());
    }

    public static @NotNull String performedRedeployResultRollback(ToolModels.Redeploy i,
                                                                  RedeployFunction.RedeployDescriptor redeployResult) {
        return "Tried to redeploy %s, redeploy failed with err %s, and rolled back to previous version. Please see log and err."
                .formatted(i.deployService(), redeployResult.err());
    }


}
