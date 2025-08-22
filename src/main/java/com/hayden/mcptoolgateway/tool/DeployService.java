package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DeployService {

    @Autowired
    SetClients setMcpClient;

    public Redeploy.RedeployResultWrapper handleFailedRedeployNoRollback(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r, ToolDecoratorService.McpServerToolState remove) {
        var tc = setMcpClient.createSetClientErr(
                redeploy.deployService(),
                new DynamicMcpToolCallbackProvider.McpError(r.err()),
                remove);

        return new Redeploy.RedeployResultWrapper(
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

    public @NotNull Redeploy.RedeployResultWrapper handleDidRedeployUpdateToolCallbackProviders(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r, ToolDecoratorService.McpServerToolState remove) {
        ToolDecoratorService.SetSyncClientResult setSyncClientResult = setMcpClient.setMcpClient(
                redeploy.deployService(),
                remove);

        return new Redeploy.RedeployResultWrapper(handleConnectMcpError(redeploy, r, setSyncClientResult),
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

    public static @NotNull String performedRedeployResultRollback(ToolModels.Redeploy i,
                                                                  RedeployFunction.RedeployDescriptor redeployResult) {
        return "Tried to redeploy %s, redeploy failed with err %s, and rolled back to previous version. Please see log and err."
                .formatted(i.deployService(), redeployResult.err());
    }


}
