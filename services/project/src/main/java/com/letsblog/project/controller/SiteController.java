package com.letsblog.project.controller;

import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.WpCliInstallResult;
import com.letsblog.project.crypto.SshKeyGenerationService;
import com.letsblog.project.dto.AdoptWordPressSiteRequest;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteConnectionCheckResult;
import com.letsblog.project.dto.SiteDetailResponse;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.SiteUpdateRequest;
import com.letsblog.project.dto.SshKeyPairRequest;
import com.letsblog.project.dto.SshKeyPairResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ProvisioningService;
import com.letsblog.project.service.ProjectService;
import com.letsblog.project.service.SiteService;
import com.letsblog.project.service.WordPressSiteProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** WordPress サイト管理API(issue #577 stage2、legacy-apiから移設)。 */
@Tag(name = "Sites", description = "WordPress サイト管理API")
@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteService siteService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final WordPressSiteProvisioningService wordPressSiteProvisioningService;
    private final SshKeyGenerationService sshKeyGenerationService;
    private final ProjectService projectService;

    public SiteController(
            SiteService siteService,
            AdminAuthorizationService adminAuthorizationService,
            WordPressSiteProvisioningService wordPressSiteProvisioningService,
            SshKeyGenerationService sshKeyGenerationService,
            ProjectService projectService) {
        this.siteService = siteService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.wordPressSiteProvisioningService = wordPressSiteProvisioningService;
        this.sshKeyGenerationService = sshKeyGenerationService;
        this.projectService = projectService;
    }

    @Operation(summary = "WordPress サイトを登録", description = "既存のWordPressサイトを登録します")
    @ApiResponse(responseCode = "201", description = "サイトが登録されました")
    @PostMapping
    public ResponseEntity<SiteResponse> register(@Valid @RequestBody SiteRegisterRequest request) {
        // 同じコントローラのupdate/delete/getDetailと揃えてadmin限定にする(issue #830)。
        // サイト登録はCMS認証情報の登録を伴うため、認証済みなら誰でも、では通せない。
        adminAuthorizationService.requireAdmin();
        return ResponseEntity.status(HttpStatus.CREATED).body(siteService.register(request));
    }

    @Operation(summary = "マネージドWordPressサイトを作成", description = "新しいマネージドWordPressサイトを作成します")
    @ApiResponse(responseCode = "201", description = "サイトが作成されました")
    @PostMapping("/managed-wordpress")
    public ResponseEntity<SiteResponse> createManagedWordPress(
            @Valid @RequestBody CreateManagedWordPressSiteRequest request) {
        // WordPressコンテナの新規構築(インフラの払い出し)を伴うためadmin限定(issue #830)。
        adminAuthorizationService.requireAdmin();
        SiteResponse response = wordPressSiteProvisioningService.createManagedSite(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "既存のマネージドWordPressサイトを取り込む",
            description = "DBには未登録だがWordPress環境としては既に構築済みのサイトを取り込んで登録します")
    @ApiResponse(responseCode = "201", description = "サイトが取り込まれました")
    @PostMapping("/managed-wordpress/adopt")
    public ResponseEntity<SiteResponse> adoptManagedWordPress(
            @Valid @RequestBody AdoptWordPressSiteRequest request) {
        // 既存WordPress環境の取り込み。createManagedWordPressと同じ影響範囲なのでadmin限定(issue #830)。
        adminAuthorizationService.requireAdmin();
        SiteResponse response = wordPressSiteProvisioningService.adoptManagedSite(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "登録済みのサイト一覧を取得", description = "登録済みのすべてのWordPressサイトを取得します")
    /**
     * サイト一覧。<b>操作者が所属するプロジェクトに紐付くサイトだけ</b>を返す(issue #830)。
     * admin は全件。以前は認可チェックが無く、認証済みなら誰でも全サイトを列挙できた。
     *
     * <p>VSCode拡張がサイト選択に使うため admin 限定にはできない(#830 の初回対応時の判断)。
     * 所属プロジェクトのID一覧を legacy-api の内部ブリッジからまとめて引き、
     * そこに紐付くサイトIDへ絞る(サイトごとの逆引きは N+1 になるため)。
     */
    @GetMapping
    public List<SiteResponse> list(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        List<SiteResponse> all = siteService.list(sortBy, sortOrder);
        return adminAuthorizationService.accessibleProjectIds()
                .map(projectIds -> {
                    Set<Long> siteIds = projectService.siteIdsOfProjects(projectIds);
                    return all.stream().filter(site -> siteIds.contains(site.id())).toList();
                })
                .orElse(all);
    }

    @Operation(summary = "サイトの詳細情報を取得", description = "指定されたサイトの詳細情報を取得します")
    @GetMapping("/{id}")
    public SiteDetailResponse getDetail(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return siteService.getDetail(id);
    }

    @Operation(summary = "SSH キーペアを生成", description = "SSH接続用のEd25519キーペアを生成します")
    @PostMapping("/ssh-keypair")
    public SshKeyPairResponse generateSshKeyPair(@RequestBody(required = false) SshKeyPairRequest request) {
        adminAuthorizationService.requireAdmin();
        String comment = (request != null && request.comment() != null) ? request.comment() : "letsblog";
        return SshKeyPairResponse.from(sshKeyGenerationService.generateEd25519(comment));
    }

    @Operation(summary = "サイト情報を更新", description = "指定されたサイトの情報を更新します")
    @PutMapping("/{id}")
    public SiteResponse update(
            @Parameter(description = "サイトID") @PathVariable Long id,
            @RequestBody SiteUpdateRequest request) {
        adminAuthorizationService.requireAdmin();
        return siteService.update(id, request);
    }

    @Operation(summary = "サイト接続をテスト", description = "WordPressサイトへの接続をテストします")
    @PostMapping("/{id}/test-connection")
    public Map<String, Object> testConnection(@Parameter(description = "サイトID") @PathVariable Long id) {
        // 保存済みのCMS認証情報を使って外部へ接続し、admin権限の有無まで返す。
        // getDetailがadmin限定である以上、その材料になる本エンドポイントも揃える(issue #830)。
        adminAuthorizationService.requireAdmin();
        SiteConnectionCheckResult result = siteService.checkConnection(id);
        Map<String, Object> response = new HashMap<>();
        response.put("connectionCheckStatus", result.connectionOk() ? "SUCCESS" : "FAILED");
        response.put("hasAdminCapability", result.hasAdminCapability());
        response.put("failureReason", result.failureReason());
        response.put("detail", result.detail());
        return response;
    }

    @Operation(summary = "サイトを削除", description = "指定されたサイトを削除します")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        wordPressSiteProvisioningService.deleteSite(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "WP-CLI をインストール", description = "サイトにWP-CLIをインストールします")
    @PostMapping("/{id}/install-wp-cli")
    public WpCliInstallResult installWpCli(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return siteService.installWpCli(id);
    }

    @Operation(summary = "letsblog プラグインの導入状態を取得",
            description = "wp-cliの`wp letsblog status`で、導入済み(バージョン) / 未導入 / 要更新 を判定します")
    @GetMapping("/{id}/letsblog-plugin")
    public LetsblogPluginStatus letsblogPluginStatus(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return siteService.getLetsblogPluginStatus(id);
    }

    @Operation(summary = "letsblog プラグインを再導入", description = "プラグインを配置し直して有効化し、導入後の状態を返します")
    @PostMapping("/{id}/letsblog-plugin/install")
    public LetsblogPluginStatus installLetsblogPlugin(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return siteService.installLetsblogPlugin(id);
    }

    @Operation(summary = "サイトを再プロビジョニング", description = "サイトのカテゴリ・タグ・著者情報を再設定します")
    @PostMapping("/{id}/reprovision")
    public ResponseEntity<Map<String, String>> reprovision(@Parameter(description = "サイトID") @PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        ProvisioningService.Result result = siteService.reprovision(id);

        String message = "プロビジョニングを再実行しました。カテゴリ: "
                + (result.defaultCategoryId != null ? result.defaultCategoryId : "失敗")
                + " / タグ: " + (result.defaultTagId != null ? result.defaultTagId : "失敗")
                + " / 著者: " + (result.authorId != null ? result.authorId : "未対応または失敗");
        return ResponseEntity.ok(Map.of("message", message));
    }
}
