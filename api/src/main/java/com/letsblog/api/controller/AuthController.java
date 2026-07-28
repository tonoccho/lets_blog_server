package com.letsblog.api.controller;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.LoginRequest;
import com.letsblog.api.dto.LoginResponse;
import com.letsblog.api.dto.PasswordResetConfirmRequest;
import com.letsblog.api.dto.PasswordResetRequest;
import com.letsblog.api.dto.SignupRequest;
import com.letsblog.api.dto.TotpLoginVerifyRequest;
import com.letsblog.api.dto.TwoFactorSetupResponse;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.dto.VerifyTotpRequest;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.InvalidTotpCodeException;
import com.letsblog.api.service.PasswordResetService;
import com.letsblog.api.service.TwoFactorService;
import com.letsblog.api.service.UserNotFoundException;
import com.letsblog.api.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final TwoFactorService twoFactorService;
    private final CurrentActorService currentActorService;
    private final UserRepository userRepository;

    public AuthController(
            UserService userService,
            PasswordResetService passwordResetService,
            TwoFactorService twoFactorService,
            CurrentActorService currentActorService,
            UserRepository userRepository) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
        this.twoFactorService = twoFactorService;
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
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

    @PostMapping("/signup")
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return userService.signup(request.email(), request.password());
    }

    @GetMapping("/setup-status")
    public Map<String, Boolean> setupStatus() {
        return Map.of("needsSetup", !userService.hasAnyUser());
    }

    @PostMapping("/setup")
    public UserResponse setup(@Valid @RequestBody SignupRequest request) {
        return userService.setupInitialAdmin(request.email(), request.password());
    }

    /**
     * 2FA有効化を開始する(ログイン済みの本人のみ、X-Actor-Idヘッダから取得)。
     */
    @PostMapping("/totp/setup")
    public TwoFactorSetupResponse setupTwoFactor() {
        Long actorId = currentActorService.getCurrentActorId();
        User user = userRepository.findById(actorId)
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));
        return twoFactorService.generateTwoFactorSecret(user.getId(), user.getEmail());
    }

    /**
     * QRコード確認後、TOTPコードを検証して2FAを有効化する。
     */
    @AuditLog(action = AuditLogAction.TWO_FACTOR_ENABLED, resourceType = "USER")
    @PostMapping("/totp/verify-setup")
    public ResponseEntity<Map<String, String>> verifyTwoFactorSetup(@Valid @RequestBody VerifyTotpRequest request) {
        Long actorId = currentActorService.getCurrentActorId();
        twoFactorService.verifyAndEnableTwoFactor(actorId, request.code());
        return ResponseEntity.ok(Map.of("message", "2FAが有効化されました。"));
    }

    /**
     * ログイン2段階目。パスワード認証(login)でtwoFactorRequired=trueだった場合に呼び出す。
     * まだセッションが確立していないため、userIdをリクエストボディで明示的に受け取る。
     */
    @PostMapping("/totp/verify")
    public LoginResponse verifyTotpLogin(@Valid @RequestBody TotpLoginVerifyRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));

        if (!twoFactorService.verifyTotpCode(user.getId(), request.code())) {
            throw new InvalidTotpCodeException("TOTPコードが無効です。");
        }

        return new LoginResponse(UserResponse.from(user), false);
    }
}
