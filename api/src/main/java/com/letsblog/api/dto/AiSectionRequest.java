package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * セクション単位(本文/リード文)のAI生成リクエスト。
 * mode: "body"(本文)、"lead"(記事全体を考慮したリード文)、"lead-subsections"(サブセクションを考慮したリード文)のいずれか。
 * historyとmessageが指定された場合は壁打ち(追加指示による再生成)として扱い、直前の生成結果を踏まえて再度生成する。
 */
public record AiSectionRequest(
        @NotBlank String mode,
        String heading,
        String precedingContext,
        String articleTitle,
        List<String> subsectionHeadings,
        List<PlanChatMessage> history,
        String message
) {
}
