package com.letsblog.api.controller;

import com.letsblog.api.cms.WpCliInstallResult;
import com.letsblog.api.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.api.dto.SiteConnectionCheckResult;
import com.letsblog.api.dto.SiteDetailResponse;
import com.letsblog.api.dto.SiteRegisterRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.dto.SiteUpdateRequest;
import com.letsblog.api.dto.SshKeyPairRequest;
import com.letsblog.api.dto.SshKeyPairResponse;
import com.letsblog.api.crypto.SshKeyGenerationService;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ProvisioningService;
import com.letsblog.api.service.SiteService;
import com.letsblog.api.service.WordPressSiteProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Sites", description = "WordPress サイト管理API")
@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteService siteService;
    private final CurrentActorService currentActorService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final WordPressSiteProvisioningService wordPressSiteProvisioningService;
    private final SshKeyGenerationService sshKeyGenerationService;

    public SiteController(
            SiteService siteService,
            CurrentActorService currentActorService,
            AdminAuthorizationService adminAuthorizationService,
            WordPressSiteProvisioningService wordPressSiteProvisioningService,
            SshKeyGenerationService sshKeyGenerationService) {
        this.siteService = siteService;
        this.currentActorService = currentActorService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.wordPressSiteProvisioningService = wordPressSiteProvisioningService;
        this.sshKeyGenerationService = sshKeyGenerationService;
    }

    @Operation(summary = "WordPress サイトを登録", description = "既存のWordPressサイトを登録します")
    @ApiResponse(responseCode = "201", description = "サイトが登録されました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @PostMapping
    public ResponseEntity<SiteResponse> register(@Valid @RequestBody SiteRegisterRequest request) {
        Long actorId = currentActorService.getCurrentActorId();
        return ResponseEntity.status(HttpStatus.CREATED).body(siteService.register(request, actorId));
    }

    @Operation(summary = "マネージドWordPressサイトを作成", description = "新しいマネージドWordPressサイトを作成します")
    @ApiResponse(responseCode = "201", description = "サイトが作成されました")
    @ApiResponse(responseCode = "400", description = "リクエストボディが不正")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @PostMapping("/managed-wordpress")
    public ResponseEntity<SiteResponse> createManagedWordPress(
            @Valid @RequestBody CreateManagedWordPressSiteRequest request) {
        Long actorId = currentActorService.getCurrentActorId();
        SiteResponse response = wordPressSiteProvisioningService.createManagedSite(request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "登録済みのサイト一覧を取得", description = "登録済みのすべてのWordPressサイトを取得します")
    @ApiResponse(responseCode = "200", description = "サイト一覧を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @GetMapping
    public List<SiteResponse> list(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        return siteService.list(sortBy, sortOrder);
    }

    @Operation(summary = "サイトの詳細情報を取得", description = "指定されたサイトの詳細情報を取得します")
    @ApiResponse(responseCode = "200", description = "サイト詳細を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @ApiResponse(responseCode = "404", description = "サイトが見つかりません")
    @GetMapping("/{id}")
    public SiteDetailResponse getDetail(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return siteService.getDetail(id);
    }

    @Operation(summary = "SSH キーペアを生成", description = "SSH接続用のEd25519キーペアを生成します")
    @ApiResponse(responseCode = "200", description = "キーペアを返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @PostMapping("/ssh-keypair")
    public SshKeyPairResponse generateSshKeyPair(@RequestBody(required = false) SshKeyPairRequest request) {
        adminAuthorizationService.requireAdmin();
        String comment = (request != null && request.comment() != null) ? request.comment() : "letsblog";
        return SshKeyPairResponse.from(sshKeyGenerationService.generateEd25519(comment));
    }

    @Operation(summary = "サイト情報を更新", description = "指定されたサイトの情報を更新します")
    @ApiResponse(responseCode = "200", description = "サイト情報が更新されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @ApiResponse(responseCode = "404", description = "サイトが見つかりません")
    @PutMapping("/{id}")
    public SiteResponse update(
            @Parameter(description = "サイトID") @PathVariable Long id,
            @RequestBody SiteUpdateRequest request) {
        adminAuthorizationService.requireAdmin();
        return siteService.update(id, request);
    }

    @Operation(summary = "サイト接続をテスト", description = "WordPressサイトへの接続をテストします")
    @ApiResponse(responseCode = "200", description = "接続テスト結果を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "404", description = "サイトが見つかりません")
    @PostMapping("/{id}/test-connection")
    public Map<String, Object> testConnection(@Parameter(description = "サイトID") @PathVariable Long id) {
        SiteConnectionCheckResult result = siteService.checkConnection(id);
        Map<String, Object> response = new HashMap<>();
        response.put("connectionCheckStatus", result.connectionOk() ? "SUCCESS" : "FAILED");
        response.put("hasAdminCapability", result.hasAdminCapability());
        response.put("failureReason", result.failureReason());
        response.put("detail", result.detail());
        return response;
    }

    @Operation(summary = "サイトを削除", description = "指定されたサイトを削除します")
    @ApiResponse(responseCode = "204", description = "サイトが削除されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @ApiResponse(responseCode = "404", description = "サイトが見つかりません")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        wordPressSiteProvisioningService.deleteSite(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "WP-CLI をインストール", description = "サイトにWP-CLIをインストールします")
    @ApiResponse(responseCode = "200", description = "インストール結果を返す")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @ApiResponse(responseCode = "404", description = "サイトが見つかりません")
    @PostMapping("/{id}/install-wp-cli")
    public WpCliInstallResult installWpCli(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return siteService.installWpCli(id);
    }

    @Operation(summary = "サイトを再プロビジョニング", description = "サイトのカテゴリ・タグ・著者情報を再設定します")
    @ApiResponse(responseCode = "200", description = "再プロビジョニングが実行されました")
    @ApiResponse(responseCode = "401", description = "認証ヘッダが無効")
    @ApiResponse(responseCode = "403", description = "管理者権限がありません")
    @ApiResponse(responseCode = "404", description = "サイトが見つかりません")
    @PostMapping("/{id}/reprovision")
    public ResponseEntity<Map<String, String>> reprovision(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        Long actorId = currentActorService.getCurrentActorId();
        ProvisioningService.ProvisioningResult result = siteService.reprovision(id, actorId);

        String message = "プロビジョニングを再実行しました。カテゴリ: "
                + (result.defaultCategoryId != null ? result.defaultCategoryId : "失敗")
                + " / タグ: " + (result.defaultTagId != null ? result.defaultTagId : "失敗")
                + " / 著者: " + (result.authorId != null ? result.authorId : "未対応または失敗");
        return ResponseEntity.ok(Map.of("message", message));
    }
}
