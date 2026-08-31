package com.letsblog.api.dto;

import jakarta.validation.constraints.Size;

/**
 * プロジェクトごとの画像生成デフォルト(negative prompt/画質プロンプト)の更新リクエスト(issue #293)。
 * 空文字はアプリ全体のデフォルトへのフォールバックを意味する。
 */
public record UpdateImageGenerationPromptDefaultsRequest(
        @Size(max = 1000, message = "1000文字以内で入力してください")
        String defaultNegativePrompt,
        @Size(max = 500, message = "500文字以内で入力してください")
        String defaultQualityPrompt
) {
}
