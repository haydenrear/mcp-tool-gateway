package com.hayden.mcptoolgateway.tool;

import com.fasterxml.jackson.annotation.JsonProperty;

public interface ToolModels {


    record Redeploy(@JsonProperty("service_name") String deployService) {}


}
