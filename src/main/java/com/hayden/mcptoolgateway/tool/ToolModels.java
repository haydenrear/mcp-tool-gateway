package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface ToolModels {
    record Redeploy(@JsonProperty("Service to redeploy") String deployService) {}
}
