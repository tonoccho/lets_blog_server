package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * レビューステップ単位の指摘生成リクエスト(issue #1213)。projectId/stepKeyはパス変数で
 * 受け取るため、ボディはtextのみを持つ(AiTagsRequestと異なり、projectIdは必須なのでボディに
 * 持たせず、AiController#reviewStepSuggestions(generate-image-promptと同じ形)のパス変数とする)。
 */
public record AiReviewStepSuggestionsRequest(@NotBlank String text) {
}
