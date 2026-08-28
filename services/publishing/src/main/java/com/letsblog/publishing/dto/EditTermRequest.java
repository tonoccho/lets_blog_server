package com.letsblog.publishing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * カテゴリ/タグの編集リクエスト。targetSlugは編集対象を特定するための現在のスラッグ、
 * それ以外は編集後の新しい値(マスター環境および他の全環境へ反映する)。
 */
public record EditTermRequest(
        @NotBlank String targetSlug,
        @NotBlank String value,
        @NotBlank String slug,
        String parentSlug,
        String description
) {
}
