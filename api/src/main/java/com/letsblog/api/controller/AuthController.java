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
import com.letsblog.api.service.ApiKeyService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.InvalidTotpCodeException;
import com.letsblog.api.service.PasswordResetService;
import com.letsblog.api.service.TwoFactorService;
import com.letsblog.api.service.UserNotFoundException;
import com.letsblog.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "Authentication", description = "認証・ログイン関連API")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final TwoFactorService twoFactorService;
    private final CurrentActorService currentActorService;
    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;

    public AuthController(
            UserService userService,
            PasswordResetService passwordResetService,
            TwoFactorService twoFactorService,
            CurrentActorService currentActorService,
            UserRepository userRepository,
            ApiKeyService apiKeyService) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
        this.twoFactorService = twoFactorService;
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
        this.apiKeyService = apiKeyService;
    }

    @Operation(summary = "ログイン", description = "メールアドレスとパスワードでログインします。レスポンスのheadersにX-API-Keyが含まれます。")
    @ApiResponse(responseCode = "200", description = "ログイン成功、APIキーを返す")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @ApiResponse(responseCode = "401", description = "メールアドレスまたはパスワードが不正")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return userService.login(request.email(), request.password(), request.label());
    }

    @Operation(summary = "パスワードリセットをリクエスト", description = "パスワードリセット用のメールをユーザーに送信します")
    @ApiResponse(responseCode = "200", description = "リセット用メールを送信しました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.requestPasswordReset(request.email());
        // セキュリティ上、メールアドレスが存在するか否かを応答に含めない
        return ResponseEntity.ok(Map.of("message", "再設定用メールを送信しました。メールボックスをご確認ください。"));
    }

    @Operation(summary = "パスワードリセットを確認", description = "パスワードリセットトークンを使用して新しいパスワードを設定します")
    @ApiResponse(responseCode = "200", description = "パスワードをリセットしました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正またはトークンが無効")
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Map<String, String>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmPasswordReset(request.token(), request.newPassword());
        return ResponseEntity.ok(Map.of("message", "パスワードをリセットしました。新しいパスワードでログインしてください。"));
    }

    @Operation(summary = "ユーザー登録", description = "新しいユーザーアカウントを作成します")
    @ApiResponse(responseCode = "200", description = "ユーザーが登録されました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @PostMapping("/signup")
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return userService.signup(request.email(), request.password());
    }

    @Operation(summary = "セットアップ状態を確認", description = "システムのセットアップが必要かどうかを確認します")
    @ApiResponse(responseCode = "200", description = "セットアップ状態を返す")
    @GetMapping("/setup-status")
    public Map<String, Boolean> setupStatus() {
        return Map.of("needsSetup", !userService.hasAnyUser());
    }

    @Operation(summary = "初期管理者をセットアップ", description = "最初の管理者ユーザーをセットアップします")
    @ApiResponse(responseCode = "200", description = "管理者がセットアップされました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @PostMapping("/setup")
    public UserResponse setup(@Valid @RequestBody SignupRequest request) {
        return userService.setupInitialAdmin(request.email(), request.password());
    }

    @Operation(summary = "2FAステータスを確認", description = "ログイン中のユーザーの2FA(二段階認証)有効化状態を確認します")
    @ApiResponse(responseCode = "200", description = "2FAの有効化状態を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @GetMapping("/totp/status")
    public Map<String, Boolean> twoFactorStatus() {
        Long actorId = currentActorService.getCurrentActorId();
        return Map.of("enabled", twoFactorService.isTwoFactorEnabled(actorId));
    }

    @Operation(summary = "2FAセットアップを開始", description = "2FA有効化のためのシークレットキーとQRコードを生成します")
    @ApiResponse(responseCode = "200", description = "シークレットキーとQRコードを返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @PostMapping("/totp/setup")
    public TwoFactorSetupResponse setupTwoFactor() {
        Long actorId = currentActorService.getCurrentActorId();
        User user = userRepository.findById(actorId)
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));
        return twoFactorService.generateTwoFactorSecret(user.getId(), user.getEmail());
    }

    @Operation(summary = "2FAセットアップを確認", description = "TOTPコードを検証して2FAを有効化します")
    @ApiResponse(responseCode = "200", description = "2FAが有効化されました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正またはTOTPコードが無効")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @AuditLog(action = AuditLogAction.TWO_FACTOR_ENABLED, resourceType = "USER")
    @PostMapping("/totp/verify-setup")
    public ResponseEntity<Map<String, String>> verifyTwoFactorSetup(@Valid @RequestBody VerifyTotpRequest request) {
        Long actorId = currentActorService.getCurrentActorId();
        twoFactorService.verifyAndEnableTwoFactor(actorId, request.code());
        return ResponseEntity.ok(Map.of("message", "2FAが有効化されました。"));
    }

    @Operation(summary = "2FAを無効化", description = "ログイン中のユーザーの2FAを無効化します")
    @ApiResponse(responseCode = "200", description = "2FAが無効化されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @AuditLog(action = AuditLogAction.TWO_FACTOR_DISABLED, resourceType = "USER")
    @PostMapping("/totp/disable")
    public ResponseEntity<Map<String, String>> disableTwoFactor() {
        Long actorId = currentActorService.getCurrentActorId();
        twoFactorService.disableTwoFactor(actorId);
        return ResponseEntity.ok(Map.of("message", "2FAを無効化しました。"));
    }

    @Operation(summary = "TOTPコードを検証してログイン", description = "ログイン2段階目。パスワード認証後にTOTPコードで認証します")
    @ApiResponse(responseCode = "200", description = "ログイン成功、APIキーを返す")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正またはTOTPコードが無効")
    @PostMapping("/totp/verify")
    public LoginResponse verifyTotpLogin(@Valid @RequestBody TotpLoginVerifyRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));

        if (!twoFactorService.verifyTotpCode(user.getId(), request.code())) {
            throw new InvalidTotpCodeException("TOTPコードが無効です。");
        }

        String apiKey = apiKeyService.issue(user, request.label());
        return new LoginResponse(UserResponse.from(user), false, apiKey);
    }
}
