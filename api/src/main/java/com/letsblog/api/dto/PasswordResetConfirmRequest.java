package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
        @NotBlank(message = "トークンは必須です")
        String token,

        @NotBlank(message = "パスワードは必須です")
        @Size(min = 8, message = "パスワードは8文字以上である必要があります")
        String newPassword
) {
}
