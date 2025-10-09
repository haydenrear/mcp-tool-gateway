package com.hayden.mcptoolgateway.tool.deploy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.nio.file.Path;
import java.util.Set;

public interface DeployModels {
    enum DeployState {
        DEPLOY_SUCCESSFUL,
        DEPLOY_FAIL_NO_CONNECT_MCP,
        DEPLOY_FAIL,
        ROLLBACK_SUCCESSFUL,
        ROLLBACK_FAIL,
        ROLLBACK_FAIL_NO_CONNECT_MCP;

        public boolean didToolListChange() {
            return DEPLOY_SUCCESSFUL == this
                    || ROLLBACK_FAIL == this
                    || ROLLBACK_FAIL_NO_CONNECT_MCP == this;
        }

        public boolean didRedeploy() {
            return DEPLOY_SUCCESSFUL == this;
        }

        public boolean didRollback() {
            return ROLLBACK_SUCCESSFUL == this;
        }

    }

    @Builder(toBuilder = true)
    record RedeployResult(
            @JsonProperty
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Set<String> toolsRemoved,
            @JsonProperty
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Set<String> toolsAdded,
            @JsonProperty("""
                    Tools that the deployed MCP server provided in list tools call
                    """)
            Set<String> tools,
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @JsonProperty("""
                    Error propagated for deploy
                    """)
            String deployErr,
            @JsonProperty("""
                    Path to find the deploy log
                    """) Path deployLog,
            @JsonProperty("""
                    Error propagated when attempting to run and connect to the MCP server
                    """)
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            String mcpConnectErr,
            @JsonProperty("""
                    The state of the deployment. The options are:
                    - DEPLOY_SUCCESSFUL
                        The deployment was successful and was able to connect to the new MCP server after deployment
                    - DEPLOY_FAIL_NO_CONNECT_MCP
                        The deployment failed because was not able to connect to the new MCP server after deployment
                    - DEPLOY_FAIL
                        The deployment failed to build the new MCP server
                    """)
            DeployState deployState,
            @JsonProperty("""
                    The state of the rollback. The options are:
                    - ROLLBACK_SUCCESSFUL
                        The deployment failed but was then able to rollback to the old version of the MCP server and connect to it.
                    - ROLLBACK_FAIL
                        The deployment failed and then the rollback also failed.
                    - ROLLBACK_FAIL_NO_CONNECT_MCP
                        The deployment failed and then was not able to connect to the old version of the MCP server.
                    """)
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            DeployState rollbackState,
            @JsonProperty("""
                    Provide any more information about the deployment
                    """)
            String deployMessage,
            @JsonProperty("""
                    Error happened during rollback.
                    """)
            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            String rollbackErr,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Path deployedMcpServerLog
    ) {}
}
