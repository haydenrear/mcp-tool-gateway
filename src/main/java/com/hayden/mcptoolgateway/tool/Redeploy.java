package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.mcptoolgateway.fn.RollbackFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class Redeploy {

    @Autowired
    RedeployFunction redeployFunction;
    @Autowired
    DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;
    @Autowired
    DeployService deploy;
    @Autowired
    RollbackFunction rollback;

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

                rollback.prepareRollback(d);

                var r = redeployFunction.performRedeploy(redeployMcpServer);

                if (!r.isSuccess()) {
                    log.debug("Failed to perform redeploy {} - copying old artifact and restarting.", r);
                    return rollback.rollback(redeploy, d, r, toolState);
                }

                return deploy.handleDidRedeployUpdateToolCallbackProviders(redeploy, r, toolState);
        });

        return res;
    }

}
