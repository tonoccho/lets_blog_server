package com.letsblog.ai.dto;

import java.util.List;

/** レビューステップ単位の指摘生成の応答(issue #1213)。 */
public record AiReviewStepSuggestionsResponse(List<ReviewStepSuggestion> suggestions) {
}
