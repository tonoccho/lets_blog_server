package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * providerはOLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はシステム設定の既定プロバイダーを使う(issue #523)。
 */
public record AiProofreadRequest(@NotBlank String text, String provider) {
}
