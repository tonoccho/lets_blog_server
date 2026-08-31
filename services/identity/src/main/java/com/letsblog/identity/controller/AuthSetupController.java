package com.letsblog.identity.controller;

import com.letsblog.identity.dto.SignupRequest;
import com.letsblog.identity.dto.UserResponse;
import com.letsblog.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web管理画面の初回セットアップ導線。issue #583でlegacy-apiの{@code AuthController}から移設した
 * (users/rolesの所有者であるidentity-serviceが持つのが自然なため)。
 *
 * <p>ログイン・2FA・パスワードリセットはissue #566でKeycloakへ全面移行し撤去済み(旧実装はgit history参照)。
 * ここに残るのは、Keycloak上にまだアカウントが1つも存在しない状態からの初回管理者作成専用の2本のみで、
 * issue #681でKeycloak Admin REST API経由の実装に置き換えてある
 * ({@link UserService#setupInitialAdmin}参照。作成されたアカウントはNextAuth Keycloakプロバイダ経由で
 * 即座にログインできる)。
 *
 * <p><b>この2本は移設先でも公開パスである</b>(ADR-0008 / issue #713)。
 * {@code SecurityConfig}の{@code PUBLIC_PATHS}に{@code /api/auth/setup-status}・{@code /api/auth/setup}
 * を含めてある。まだ誰もログインできない状態で叩くため、認証を必須にすると初回セットアップ自体が
 * 不可能になる。
 */
@Tag(name = "Authentication", description = "初回セットアップ関連API")
@RestController
@RequestMapping("/api/auth")
public class AuthSetupController {

    private final UserService userService;

    public AuthSetupController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 認可不要: 初回セットアップが必要かどうかを返す導線で、SecurityConfig の PUBLIC_PATHS に含まれる
     * <b>認証前に叩かれる公開パス</b>(issue #830)。まだ誰もログインできない状態で使うため、
     * 認可を掛けると初回セットアップ自体が不可能になる。
     */
    @Operation(summary = "セットアップ状態を確認", description = "システムのセットアップが必要かどうかを確認します")
    @ApiResponse(responseCode = "200", description = "セットアップ状態を返す")
    @GetMapping("/setup-status")
    public Map<String, Boolean> setupStatus() {
        return Map.of("needsSetup", !userService.hasAnyUser());
    }

    /**
     * 認可不要: 初回セットアップで最初のadminを作る導線で、SecurityConfig の PUBLIC_PATHS に含まれる
     * <b>認証前に叩かれる公開パス</b>(issue #830)。まだ誰もログインできない状態で使うため、
     * 認可を掛けると初回セットアップ自体が不可能になる。
     */
    @Operation(summary = "初期管理者をセットアップ", description = "最初の管理者ユーザーをセットアップします")
    @ApiResponse(responseCode = "200", description = "管理者がセットアップされました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @PostMapping("/setup")
    public UserResponse setup(@Valid @RequestBody SignupRequest request) {
        return userService.setupInitialAdmin(request.email(), request.password());
    }
}
