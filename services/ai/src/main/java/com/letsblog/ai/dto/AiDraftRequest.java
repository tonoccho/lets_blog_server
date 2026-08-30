package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

/** providerはOLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はシステム設定の既定プロバイダーを使う(issue #530)。 */
public record AiDraftRequest(@NotBlank String mode, @NotBlank String text, String provider) {
}
