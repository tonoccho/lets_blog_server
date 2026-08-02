package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * カテゴリ/タグの編集リクエスト。nameは編集対象を名前で特定するための旧名、
 * それ以外は編集後の新しい値(マスター環境および他の全環境へ反映する)。
 */
public record EditTermRequest(
        @NotBlank String name,
        @NotBlank String value,
        @NotBlank String slug,
        String parentSlug,
        String description
) {
}
