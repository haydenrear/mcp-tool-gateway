package com.hayden.mcptoolgateway.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import lombok.Builder;

import java.util.Optional;

public interface RedeployFunction {

    @Builder
    record RedeployDescriptor(boolean isSuccess, String err) {

    }

    RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name);

    default boolean hasRollback(ToolGatewayConfigProperties.DeployableMcpServer name) {
        throw new RuntimeException("Not implemented yet");
    }

    default Optional<RedeployDescriptor> performRollback(ToolGatewayConfigProperties.DeployableMcpServer name) {
        throw new RuntimeException("Not implemented yet");
    }

}
