package com.letsblog.api.service;

import com.letsblog.api.ai.BraveSearchResult;

import java.util.List;

public record WebSearchOutcome(boolean succeeded, List<BraveSearchResult> results, String errorMessage) {

    public static WebSearchOutcome success(List<BraveSearchResult> results) {
        return new WebSearchOutcome(true, results, null);
    }

    public static WebSearchOutcome failure(String errorMessage) {
        return new WebSearchOutcome(false, List.of(), errorMessage);
    }
}
