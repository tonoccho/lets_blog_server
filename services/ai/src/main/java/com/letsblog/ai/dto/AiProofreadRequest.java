package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * providerはOLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はプロジェクトの選択中プロバイダー、それも無ければ
 * システム設定の既定プロバイダーを使う(issue #523)。projectId(任意)を指定するとそのプロジェクトの選択中モデルを使う(issue #1495)。
 */
public record AiProofreadRequest(@NotBlank String text, String provider, Long projectId) {
}
