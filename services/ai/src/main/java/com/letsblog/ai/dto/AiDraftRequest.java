package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

/** providerはOLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はプロジェクトの選択中プロバイダー、それも無ければシステム設定の既定プロバイダーを使う(issue #530)。
 * projectId(任意)を指定するとそのプロジェクトの選択中モデルを使う(issue #1495)。 */
public record AiDraftRequest(@NotBlank String mode, @NotBlank String text, String provider, Long projectId) {
}
