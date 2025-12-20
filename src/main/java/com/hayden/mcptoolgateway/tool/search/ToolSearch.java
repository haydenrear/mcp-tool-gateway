package com.hayden.mcptoolgateway.tool.search;

import com.hayden.mcptoolgateway.tool.ToolDecoratorService;
import com.hayden.mcptoolgateway.tool.tool_state.ToolDecoratorInterpreter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class ToolSearch {


    private ToolDecoratorService toolDecoratorService;

    @Autowired @Lazy
    public void setToolDecoratorService(ToolDecoratorService toolDecoratorService) {
        this.toolDecoratorService = toolDecoratorService;
    }

    public ToolDecoratorInterpreter.ToolDecoratorResult.SearchResultWrapper doToolSearch(ToolDecoratorInterpreter.ToolDecoratorEffect.DoToolSearch toolSearch) {
        var added = toolDecoratorService.createAddServer(toolSearch);
        return ToolDecoratorInterpreter.ToolDecoratorResult.SearchResultWrapper.builder()
                .toolStateChanges(added.underlying().getToolStateChanges())
                .added(added.underlying().toAddTools())
                .newToolState(added.underlying().toolState())
                .err(added.underlying().err())
                .build();
    }

}
