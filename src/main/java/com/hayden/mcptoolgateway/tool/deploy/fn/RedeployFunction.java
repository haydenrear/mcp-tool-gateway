package com.hayden.mcptoolgateway.tool.deploy.fn;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import lombok.Builder;

import java.nio.file.Path;
import java.util.Optional;

public interface RedeployFunction {

    String REDEPLOY_MCP_SERVER = "redeploy-mcp-server";

    @Builder
    record RedeployDescriptor(boolean isSuccess, int exitCode, Path binary, Path log, String err) {}

    RedeployDescriptor performRedeploy(ToolGatewayConfigProperties.DeployableMcpServer name);

    void register(ToolGatewayConfigProperties.DeployableMcpServer deployableMcpServer);

}
