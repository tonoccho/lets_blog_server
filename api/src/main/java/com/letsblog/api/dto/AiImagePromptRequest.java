package com.letsblog.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * チャットメッセージから画像生成プロンプトを作成するためのリクエスト。
 * historyは壁打ちの続きとして渡す過去の往復(任意)、messageは今回のユーザー発言。
 */
public record AiImagePromptRequest(
        @Valid List<PlanChatMessage> history,
        @NotBlank String message,
        /**
         * OLLAMA/OPENAI/CLAUDEのいずれか(任意)。指定時はプロジェクト単位の既定プロバイダーより優先する
         * (issue #530)。未指定時はプロジェクト単位の既定→システム設定の既定の順にフォールバックする。
         */
        String provider
) {
}
