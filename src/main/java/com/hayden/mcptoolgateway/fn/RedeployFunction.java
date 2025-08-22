package com.hayden.mcptoolgateway.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import lombok.Builder;

import java.nio.file.Path;
import java.util.Optional;

public interface RedeployFunction {

    @Builder
    record RedeployDescriptor(boolean isSuccess, int exitCode, Path binary, Path log, String err) {}

    RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name);

    void register(ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer);

}
