package com.hayden.mcptoolgateway.tool.deploy.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;

public interface RedeployFunction {

    String REDEPLOY_MCP_SERVER = "redeploy-mcp-server";

    ToolDecoratorInterpreter.ToolDecoratorResult.RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DecoratedMcpServer name);

    void register(ToolGatewayConfigProperties.DecoratedMcpServer deployableMcpServer);

}
