package com.letsblog.project.dto;

import jakarta.validation.constraints.NotBlank;

/** 静的コンテンツの「保存」(issue #1409)。生成結果を確認した利用者が、本文をそのまま・または手直しして保存する。 */
public record SaveStaticContentRequest(@NotBlank(message = "本文は必須です") String body) {
}
