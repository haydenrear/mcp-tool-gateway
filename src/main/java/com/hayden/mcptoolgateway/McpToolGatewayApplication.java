package com.hayden.mcptoolgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sits between MCP tool and deployability of that tool.
 */
@SpringBootApplication
public class McpToolGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpToolGatewayApplication.class, args);
    }

}
