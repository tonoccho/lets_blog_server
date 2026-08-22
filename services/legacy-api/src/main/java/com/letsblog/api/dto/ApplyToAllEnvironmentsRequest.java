package com.letsblog.api.dto;

import com.letsblog.api.domain.BulkOperationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * slugベースのプラグイン/テーマインストールを、紐付いている全環境へ一括適用するリクエスト(issue #393)。
 * カテゴリ/タグ系の操作は対象外のため、{@link ApplyToEnvironmentRequest}と異なりcategory系フィールドは持たない。
 */
public record ApplyToAllEnvironmentsRequest(
        @NotNull BulkOperationType operationType,
        @NotBlank String value
) {
}
