package com.letsblog.ai.dto;

import java.util.List;

/**
 * プロジェクトのLLMモデルの選択肢と選択中のモデル。
 *
 * @param availableModels プロバイダーから取得したモデルの一覧。取得に失敗したときはシステム設定のリスト
 * @param fallback 取得に失敗してシステム設定のリストに戻したとき true(issue #1674)
 */
public record LlmModelListResponse(List<String> availableModels, String selected, boolean fallback) {

    /** 取得に成功した一覧(fallbackではない)。 */
    public LlmModelListResponse(List<String> availableModels, String selected) {
        this(availableModels, selected, false);
    }
}
