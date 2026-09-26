package com.letsblog.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * テンプレートをプロジェクトの {@code custom_tags} 行として適用する要求(issue #1131)。
 * {@code tagName} の形式は {@link CustomTagRequest#tagName()} と同じ。
 */
public record ApplyCustomTagTemplateRequest(
        @NotNull(message = "適用先のプロジェクトは必須です")
        Long projectId,
        @NotBlank @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$", message = "英数字・ハイフン・アンダースコアのみ使用できます")
        String tagName
) {
}
