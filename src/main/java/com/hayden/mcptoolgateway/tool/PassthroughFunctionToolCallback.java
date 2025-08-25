package com.hayden.mcptoolgateway.tool;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

import java.util.function.BiFunction;


@RequiredArgsConstructor
public class PassthroughFunctionToolCallback implements ToolCallback {

    private final BiFunction<String, ToolContext, String> fn;

    @Getter
    private final ToolDefinition toolDefinition;


    @Override
    public @NotNull String call(@NotNull String toolInput) {
        return call(toolInput, null);
    }

    @Override
   public @NotNull String call(@NotNull String toolInput, @Nullable ToolContext tooContext) {
        String response = fn.apply(toolInput, tooContext);
        return response;
    }

}
