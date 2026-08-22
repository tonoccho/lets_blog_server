package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Next.js側のOAuthコールバックルートから、受け取った認可コードを交換依頼するためのリクエスト。 */
public record CompleteAdSenseOAuthRequest(
        @NotBlank String code,
        @NotBlank String redirectUri
) {
}
