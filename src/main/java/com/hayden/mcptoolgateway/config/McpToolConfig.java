package com.hayden.mcptoolgateway.config;

import com.hayden.utilitymodule.delegate_mcp.DynamicMcpToolCallbackProvider;
import com.hayden.utilitymodule.schema.DelegatingSchemaReplacer;
import com.hayden.utilitymodule.schema.SpecialJsonSchemaGenerator;
import com.hayden.utilitymodule.schema.SpecialMethodToolCallbackProviderFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Import({DynamicMcpToolCallbackProvider.class, DelegatingSchemaReplacer.class,
         SpecialJsonSchemaGenerator.class, SpecialMethodToolCallbackProviderFactory.class})
@Configuration
public class McpToolConfig {
}
