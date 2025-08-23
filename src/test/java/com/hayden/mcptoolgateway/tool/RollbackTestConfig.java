package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.fn.RedeployFunction;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import io.modelcontextprotocol.client.McpSyncClient;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("rollback-tests")
class RollbackTestConfig {

    @Bean
    @Primary
    public DynamicMcpToolCallbackProvider dynamicMcpToolCallbackProvider() {
        return Mockito.mock(DynamicMcpToolCallbackProvider.class);
    }

    @Bean
    @Primary
    public RedeployFunction redeployFunction() {
        return  Mockito.mock(RedeployFunction.class);
    }

    @Bean
    @Primary
    public McpSyncClient mcpSyncClient() {
        return Mockito.mock(McpSyncClient.class);
    }

    @Bean
    @Primary
    public McpSyncServerDelegate mcpSyncServerDelegate() {
        return Mockito.mock(McpSyncServerDelegate.class);
    }

}
