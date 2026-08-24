package com.letsblog.content.dto;

import com.letsblog.content.domain.CustomTagFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CustomTagRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$", message = "英数字・ハイフン・アンダースコアのみ使用できます")
        String tagName,
        @NotBlank String htmlTemplate,
        String description,
        String cssContent,
        // 未指定時はBLOCK扱い(CustomTagService側でデフォルト適用、既存クライアントとの後方互換のため)
        CustomTagFormat tagFormat,
        Long projectId
) {
}
