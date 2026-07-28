package com.letsblog.api.controller;

import com.letsblog.api.dto.LoginRequest;
import com.letsblog.api.dto.PasswordResetConfirmRequest;
import com.letsblog.api.dto.PasswordResetRequest;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.service.PasswordResetService;
import com.letsblog.api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;

    public AuthController(UserService userService, PasswordResetService passwordResetService) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request.email(), request.password());
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestPasswordReset(request.email());
        // セキュリティ上、メールアドレスが存在するか否かを応答に含めない
        return ResponseEntity.ok(Map.of("message", "再設定用メールを送信しました。メールボックスをご確認ください。"));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Map<String, String>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmPasswordReset(request.token(), request.newPassword());
        return ResponseEntity.ok(Map.of("message", "パスワードをリセットしました。新しいパスワードでログインしてください。"));
    }
}
