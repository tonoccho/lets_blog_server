package com.letsblog.logwriter.client;

import java.time.LocalDateTime;

/**
 * ai-serviceの{@code GET /api/generation-jobs}({@code GenerationJobResponse})の応答形状(#572、#825)。
 * 統合操作ログ(/api/operation-logs/unified)のAI_JOBソースを取得するために使う。
 *
 * <p><b>ai-service側のDTOと形状を追随させ続ける必要がある。</b>
 * 向こうは{@code updatedAt}も返すが本レコードは持たない(統合ログで使わないため)。
 * 余剰フィールドはJacksonの既定({@code FAIL_ON_UNKNOWN_PROPERTIES}無効)で無視される。
 * ただし<b>使っているフィールドの名前や型が向こうで変わると無音で壊れる</b>
 * (#825の縮退により、デシリアライズ失敗もAI_JOBソースの除外として処理されるため)。
 * {@code GenerationJobClientTest}が実際のJSONでこの形状を固定している。
 */
public record GenerationJobSummary(Long id, String type, String status, LocalDateTime createdAt) {
}
