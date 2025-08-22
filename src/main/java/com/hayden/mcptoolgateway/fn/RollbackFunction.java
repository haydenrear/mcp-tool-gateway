package com.hayden.mcptoolgateway.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static com.hayden.mcptoolgateway.tool.DeployService.performedRedeployResultRollback;

@Component
@Slf4j
public class RollbackFunction {

    @Autowired
    DeployService deploy;
    @Autowired
    ToolGatewayConfigProperties toolGatewayConfigProperties;

    public Redeploy.RedeployResultWrapper rollback(ToolModels.Redeploy redeploy,
                                                   ToolGatewayConfigProperties.DeployableMcpServer d,
                                                   RedeployFunction.RedeployDescriptor r,
                                                   ToolDecoratorService.McpServerToolState remove,
                                                   ToolDecoratorService.DeployState deployState) {
            Redeploy.RedeployResultWrapper redeployResultWrapper = deploy.handleDeploy(
                    DeployService.DeployDescription.AfterFailedDeployTryRollback
                            .builder()
                            .d(d)
                            .redeploy(redeploy)
                            .remove(remove)
                            .deployState(deployState)
                            .r(r)
                            .build());
            if (redeployResultWrapper.redeployResult().deployState().didRollback()) {
                return deploy.handleDeploy(
                        DeployService.DeployDescription.AfterRollbackSuccess
                                .builder()
                                .rollbackResult(redeployResultWrapper)
                                .deployState(deployState)
                                .redeploy(redeploy)
                                .r(r)
                                .remove(remove)
                                .build());

            } else {
                return deploy.handleDeploy(
                        DeployService.DeployDescription.AfterRollbackFail
                                .builder()
                                .rollbackResult(redeployResultWrapper)
                                .deployState(deployState)
                                .r(r)
                                .d(d)
                                .redeploy(redeploy)
                                .build());
            }
    }

    public void prepareRollback(ToolGatewayConfigProperties.DeployableMcpServer d) {
        if (d.copyToArtifactPath().toFile().exists()) {
            try {
                Files.copy(
                        d.copyToArtifactPath(),
                        toolGatewayConfigProperties.getArtifactCache().resolve(d.copyToArtifactPath().toFile().getName()),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.error("Failed to copy MCP server artifact to cache.");
            }
        }
    }

}
