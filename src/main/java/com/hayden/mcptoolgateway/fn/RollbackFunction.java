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
    @Autowired
    SetClients setMcpClient;

    public Redeploy.RedeployResultWrapper rollback(ToolModels.Redeploy redeploy,
                                                    ToolGatewayConfigProperties.DeployableMcpServer d,
                                                    RedeployFunction.RedeployDescriptor r,
                                                    ToolDecoratorService.McpServerToolState remove) {
        try {
            Redeploy.RedeployResultWrapper redeployResultWrapper = tryRollback(redeploy, d, r, remove);
            if (redeployResultWrapper.redeployResult().didRollback()) {
                var w = deploy.handleDidRedeployUpdateToolCallbackProviders(redeploy, r, remove);

                return w.toBuilder()
                        .redeployResult(
                                w.redeployResult()
                                        .toBuilder()
                                        .deployLog(r.log())
                                        .deployErr(performedRedeployResultRollback(redeploy, r))
                                        .didRollback(true)
                                        .build())
                        .build();
            } else {
                var w = deploy.handleFailedRedeployNoRollback(redeploy, r, remove);
                ToolDecoratorService.RedeployResult res = w.redeployResult();
                return w.toBuilder()
                        .redeployResult(res
                                .toBuilder()
                                .deployLog(r.log())
                                .deployErr("Deploy err: %s - tried to rollback but failed with unknown error."
                                        .formatted(res.deployErr()))
                                .build())
                        .build();
            }
        } catch (IOException e) {
            log.error("Failed to copy MCP server artifact back from cache - failed to rollback to previous version.");
            var w = deploy.handleFailedRedeployNoRollback(redeploy, r, remove);
            var redeployResult = w.redeployResult();
            return w.toBuilder()
                    .redeployResult(redeployResult.toBuilder()
                            .deployLog(r.log())
                            .deployErr("Deploy err: %s - tried to rollback but failed with err: %s"
                                    .formatted(redeployResult.deployErr(), e.getMessage()))
                            .build())
                    .build();
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

    private Redeploy.RedeployResultWrapper tryRollback(ToolModels.Redeploy redeploy,
                                                       ToolGatewayConfigProperties.DeployableMcpServer d,
                                                       RedeployFunction.RedeployDescriptor r,
                                                       ToolDecoratorService.McpServerToolState toolState) throws IOException {
        if (!toolGatewayConfigProperties.getArtifactCache().resolve(d.copyToArtifactPath().toFile().getName()).toFile().exists()) {
            return doTryRollbackInner(redeploy, r, toolState);
        }

        Files.copy(
                toolGatewayConfigProperties.getArtifactCache().resolve(d.copyToArtifactPath().toFile().getName()),
                d.copyToArtifactPath(),
                StandardCopyOption.REPLACE_EXISTING);

        return doTryRollbackInner(redeploy, r, toolState);
    }

    private Redeploy.RedeployResultWrapper doTryRollbackInner(ToolModels.Redeploy redeploy, RedeployFunction.RedeployDescriptor r, ToolDecoratorService.McpServerToolState toolState) {
        var toSet = setMcpClient.setMcpClient(redeploy.deployService(), toolState);

        if (toSet.wasSuccessful() && setMcpClient.clientInitialized(redeploy.deployService())) {
            return Redeploy.RedeployResultWrapper
                    .builder()
                    .redeploy(redeploy)
                    .redeployResult(ToolDecoratorService.RedeployResult
                            .builder()
                            .tools(toSet.tools())
                            .deployErr(r.err())
                            .deployLog(r.log())
                            .didRollback(true)
                            .build())
                    .build();
        }

        return Redeploy.RedeployResultWrapper
                .builder()
                .redeploy(redeploy)
                .redeployResult(ToolDecoratorService.RedeployResult.builder()
                        .deployErr(r.err())
                        .deployLog(r.log())
                        .didRollback(false)
                        .mcpConnectErr(toSet.err())
                        .build())
                .build();
    }
}
