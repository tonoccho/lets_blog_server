package com.letsblog.identity.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * issue #1259: 個人設定のタイムゾーンは任意の上書き。{@code timezone}に{@code null}を渡すと
 * 「ブラウザのタイムゾーンに従う(未設定)」へ戻せるため、{@code locale}と異なり
 * {@code @NotBlank}を付けない(空文字は{@link com.letsblog.identity.service.UserService}側の
 * {@code ZoneId.of}検証で不正な値として弾かれる)。
 */
public record UpdateUserPreferencesRequest(
        @NotBlank String locale,
        String timezone
) {
}
