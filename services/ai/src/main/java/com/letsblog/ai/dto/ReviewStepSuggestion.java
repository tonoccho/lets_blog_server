package com.letsblog.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * レビューステップ単位で検出した1件の指摘(issue #1213)。
 *
 * <p>{@code id}は、拡張側が「前回スキップした指摘と同じか」を本文中の出現位置に依らず判定
 * できるよう、{@code stepKey + originalText + message}から導いた安定した識別子(サーバは状態を
 * 持たない)。同じ語句が本文中の複数箇所にあり同じ指摘内容が付いた場合、それらは同一のidになる
 * (仕様。Issue #1213の実装ノート参照)。
 *
 * <p>{@code originalText}は本文中に実在する引用そのもの(位置特定用、
 * {@link com.letsblog.ai.service.AiAssistService}の既存の防御と同じくLLMの幻覚を除外する)。
 *
 * <p>{@code sources}は校閲(FACT_CHECK、issue #1214)が判断の根拠にしたWeb検索の出典(タイトルとURL)。
 * 校閲以外のステップではnullで、JSONにもキーが現れない(既存の応答の形を変えないため)。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewStepSuggestion(
        String id, String stepKey, String originalText, String message, List<SourceReference> sources) {

    /** 出典を持たないステップ用。 */
    public ReviewStepSuggestion(String id, String stepKey, String originalText, String message) {
        this(id, stepKey, originalText, message, null);
    }
}
