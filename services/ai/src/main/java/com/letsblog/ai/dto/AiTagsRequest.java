package com.letsblog.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * providerはOLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はシステム設定の既定プロバイダーを使う(issue #530)。
 * projectIdは任意。指定時はそのプロジェクトのマスター環境サイトに既存のタグを優先して提案する(issue #525)。
 */
public record AiTagsRequest(@NotBlank String text, String provider, Long projectId) {
}
