package com.hayden.mcptoolgateway.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;

public interface RedeployFunction {

    record RedeployDescriptor(boolean isSuccess) {

    }

    RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name);

}
