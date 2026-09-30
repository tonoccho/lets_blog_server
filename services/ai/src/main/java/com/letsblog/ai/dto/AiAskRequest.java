package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * エディタの右クリックメニュー「Ask AI」から送信される質問リクエスト(issue #526)。
 * providerはOLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はプロジェクトの選択中プロバイダー、それも無ければ
 * システム設定の既定プロバイダーを使う。projectId(任意)を指定するとそのプロジェクトの選択中モデルを使う(issue #1495)。
 */
public record AiAskRequest(@NotBlank String question, String provider, Long projectId) {
}
