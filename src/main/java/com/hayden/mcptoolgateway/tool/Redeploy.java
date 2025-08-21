package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.stream.StreamUtil;
import io.micrometer.common.util.StringUtils;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.StaticToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hayden.mcptoolgateway.tool.ToolDecoratorService.REDEPLOY_MCP_SERVER;

@Service
@Slf4j
public class Redeploy {
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    SetClients setMcpClient;
    @Autowired
    McpSyncServerDelegate mcpSyncServer;
    @Autowired
    RedeployFunction redeployFunction;
    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;

    @Builder(toBuilder = true)
    public record RedeployResultWrapper(
            ToolDecoratorService.RedeployResult redeployResult,
            ToolDecoratorService.McpServerToolState newToolState,
            ToolModels.Redeploy redeploy) {}

    RedeployResultWrapper doRedeploy(ToolModels.Redeploy redeploy,
                                     ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer,
                                     ToolDecoratorService.McpServerToolState toolState) {
        var res = this.dynamicMcpToolCallbackProvider.killClientAndThen(redeploy.deployService(), () -> {
                var d = this.toolGatewayConfigProperties.getDeployableMcpServers().get(redeploy.deployService());

                boolean doRollback = prepareRollback(d);

                var r = redeployFunction.performRedeploy(redeployMcpServer);

                if (!r.isSuccess()) {
                    log.debug("Failed to perform redeploy {} - copying old artifact and restarting.", r);
                    if (doRollback) {
                        return rollback(redeploy, d, r, toolState);
                    }

                    return handleFailedRedeployNoRollback(redeploy, r, toolState);
                }

                return handleDidRedeployUpdateToolCallbackProviders(redeploy, r, toolState);
        });

        return res;
    }

    private RedeployResultWrapper rollback(ToolModels.Redeploy redeploy,
                                           ToolGatewayConfigProperties.DeployableMcpServer d,
                                           RedeployFunction.RedeployDescriptor r,
                                           ToolDecoratorService.McpServerToolState remove) {
        try {
            RedeployResultWrapper redeployResultWrapper = tryRollback(redeploy, d, r, remove);
            if (redeployResultWrapper.redeployResult.didRollback()) {
                var w = handleDidRedeployUpdateToolCallbackProviders(redeploy, r, remove);

                return w.toBuilder()
                        .redeployResult(w.redeployResult
                                .toBuilder()
                                .deployErr(performedRedeployResultRollback(redeploy))
                                .didRollback(true)
                                .build())
                        .build();
            } else {
                var w = handleFailedRedeployNoRollback(redeploy, r, remove);
                ToolDecoratorService.RedeployResult res = w.redeployResult();
                return w.toBuilder()
                        .redeployResult(res
                                .toBuilder()
                                .deployErr("Deploy err: %s - tried to rollback but failed with unknown error."
                                        .formatted(res.deployErr()))
                                .build())
                        .build();
            }
        } catch (IOException e) {
            log.error("Failed to copy MCP server artifact back from cache - failed to rollback to previous version.");
            var w = handleFailedRedeployNoRollback(redeploy, r, remove);
            var redeployResult = w.redeployResult;
            return w.toBuilder()
                    .redeployResult(redeployResult.toBuilder()
                            .deployErr("Deploy err: %s - tried to rollback but failed with err: %s"
                                    .formatted(redeployResult.deployErr(), e.getMessage()))
                            .build())
                    .build();
        }
    }

    private boolean prepareRollback(ToolGatewayConfigProperties.DeployableMcpServer d) {
        boolean doRollback = false;

        if (d.binary().toFile().exists()) {
            try {
                Files.copy(
                        d.binary(),
                        toolGatewayConfigProperties.getArtifactCache().resolve(d.binary().toFile().getName()),
                        StandardCopyOption.REPLACE_EXISTING);
                doRollback = true;
            } catch (IOException e) {
                log.error("Failed to copy MCP server artifact to cache.");
            }
        }
        return doRollback;
    }

    private RedeployResultWrapper tryRollback(ToolModels.Redeploy redeploy,
                                              ToolGatewayConfigProperties.DeployableMcpServer d,
                                              RedeployFunction.RedeployDescriptor r,
                                              ToolDecoratorService.McpServerToolState toolState) throws IOException {
        Files.copy(
                toolGatewayConfigProperties.getArtifactCache().resolve(d.binary().toFile().getName()),
                d.binary(),
                StandardCopyOption.REPLACE_EXISTING);

        var toSet = setMcpClient.setMcpClient(redeploy.deployService(), toolState);

        if (toSet.wasSuccessful() && setMcpClient.clientInitialized(redeploy.deployService())) {
            return RedeployResultWrapper
                    .builder()
                    .redeploy(redeploy)
                    .redeployResult(ToolDecoratorService.RedeployResult.builder()
                            .tools(toSet.tools())
                            .deployErr(r.err())
                            .didRollback(true)
                            .build())
                    .build();
        }

        return RedeployResultWrapper
                .builder()
                .redeploy(redeploy)
                .redeployResult(ToolDecoratorService.RedeployResult.builder()
                        .deployErr(r.err())
                        .didRollback(false)
                        .mcpConnectErr(toSet.err())
                        .build())
                .build();
    }

    private RedeployResultWrapper handleFailedRedeployNoRollback(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r, ToolDecoratorService.McpServerToolState remove) {
        var tc = setMcpClient.createSetClientErr(
                redeploy.deployService(),
                new DynamicMcpToolCallbackProvider.McpError(r.err()),
                remove);

        return new RedeployResultWrapper(
                ToolDecoratorService.RedeployResult.builder()
                        .deployErr(redeployFailedErr(redeploy))
                        .toolsRemoved(tc.toolsRemoved())
                        .toolsAdded(tc.toolsAdded())
                        .tools(tc.tools())
                        .build(),
                ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(tc.providers())
                        .lastDeploy(r)
                        .build(),
                redeploy);
    }

    private @NotNull RedeployResultWrapper handleDidRedeployUpdateToolCallbackProviders(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r, ToolDecoratorService.McpServerToolState remove) {
        ToolDecoratorService.SetSyncClientResult setSyncClientResult = setMcpClient.setMcpClient(
                redeploy.deployService(),
                remove);

        return new RedeployResultWrapper(handleConnectMcpError(redeploy, r, setSyncClientResult),
                ToolDecoratorService.McpServerToolState.builder()
                        .toolCallbackProviders(setSyncClientResult.providers())
                        .lastDeploy(setSyncClientResult.lastDeploy())
                        .build(),
                redeploy);
    }


    private @NotNull ToolDecoratorService.RedeployResult handleConnectMcpError(ToolModels.Redeploy redeploy,
                                                                               RedeployFunction.RedeployDescriptor r,
                                                                               ToolDecoratorService.SetSyncClientResult setSyncClientResult) {
        if (setMcpClient.noClientKey(redeploy.deployService())) {
            return toRedeployRes(r, setSyncClientResult, "MCP client was not found for %s"
                    .formatted(redeploy.deployService()));
        } else if (setMcpClient.noMcpClient(redeploy.deployService())) {
            var err = setMcpClient.getError(redeploy.deployService());
            if (err != null) {
                return toRedeployRes(r, setSyncClientResult,
                        "Error connecting to MCP client for %s after redeploy: %s".formatted(redeploy, err));
            } else {
                return toRedeployRes(r, setSyncClientResult,
                        "Unknown connecting to MCP client for %s after redeploy".formatted(redeploy));
            }
        } else {
            return toRedeployRes(r, setSyncClientResult,
                    "Performed redeploy for %s: %s.".formatted(redeploy.deployService(), r));
        }
    }

    private static ToolDecoratorService.RedeployResult toRedeployRes(RedeployFunction.RedeployDescriptor r, ToolDecoratorService.SetSyncClientResult setSyncClientResult,
                                                                     String mcpConnectErr) {
        return ToolDecoratorService.RedeployResult.builder()
                .mcpConnectErr(mcpConnectErr)
                .tools(setSyncClientResult.tools())
                .toolsRemoved(setSyncClientResult.toolsRemoved())
                .toolsAdded(setSyncClientResult.toolsAdded())
                .deployErr(r.err())
                .build();
    }




    private static @NotNull String redeployFailedErr(ToolModels.Redeploy i) {
        return "Error performing redeploy of %s.".formatted(i.deployService());
    }

    private static @NotNull String performedRedeployResultRollback(ToolModels.Redeploy i) {
        return "Tried to redeploy %s, redeploy failed and rolled back to previous version.".formatted(i.deployService());
    }

}
