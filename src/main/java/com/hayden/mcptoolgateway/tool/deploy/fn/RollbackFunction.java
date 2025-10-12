package com.hayden.mcptoolgateway.tool.deploy.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.*;
import com.hayden.mcptoolgateway.tool.deploy.DeployModels;
import com.hayden.mcptoolgateway.tool.deploy.DeployService;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.free.Free;
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

    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> rollback(ToolDecoratorInterpreter.ToolDecoratorEffect.PerformRollback performRollback) {
        return rollback(performRollback.redeploy(), performRollback.d(), performRollback.r(), performRollback.remove(), performRollback.deployState()) ;
    }

    public Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> rollback(ToolModels.Redeploy redeploy,
                                                                                                                     ToolGatewayConfigProperties.DeployableMcpServer d,
                                                                                                                     ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor r,
                                                                                                                     ToolDecoratorService.McpServerToolState remove,
                                                                                                                     DeployModels.DeployState deployState) {

        return deploy.handleDeploy(
                        ToolDecoratorInterpreter.ToolDecoratorEffect.AfterDeployHandleMcp.AfterFailedDeployTryRollback
                                .builder()
                                .d(d)
                                .redeploy(redeploy)
                                .remove(remove)
                                .deployState(deployState)
                                .r(r)
                                .build()
                )
                .flatMap(tdr -> {
                    if (tdr.redeployResult().rollbackState().didRollback()) {
                        return deploy.handleDeploy(
                                ToolDecoratorInterpreter.ToolDecoratorEffect.AfterDeployHandleMcp.AfterRollbackSuccess
                                        .builder()
                                        .rollbackResult(tdr)
                                        .deployState(deployState)
                                        .redeploy(redeploy)
                                        .r(r)
                                        .remove(remove)
                                        .build());

                    } else {
                        return deploy.handleDeploy(
                                ToolDecoratorInterpreter.ToolDecoratorEffect.AfterDeployHandleMcp.AfterRollbackFail
                                        .builder()
                                        .rollbackResult(tdr)
                                        .deployState(deployState)
                                        .r(r)
                                        .d(d)
                                        .redeploy(redeploy)
                                        .build());
                    }
                })
                .flatMap(Free::pure);

    }

    public ToolDecoratorInterpreter.ToolDecoratorResult.PrepareRollbackResult prepareRollback(ToolGatewayConfigProperties.DeployableMcpServer d) {
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

                return new ToolDecoratorInterpreter.ToolDecoratorResult.PrepareRollbackResult();
            } catch (IOException e) {
                log.error("Failed to copy MCP server artifact to cache: {}.", e.getMessage(), e);
            }
        }
        return new ToolDecoratorInterpreter.ToolDecoratorResult.PrepareRollbackResult();
    }

}
