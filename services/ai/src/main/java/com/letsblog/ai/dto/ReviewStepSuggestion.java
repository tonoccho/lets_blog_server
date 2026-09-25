package com.letsblog.ai.dto;

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
 */
public record ReviewStepSuggestion(String id, String stepKey, String originalText, String message) {
}
