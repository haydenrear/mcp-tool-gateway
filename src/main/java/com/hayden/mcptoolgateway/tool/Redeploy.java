package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.mcptoolgateway.fn.RollbackFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

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
            ToolModels.Redeploy redeploy) {

        public boolean didToolListChange() {
            return Optional.ofNullable(redeployResult)
                    .flatMap(r -> Optional.ofNullable(r.rollbackState()))
                    .map(ToolDecoratorService.DeployState::didToolListChange)
                    .or(() -> {
                        return Optional.ofNullable(redeployResult)
                                .flatMap(r -> Optional.ofNullable(r.deployState()))
                                .map(ToolDecoratorService.DeployState::didToolListChange)    ;
                    })
                    .orElse(false);
        }


        public boolean didRollback() {
            return Optional.ofNullable(redeployResult)
                    .flatMap(r -> Optional.ofNullable(r.rollbackState()))
                    .map(ToolDecoratorService.DeployState::didRollback)
                    .orElse(false);
        }

        public boolean didDeploy() {
            return Optional.ofNullable(redeployResult)
                    .flatMap(r -> Optional.ofNullable(r.deployState()))
                    .map(ToolDecoratorService.DeployState::didRedeploy)
                    .orElse(false);
        }

    }

    RedeployResultWrapper doRedeploy(ToolModels.Redeploy redeploy,
                                     ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer,
                                     ToolDecoratorService.McpServerToolState toolState) {
        var res = this.dynamicMcpToolCallbackProvider.killClientAndThen(redeploy.deployService(), () -> {
                var d = this.toolGatewayConfigProperties.getDeployableMcpServers().get(redeploy.deployService());

                rollback.prepareRollback(d);

                var r = redeployFunction.performRedeploy(redeployMcpServer);

                if (!r.isSuccess()) {
                    log.debug("Failed to perform redeploy {} - copying old artifact and restarting.", r);
                    return rollback.rollback(redeploy, d, r, toolState, ToolDecoratorService.DeployState.DEPLOY_FAIL);
                }

                var didRedeploy = deploy.handleDeploy(
                        DeployService.DeployDescription.AfterSuccessfulRedeploy.builder()
                                .r(r)
                                .redeploy(redeploy)
                                .toolState(toolState)
                                .build());

                if (didRedeploy.redeployResult().deployState().didRedeploy()) {
                    return didRedeploy;
                }

                return rollback.rollback(redeploy, d, r, toolState, didRedeploy.redeployResult().deployState());
        });

        return res;
    }

}
