package com.letsblog.api.dto;

import com.letsblog.api.domain.BulkOperationType;
import jakarta.validation.constraints.NotNull;

/**
 * 各フィールドの意味はoperationTypeによって変わる(必須項目はBulkManagementService側で検証する)。
 * - value: CATEGORY_CREATE/EDIT=名前 / PLUGIN_*・THEME_*=slug / CATEGORY_DELETE=未使用
 * - categorySlug: CATEGORY_CREATE/EDIT=作成・変更後のスラッグ
 * - categoryParentSlug: CATEGORY_CREATE/EDIT=親カテゴリのスラッグ(任意、既存カテゴリのものを指定)
 * - categoryDescription: CATEGORY_CREATE/EDIT=説明(任意)
 * - categoryTargetSlug: CATEGORY_EDIT/DELETE=編集・削除対象の現在のスラッグ(必須)
 */
public record BulkOperationRequest(
        @NotNull BulkOperationType operationType,
        String value,
        String categorySlug,
        String categoryParentSlug,
        String categoryDescription,
        String categoryTargetSlug
) {
}
