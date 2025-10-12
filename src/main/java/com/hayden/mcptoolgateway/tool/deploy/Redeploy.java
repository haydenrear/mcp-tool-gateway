package com.hayden.mcptoolgateway.tool.deploy;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.deploy.fn.RedeployFunction;
import com.hayden.mcptoolgateway.tool.deploy.fn.RollbackFunction;
import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.ToolModels;
import com.hayden.mcptoolgateway.tool.tool_state.McpServerToolStates;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import com.hayden.utilitymodule.free.Free;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class Redeploy {

    @Autowired @Lazy
    private ToolDecoratorInterpreter toolDecoratorInterpreter;

    ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper doRedeploy(ToolModels.Redeploy redeploy,
                          ToolGatewayConfigProperties.DeployableMcpServer redeployMcpServer,
                          ToolDecoratorService.McpServerToolState toolState) {
        return doRedeploy(new ToolDecoratorInterpreter.ToolDecoratorEffect.DoRedeploy(redeploy, redeployMcpServer, toolState));
    }

    public ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper doRedeploy(ToolDecoratorInterpreter.ToolDecoratorEffect.DoRedeploy doRedeploy) {
        var f = Free.<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult>
                liftF(doRedeploy);

        var parsed = Free.parse(f, toolDecoratorInterpreter);

        if (parsed instanceof ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper r) {
            return r;
        }

        throw new RuntimeException("Failed to parse.");
    }

}
