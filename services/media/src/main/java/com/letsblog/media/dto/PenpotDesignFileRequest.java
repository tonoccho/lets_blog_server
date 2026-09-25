package com.letsblog.media.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * カスタムタグ生成({@code com.letsblog.api.service.CustomTagGenerationService}、legacy-api側に残る)
 * からの{@code POST /api/render/penpot/design-file}呼び出しリクエスト(#573)。
 */
public record PenpotDesignFileRequest(@NotBlank String fileName, String promptContext) {
}
