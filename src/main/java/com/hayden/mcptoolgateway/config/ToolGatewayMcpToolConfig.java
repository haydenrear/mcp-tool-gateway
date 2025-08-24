package com.hayden.mcptoolgateway.config;

import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ToolGatewayMcpToolConfig {

    @Bean
    public CommandLineRunner mcpToolShutdown(DynamicMcpToolCallbackProvider clientList) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Running shutdown hook.");
            clientList.shutdown();
        }));
        SpringApplication.getShutdownHandlers().add(() -> {
            log.info("Running shutdown hook.");
            clientList.shutdown();
        });
        return args -> {};
    }


}
