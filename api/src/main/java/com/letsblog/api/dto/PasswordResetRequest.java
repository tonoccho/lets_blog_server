package com.letsblog.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
        @NotBlank(message = "メールアドレスは必須です")
        @Email(message = "有効なメールアドレスを入力してください")
        String email
) {
}
