package com.letsblog.api.controller;

import com.letsblog.api.dto.SignupRequest;
import com.letsblog.api.dto.UserResponse;
import com.letsblog.api.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ログイン・2FA・パスワードリセットはissue #566でKeycloakへ全面移行し撤去した
 * (旧実装はgit historyを参照)。ここに残るのは、Web管理画面の初回セットアップ(/setup)が使う
 * エンドポイントのみ。setupはKeycloak上にまだアカウントが1つも存在しない状態からの初回管理者
 * 作成専用であり、issue #681でKeycloak Admin REST API経由の実装に置き換えた
 * (UserService#setupInitialAdmin参照。作成されたアカウントはKeycloak経由のログイン
 * (NextAuth Keycloakプロバイダ)が即座に可能)。
 */
@Tag(name = "Authentication", description = "初回セットアップ関連API")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
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
}
