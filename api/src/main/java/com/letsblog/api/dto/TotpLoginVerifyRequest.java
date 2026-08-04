package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * ログイン時のTOTP検証リクエスト。ログインはまだ完了していない(NextAuthセッション未確立)ため、
 * X-Actor-IdヘッダではなくリクエストボディでuserIdを明示的に受け取る。
 */
public record TotpLoginVerifyRequest(
        @NotNull Long userId,
        @NotBlank(message = "TOTPコードは必須です") String code,
        String label
) {
}
