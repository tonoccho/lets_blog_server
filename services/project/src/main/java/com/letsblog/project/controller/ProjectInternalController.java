package com.letsblog.project.controller;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.client.IdentityBridgeClient;
import com.letsblog.project.domain.Project;
import com.letsblog.project.dto.GithubTokenBridgeResponse;
import com.letsblog.project.dto.ProjectBridgeResponse;
import com.letsblog.project.dto.SetGithubTokenBridgeRequest;
import com.letsblog.project.dto.SiteBridgeResponse;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.service.CurrentActorService;
import com.letsblog.project.service.ProjectService;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiに残るドメイン(一括管理/カテゴリ・タグ・プラグイン・テーマ・投稿の環境間比較/投稿publish・
 * 記事プレビュー等、いずれもCMSアダプタ・SSH実行への深い依存のため#577では移設せずlegacy-apiに残る)が、
 * プロジェクト/サイトの基本情報(認証情報を除く)を参照するための内部API(issue #577 stage3)。
 * サイトのCMS認証情報自体は{@link SiteCredentialsInternalController}(既存、issue #577受入基準)で
 * 別途取得する。{@link SiteCredentialsInternalController}と同じ方針で、project-serviceのSecurityConfig
 * による{@code /api/internal/**}の認証必須以上の追加認可は行わない。
 */
@RestController
public class ProjectInternalController {

    private final ProjectService projectService;
    private final SiteRepository siteRepository;
    private final IdentityBridgeClient identityBridgeClient;
    private final CurrentActorService currentActorService;
    private final CredentialCipher credentialCipher;

    public ProjectInternalController(
            ProjectService projectService,
            SiteRepository siteRepository,
            IdentityBridgeClient identityBridgeClient,
            CurrentActorService currentActorService,
            CredentialCipher credentialCipher) {
        this.projectService = projectService;
        this.siteRepository = siteRepository;
        this.identityBridgeClient = identityBridgeClient;
        this.currentActorService = currentActorService;
        this.credentialCipher = credentialCipher;
    }

    @GetMapping("/api/internal/project/projects/{projectId}")
    public ProjectBridgeResponse project(@PathVariable Long projectId) {
        return ProjectBridgeResponse.from(projectService.getProjectEntity(projectId));
    }

    /** legacy-apiのProjectApiKeyServiceが使う、GitHubトークン(暗号化済みバイト列)の取得。 */
    @GetMapping("/api/internal/project/projects/{projectId}/github-token")
    public GithubTokenBridgeResponse githubToken(@PathVariable Long projectId) {
        byte[] encryptedToken = projectService.getGithubTokenEncrypted(projectId);
        return new GithubTokenBridgeResponse(encryptedToken != null && encryptedToken.length > 0, encryptedToken);
    }

    @PutMapping("/api/internal/project/projects/{projectId}/github-token")
    public void setGithubToken(@PathVariable Long projectId, @RequestBody SetGithubTokenBridgeRequest request) {
        projectService.setGithubTokenEncrypted(projectId, request.encryptedToken());
    }

    @DeleteMapping("/api/internal/project/projects/{projectId}/github-token")
    public void clearGithubToken(@PathVariable Long projectId) {
        projectService.setGithubTokenEncrypted(projectId, null);
    }

    public record ProjectIdResponse(Long projectId) {
    }

    /** legacy-apiのPostPublishService#publishが使う、siteId→所属projectIdの逆引き。未紐付けならprojectId=null。 */
    @GetMapping("/api/internal/project/sites/{siteId}/project-id")
    public ProjectIdResponse projectIdForSite(@PathVariable Long siteId) {
        return new ProjectIdResponse(projectService.findProjectIdBySiteId(siteId));
    }

    @GetMapping("/api/internal/project/sites/{siteId}")
    public ResponseEntity<SiteBridgeResponse> site(@PathVariable Long siteId) {
        return siteRepository.findById(siteId)
                .map(site -> ResponseEntity.ok(SiteBridgeResponse.from(site)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/internal/project/sites/by-key/{siteKey}")
    public ResponseEntity<SiteBridgeResponse> siteByKey(@PathVariable String siteKey) {
        return siteRepository.findBySiteKey(siteKey)
                .map(site -> ResponseEntity.ok(SiteBridgeResponse.from(site)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** legacy-apiのContentBridgeController#sitesが使う、全サイトの基本情報一覧(サイト名表示用)。 */
    @GetMapping("/api/internal/project/sites")
    public List<SiteBridgeResponse> sites() {
        return siteRepository.findAll().stream().map(SiteBridgeResponse::from).toList();
    }

    // ---- issue #583: legacy-api 解体で引き取ったブリッジ ----

    public record ProjectEligibilityResponse(boolean hasProductionSite) {
    }

    /**
     * 認可不要: gatewayのルート表に載っておらず外部から到達できない内部ブリッジで、呼び出し元の
     * サービスが既に認可を済ませている(issue #583)。
     *
     * <p>analytics-service の {@code GoogleAnalyticsReportService} / {@code AdSenseReportService} が
     * 使う、レポート取得可否判定用のプロジェクト情報。#583以前は legacy-api の
     * {@code AnalyticsBridgeController} が同じ値を返していた。
     * プロジェクトが存在しない場合は404になる({@code ProjectNotFoundException})。
     */
    @GetMapping("/api/internal/project/projects/{projectId}/eligibility")
    public ProjectEligibilityResponse projectEligibility(@PathVariable Long projectId) {
        return new ProjectEligibilityResponse(
                projectService.getProjectEntity(projectId).getProductionSiteId() != null);
    }

    /**
     * 認可不要: {@link #projectEligibility}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。
     *
     * <p>content-service の {@code PostController#list} / {@code #lookupBySlug} が
     * 「自分が所属するプロジェクトのサイトの投稿だけ」に絞るために使う(issue #830)。
     *
     * <p>{@code project_users} は identity-service、{@code projects}/{@code sites} は
     * project-service が所有するため、identity へ所属プロジェクトIDを問い合わせてから
     * こちらでサイトIDへ展開する。#583以前は両方を引けた legacy-api が解決していた。
     */
    @GetMapping("/api/internal/project/users/{userId}/site-ids")
    public List<Long> accessibleSiteIds(@PathVariable Long userId) {
        List<Long> projectIds =
                identityBridgeClient.projectIdsForUser(userId, currentActorService.getAuthorizationHeader());
        if (projectIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(projectService.siteIdsOfProjects(Set.copyOf(projectIds)));
    }

    public record GithubAccessResponse(String token, String owner, String repo) {
    }

    /**
     * 認可不要: {@link #projectEligibility}と同じ理由(gateway非経由・呼び出し元が認可済み、issue #583)。
     *
     * <p>ai-service の {@code ArticlePlanService#resolveGithubAccess} が使う。
     * プロジェクト自身のトークンを優先し、無ければ操作者本人のユーザー設定
     * (identity-service が所有)へフォールバックする。GitHubリポジトリ未設定時は
     * {@code IllegalStateException} を投げ、{@code GlobalExceptionHandler} が409として返す。
     * #583以前は legacy-api の {@code AiBridgeController} が同じロジックを持っていた。
     */
    @GetMapping("/api/internal/project/projects/{projectId}/github-access")
    public GithubAccessResponse githubAccess(@PathVariable Long projectId, @RequestParam Long actorUserId) {
        Project project = projectService.getProjectEntity(projectId);
        if (!project.isGithubRepositoryConfigured()) {
            throw new IllegalStateException(
                    "このプロジェクトにGitHubリポジトリが紐付けられていません。プロジェクト詳細ページから設定してください。");
        }
        byte[] projectToken = project.getGithubTokenEncrypted();
        String token;
        if (projectToken != null && projectToken.length > 0) {
            token = credentialCipher.decrypt(projectToken);
        } else {
            IdentityBridgeClient.UserGithubToken userToken =
                    identityBridgeClient.userGithubToken(actorUserId, currentActorService.getAuthorizationHeader());
            if (!userToken.configured()) {
                throw new IllegalStateException("ユーザーの GitHub トークンが設定されていません");
            }
            token = credentialCipher.decrypt(userToken.encryptedToken());
        }
        String[] repoParts = project.getGithubRepository().split("/", 2);
        return new GithubAccessResponse(token, repoParts[0], repoParts[1]);
    }
}
