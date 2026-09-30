package com.letsblog.ai.service;

import com.letsblog.ai.domain.ReviewStepKey;

import java.util.Locale;

/**
 * レビューステップキー(issue #1210)をパス変数の生文字列から解決する(issue #1222)。
 *
 * <p>{@code @PathVariable ReviewStepKey}のままSpringの型変換に任せると、未知の値は
 * {@link InvalidReviewInputException}のJavadocで説明した原因連鎖の問題により409 CONFLICTになって
 * しまうため、Controller側ではパス変数を{@code String}のまま受け取り、本クラスで明示的に
 * 400 Bad Requestとして扱う({@link com.letsblog.ai.controller.ProjectLlmModelController}の
 * 設定保存API(issue #1211)と{@link com.letsblog.ai.controller.AiController}の指摘生成API
 * (issue #1213)の両方が対象)。
 */
public final class ReviewStepKeys {

    private ReviewStepKeys() {
    }

    public static ReviewStepKey parse(String rawStepKey) {
        if (rawStepKey == null || rawStepKey.isBlank()) {
            throw new InvalidReviewInputException("レビューステップキーを指定してください");
        }
        try {
            return ReviewStepKey.valueOf(rawStepKey.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidReviewInputException("不明なレビューステップキーです: " + rawStepKey);
        }
    }
}
