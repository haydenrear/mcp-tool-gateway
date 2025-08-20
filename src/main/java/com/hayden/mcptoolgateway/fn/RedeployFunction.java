package com.hayden.mcptoolgateway.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import lombok.Builder;

public interface RedeployFunction {

    @Builder
    record RedeployDescriptor(boolean isSuccess, String err) {

    }

    RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name);

}
