package com.hayden.mcptoolgateway.tool.deploy;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.deploy.fn.RollbackFunction;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Optional;

@Service
@Slf4j
public class Redeploy {

    @Autowired
    RedeployFunction redeployFunction;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;
    @Autowired
    DeployService deploy;
    @Autowired
    RollbackFunction rollback;
    @Autowired
    McpServerToolStates ts;

    @Builder(toBuilder = true)
    public record RedeployResultWrapper(
            DeployModels.RedeployResult redeployResult,
            ToolDecoratorService.McpServerToolState newToolState,
            ToolModels.Redeploy redeploy) {

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

    RedeployResultWrapper doRedeploy(ToolModels.Redeploy redeploy,
                                     ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer,
                                     ToolDecoratorService.McpServerToolState toolState) {


        var res = this.ts.killClientAndThen(toolState, redeploy.deployService(), () -> {
                var d = this.toolGatewayConfigProperties.getDeployableMcpServers().get(redeploy.deployService());

                rollback.prepareRollback(d);

                var r = redeployFunction.performRedeploy(redeployMcpServer);

                if (!r.isSuccess()) {
                    log.debug("Failed to perform redeploy {} - copying old artifact and restarting.", r);
                    return rollback.rollback(redeploy, d, r, toolState, DeployModels.DeployState.DEPLOY_FAIL);
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
