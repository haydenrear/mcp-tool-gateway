package com.hayden.mcptoolgateway.config;

import com.hayden.commitdiffmodel.config.GraphQlProps;
import com.hayden.utilitymodule.concurrent.striped.StripedLockAspect;
import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.schema.DelegatingSchemaReplacer;
import com.hayden.utilitymodule.schema.SpecialJsonSchemaGenerator;
import com.hayden.utilitymodule.schema.SpecialMethodToolCallbackProviderFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Import({DynamicMcpToolCallbackProvider.class, DelegatingSchemaReplacer.class,
         SpecialJsonSchemaGenerator.class, SpecialMethodToolCallbackProviderFactory.class, StripedLockAspect.class})
@EnableConfigurationProperties({GraphQlProps.class})
@Configuration
public class McpToolConfig {

}
