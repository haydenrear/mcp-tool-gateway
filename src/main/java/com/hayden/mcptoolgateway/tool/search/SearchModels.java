package com.hayden.mcptoolgateway.tool.search;

import lombok.Builder;

public interface SearchModels {

    @Builder(toBuilder = true)
    record SearchResult(String added, String addedSchema, String addErr) {}

}
