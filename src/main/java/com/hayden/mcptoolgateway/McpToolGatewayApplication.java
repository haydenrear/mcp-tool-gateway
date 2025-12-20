package com.hayden.mcptoolgateway;

import com.hayden.mcptoolgateway.config.KillCdcInitializer;
import org.springframework.ai.mcp.client.autoconfigure.McpClientAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Sits between MCP tool and re-deployability of that tool.
 * Used for working on tools, making better at working on tools.
 * So an IDE doesn't have to restart the tool, it just says, oh yeah, now toSearch that because I made changes to it.
 * Used for using an algorithm you are improving at the same time as improving it, so the AI can test it by using it.
 *
 * Acts as a decorator for a tool, adding the toSearch tool.
 */
@SpringBootApplication(exclude = { McpClientAutoConfiguration.class })
public class McpToolGatewayApplication {

    static void main(String[] args) {
        SpringApplicationBuilder springApplicationBuilder = new SpringApplicationBuilder(McpToolGatewayApplication.class);
        springApplicationBuilder
                .initializers(new KillCdcInitializer())
                .run(args);
    }

}
