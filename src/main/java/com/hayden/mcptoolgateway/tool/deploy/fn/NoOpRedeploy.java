package com.hayden.mcptoolgateway.tool.deploy.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ConditionalOnProperty(value = "gateway.enable-redeployable", havingValue = "false")
public class NoOpRedeploy implements RedeployFunction {
    @Override
    public ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name) {
        throw new  UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void register(ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer) {
        throw new  UnsupportedOperationException("Not supported yet.");
    }
}
