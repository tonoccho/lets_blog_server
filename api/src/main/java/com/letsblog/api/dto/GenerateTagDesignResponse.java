package com.letsblog.api.dto;

/** htmlTemplateはAIが構造変更不要と判断した場合に空文字になりうる(その場合は現在の値を維持する)。 */
public record GenerateTagDesignResponse(String htmlTemplate, String cssContent) {
}
