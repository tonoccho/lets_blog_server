package com.letsblog.api.dto;

import com.letsblog.api.domain.BulkOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 単一環境への一括管理操作の適用リクエスト。
 * categorySlug/categoryParentSlug/categoryDescription/categoryTargetSlugの意味はoperationTypeによって変わる
 * (カテゴリ・タグ系の操作のみ使用し、プラグイン・テーマ系の操作はvalue(slug)のみ使用する)。
 */
public record ApplyToEnvironmentRequest(
        @NotBlank String environment,
        @NotNull BulkOperationType operationType,
        String value,
        String categorySlug,
        String categoryParentSlug,
        String categoryDescription,
        String categoryTargetSlug
) {
}
