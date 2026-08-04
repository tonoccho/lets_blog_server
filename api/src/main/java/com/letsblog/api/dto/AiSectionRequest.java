package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * セクション単位(本文/リード文)のAI生成リクエスト。
 * mode: "body"(本文)または "lead"(リード文)。
 */
public record AiSectionRequest(
        @NotBlank String mode,
        @NotBlank String heading,
        String precedingContext,
        String articleTitle
) {
}
