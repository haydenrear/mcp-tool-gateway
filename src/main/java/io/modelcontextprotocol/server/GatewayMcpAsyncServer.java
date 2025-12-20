package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;

import java.time.Duration;

public class GatewayMcpAsyncServer extends McpAsyncServer{
    /**
     * Create a new McpAsyncServer with the given transport provider and capabilities.
     *
     * @param mcpTransportProvider      The transport layer implementation for MCP
     *                                  communication.
     * @param objectMapper              The ObjectMapper to use for JSON serialization/deserialization
     * @param features                  The MCP server supported features.
     * @param requestTimeout
     * @param uriTemplateManagerFactory
     */
//    TODO: override toolsListRequestHandler so that tools list call takes into account the user's username, only returning specific tools registered.
    GatewayMcpAsyncServer(McpServerTransportProvider mcpTransportProvider, ObjectMapper objectMapper, McpServerFeatures.Async features, Duration requestTimeout, McpUriTemplateManagerFactory uriTemplateManagerFactory) {
        super(mcpTransportProvider, objectMapper, features, requestTimeout, uriTemplateManagerFactory);
    }
}
