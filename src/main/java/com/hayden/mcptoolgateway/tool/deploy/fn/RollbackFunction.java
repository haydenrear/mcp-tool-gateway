package com.hayden.mcptoolgateway.tool.deploy.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.*;
import com.hayden.mcptoolgateway.tool.deploy.DeployModels;
import com.hayden.mcptoolgateway.tool.deploy.DeployService;
import com.hayden.mcptoolgateway.tool.deploy.Redeploy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
                                                   DeployModels.DeployState deployState) {
            Redeploy.RedeployResultWrapper redeployResultWrapper = deploy.handleDeploy(
                    DeployService.DeployDescription.AfterFailedDeployTryRollback
                            .builder()
                            .d(d)
                            .redeploy(redeploy)
                            .remove(remove)
                            .deployState(deployState)
                            .r(r)
                            .build());
            if (redeployResultWrapper.redeployResult().rollbackState().didRollback()) {
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
                if (!Files.exists(toolGatewayConfigProperties.getArtifactCache())
                        && !toolGatewayConfigProperties.getArtifactCache().toFile().mkdirs()) {
                    log.error("Failed to create artifact cache directory");
                }
                Path toCopyTo = toolGatewayConfigProperties.getArtifactCache().resolve(d.getCopyFromArtifactPath().toFile().getName());
                Path toCopyFrom = d.copyToArtifactPath().resolve(d.getCopyFromArtifactPath().toFile().getName());
                if (!toCopyFrom.toFile().exists()) {
                    log.error("Copy from {} did not exist when redeploying.", toCopyFrom);
                } else {
                    log.info("Copying {} to {}", toCopyFrom, toCopyTo);
                    Files.copy(
                            toCopyFrom,
                            toCopyTo,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.error("Failed to copy MCP server artifact to cache: {}.", e.getMessage(), e);
            }
        }
    }

}
