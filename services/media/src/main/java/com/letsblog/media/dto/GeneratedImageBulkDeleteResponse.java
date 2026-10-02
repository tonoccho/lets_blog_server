package com.letsblog.media.dto;

import java.util.List;
import java.util.Map;

/**
 * 生成画像の一括削除の結果(issue #1492)。{@code MediaGarbageCollectionService}の結果と同じ形で、
 * {@code failures}は画像id(文字列)から失敗理由への対応。
 */
public record GeneratedImageBulkDeleteResponse(
        int deletedCount, int failedCount, List<Long> deletedIds, Map<String, String> failures) {
}
