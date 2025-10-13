package com.hayden.mcptoolgateway.tool.deploy;

import com.hayden.mcptoolgateway.config.ToolGatewayConfigProperties;
import com.hayden.mcptoolgateway.tool.ToolDecorator;
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
        Free<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult> f
                = Free.<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper>
                        liftF(doRedeploy)
                .flatMap(rWrapper -> {
                    return Free.<ToolDecoratorInterpreter.ToolDecoratorEffect, ToolDecoratorInterpreter.ToolDecoratorResult>
                                    liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.UpdateMcpServerWithToolChanges(rWrapper.toolStateChanges()))
                            .flatMap(ts -> Free.liftF(new ToolDecoratorInterpreter.ToolDecoratorEffect.AddMcpServerToolState(
                                    new ToolDecorator.ToolDecoratorToolStateUpdate.AddToolStateUpdate(rWrapper.redeploy().deployService(), rWrapper.newToolState(), rWrapper.toolStateChanges()))))
                            .flatMap(s -> Free.pure(rWrapper));
                })
                .flatMap(Free::pure);

        var parsed = Free.parse(f, toolDecoratorInterpreter);

        if (parsed instanceof ToolDecoratorInterpreter.ToolDecoratorResult.RedeployResultWrapper r) {
            return r;
        }

        throw new RuntimeException("Failed to parse.");
    }

}
