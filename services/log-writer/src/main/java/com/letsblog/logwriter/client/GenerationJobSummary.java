package com.letsblog.logwriter.client;

import java.time.LocalDateTime;

/**
 * legacy-apiの{@code GET /api/generation-jobs}(GenerationJobResponse)の応答形状(#572)。
 * 統合操作ログ(/api/operation-logs/unified)のAI_JOBソースをlegacy-api側から取得するために使う。
 */
public record GenerationJobSummary(Long id, String type, String status, LocalDateTime createdAt) {
}
