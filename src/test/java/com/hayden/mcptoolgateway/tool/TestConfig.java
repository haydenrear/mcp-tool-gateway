package com.hayden.mcptoolgateway.tool;

import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.tool_state.McpSyncServerDelegate;
import io.modelcontextprotocol.client.McpSyncClient;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("test")
class TestConfig {

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
