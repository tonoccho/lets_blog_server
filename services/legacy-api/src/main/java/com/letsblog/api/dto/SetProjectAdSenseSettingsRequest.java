package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

/** AdSenseパブリッシャーIDとGoogle OAuthクライアントID(秘匿情報ではない)をまとめて保存する(issue #407)。 */
public record SetProjectAdSenseSettingsRequest(@NotBlank String accountId, String clientId) {
}
