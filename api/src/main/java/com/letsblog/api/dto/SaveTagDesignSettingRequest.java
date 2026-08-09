package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SaveTagDesignSettingRequest(
        @NotBlank(message = "プリセットは必須です")
        String presetId,
        @NotBlank(message = "背景色は必須です")
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "背景色は#RRGGBB形式で指定してください")
        String backgroundColor,
        @NotBlank(message = "テキスト色は必須です")
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "テキスト色は#RRGGBB形式で指定してください")
        String textColor,
        @NotBlank(message = "アクセントカラーは必須です")
        @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "アクセントカラーは#RRGGBB形式で指定してください")
        String accentColor) {
}
