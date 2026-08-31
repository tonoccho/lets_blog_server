package com.letsblog.api.controller;

import com.letsblog.api.ai.AiProvider;
import com.letsblog.api.client.PlatformServiceClient;
import com.letsblog.api.domain.Project;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.service.ProjectApiKeyService;
import com.letsblog.api.service.ProjectService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ai-service向けの内部ブリッジ(issue #574)。GithubClientの記事プラン向けissue連携で使う
 * GitHubトークン解決、プロジェクトメンバー判定は、Project/project_user(project-service/
 * content-serviceが未抽出のためlegacy-apiに残るドメイン)への依存が強いため、ai-service側で
 * 直接持たず、このブリッジ経由でlegacy-apiへ問い合わせる(media-service(#573)の
 * CmsMediaBridgeControllerと同じ方針。認可は呼び出し元(ai-service)が既にrequireAdmin/
 * requireProjectMemberOrAdmin等を済ませたリクエストのBearerトークンをそのまま転送してもらう想定で、
 * ここでは追加の認可チェックは行わない)。
 *
 * <p>マスター環境サイトの既存カテゴリ/タグ取得の3エンドポイントは、{@code CmsAdapterFactory}/
 * {@code cms/*}パッケージの所有権がpublishing-serviceへ移った(issue #707)のに伴い、
 * publishing-serviceの{@code AiExistingTaxonomyBridgeController}へ移管した(issue #711、
 * Epic #551 C6-5)。ai-serviceは新規クライアント({@code PublishingServiceClient}）経由で
 * publishing-serviceへ直接問い合わせる。
 *
 * <p>システム全体既定のBrave Search APIキー・実効LLM接続設定は、system_settingsの所有権が
 * platform-serviceへ移った(issue #693)ため、本コントローラは{@link PlatformServiceClient}への
 * 単純委譲のみを行う(ai-serviceのLegacyApiBridgeClientが呼び出すパス自体は変えず、legacy-apiを
 * 経由するブリッジチェーンを維持する)。
 */
@RestController
public class AiBridgeController {

    private final ProjectService projectService;
    private final ProjectApiKeyService projectApiKeyService;
    private final ProjectUserRepository projectUserRepository;
    private final PlatformServiceClient platformServiceClient;

    public AiBridgeController(
            ProjectService projectService,
            ProjectApiKeyService projectApiKeyService,
            ProjectUserRepository projectUserRepository,
            PlatformServiceClient platformServiceClient) {
        this.projectService = projectService;
        this.projectApiKeyService = projectApiKeyService;
        this.projectUserRepository = projectUserRepository;
        this.platformServiceClient = platformServiceClient;
    }

    public record GithubAccessResponse(String token, String owner, String repo) {
    }

    /**
     * ArticlePlanService(ai-service)#resolveGithubAccessと同じロジック(プロジェクト自身のトークン
     * 優先、無ければ操作者本人のユーザー設定へフォールバック)。GitHubリポジトリ未設定時は
     * IllegalStateExceptionを投げ、GlobalExceptionHandlerが409として返す(元のロジックと同じ)。
     */
    @GetMapping("/api/internal/ai/projects/{projectId}/github-access")
    public GithubAccessResponse githubAccess(
            @PathVariable Long projectId, @RequestParam Long actorUserId) {
        Project project = projectService.getProjectEntity(projectId);
        if (!project.isGithubRepositoryConfigured()) {
            throw new IllegalStateException(
                    "このプロジェクトにGitHubリポジトリが紐付けられていません。プロジェクト詳細ページから設定してください。");
        }
        String token = projectApiKeyService.resolveGithubToken(projectId, actorUserId);
        String[] repoParts = project.getGithubRepository().split("/", 2);
        return new GithubAccessResponse(token, repoParts[0], repoParts[1]);
    }

    /** AdminAuthorizationService(ai-service)#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
    @GetMapping("/api/internal/ai/projects/{projectId}/members/{userId}")
    public boolean isProjectMember(@PathVariable Long projectId, @PathVariable Long userId) {
        return projectUserRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }

}
