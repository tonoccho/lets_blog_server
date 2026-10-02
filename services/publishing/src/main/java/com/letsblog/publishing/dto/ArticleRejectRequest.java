package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotBlank;

/** 記事差し戻しAPIの入力(issue #1344)。指摘事項は必須で、空・空白だけは拒否する。 */
public record ArticleRejectRequest(@NotBlank String comment) {
}
