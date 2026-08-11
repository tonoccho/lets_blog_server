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
        @NotBlank String message
) {
}
