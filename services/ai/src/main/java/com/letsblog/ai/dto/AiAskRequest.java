package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * エディタの右クリックメニュー「Ask AI」から送信される質問リクエスト(issue #526)。
 * providerはOLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はシステム設定の既定プロバイダーを使う。
 */
public record AiAskRequest(@NotBlank String question, String provider) {
}
