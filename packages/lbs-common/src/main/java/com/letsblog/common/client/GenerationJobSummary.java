package com.letsblog.common.client;

import java.time.LocalDateTime;

/**
 * ai-serviceの{@code GenerationJobResponse}を写したもの(#573 stage3、#825、#1483でmediaとlog-writerの
 * 複製をここへ統合した)。{@code POST /api/internal/ai/generation-jobs}(media)と
 * {@code GET /api/generation-jobs}(log-writer、統合操作ログのAI_JOBソース)の応答に使う。
 *
 * <p><b>ai-service側のDTOと形状を追随させ続ける必要がある。</b>
 * 使っているフィールドの名前や型が向こうで変わると無音で壊れる(#825の縮退により、
 * デシリアライズ失敗もAI_JOBソースの除外として処理されるため)。
 * {@code GenerationJobClientListTest}が実際のJSONでこの形状を固定している。
 * log-writerは{@code updatedAt}を使わないが、余剰フィールドとしては無害である。
 */
public record GenerationJobSummary(Long id, String type, String status, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
