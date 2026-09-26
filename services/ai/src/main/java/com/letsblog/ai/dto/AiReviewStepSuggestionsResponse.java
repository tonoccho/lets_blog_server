package com.letsblog.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * レビューステップ単位の指摘生成の応答(issue #1213)。
 *
 * <p>{@code skipped}/{@code skipReason}は校閲(FACT_CHECK、issue #1214)だけが返す。校閲はBrave Search
 * に依存し、APIキー未設定・検索失敗のときは校閲を実行できない。そのとき指摘0件の応答と
 * 「事実確認済みで問題なし」を取り違えないよう、実行しなかったこと({@code skipped=true})とその理由
 * ({@code skipReason})を明示する。校閲が実行できたときは{@code skipped=false}。
 * 校閲以外のステップでは両方nullで、JSONにもキーが現れない(既存の応答の形を変えないため)。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiReviewStepSuggestionsResponse(
        List<ReviewStepSuggestion> suggestions, Boolean skipped, String skipReason) {

    /** 校閲以外のステップ用。スキップ情報を持たない(JSONにも出さない)。 */
    public AiReviewStepSuggestionsResponse(List<ReviewStepSuggestion> suggestions) {
        this(suggestions, null, null);
    }
}
