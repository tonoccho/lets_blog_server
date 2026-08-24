package com.letsblog.api.controller;

import com.letsblog.api.ai.AiProvider;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.service.AppSettingService;
import com.letsblog.api.service.ProjectApiKeyService;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.SiteService;
import com.letsblog.api.service.SystemSettingService;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ai-service向けの内部ブリッジ(issue #574)。GithubClientの記事プラン向けissue連携で使う
 * GitHubトークン解決、マスター環境サイトの既存カテゴリ/タグ取得、プロジェクトメンバー判定、
 * システム全体既定のBrave Search APIキー、実効LLM接続設定は、いずれもProject/Site/CmsAdapter/
 * project_user/system_settings(いずれもproject-service/content-service/platform-serviceが
 * 未抽出のためlegacy-apiに残るドメイン)への依存が強いため、ai-service側で直接持たず、この
 * ブリッジ経由でlegacy-apiへ問い合わせる(media-service(#573)のCmsMediaBridgeControllerと同じ方針。
 * 認可は呼び出し元(ai-service)が既にrequireAdmin/requireProjectMemberOrAdmin等を済ませたリクエストの
 * Bearerトークンをそのまま転送してもらう想定で、ここでは追加の認可チェックは行わない)。
 */
@RestController
public class AiBridgeController {

    private final ProjectService projectService;
    private final ProjectApiKeyService projectApiKeyService;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final ProjectUserRepository projectUserRepository;
    private final SystemSettingService systemSettingService;
    private final AppSettingService appSettingService;

    public AiBridgeController(
            ProjectService projectService,
            ProjectApiKeyService projectApiKeyService,
            SiteService siteService,
            CmsAdapterFactory cmsAdapterFactory,
            ProjectUserRepository projectUserRepository,
            SystemSettingService systemSettingService,
            AppSettingService appSettingService) {
        this.projectService = projectService;
        this.projectApiKeyService = projectApiKeyService;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.projectUserRepository = projectUserRepository;
        this.systemSettingService = systemSettingService;
        this.appSettingService = appSettingService;
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

    /**
     * プロジェクトのマスター環境サイトに既に存在するカテゴリ名一覧。サイト未紐付け・非WordPress・
     * 取得失敗時は空リストを返す(元のArticlePlanService#listExistingCategoriesと同じフェイルオープン方針)。
     */
    @GetMapping("/api/internal/ai/projects/{projectId}/existing-categories")
    public List<String> existingCategories(@PathVariable Long projectId) {
        try {
            CmsAdapterAndCredentials resolved = resolveMasterSiteCmsAdapter(projectId);
            return resolved == null ? List.of() : resolved.adapter().listCategoryNames(resolved.credentials());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 親カテゴリ名付きの既存カテゴリ一覧(issue #289)。取得失敗時は空リスト。 */
    @GetMapping("/api/internal/ai/projects/{projectId}/existing-categories-with-parents")
    public List<CmsAdapter.CategoryOption> existingCategoriesWithParents(@PathVariable Long projectId) {
        try {
            CmsAdapterAndCredentials resolved = resolveMasterSiteCmsAdapter(projectId);
            return resolved == null ? List.of() : resolved.adapter().listCategoriesWithParents(resolved.credentials());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** プロジェクトのマスター環境サイトに既に存在するタグ名一覧(issue #525)。取得失敗時は空リスト。 */
    @GetMapping("/api/internal/ai/projects/{projectId}/existing-tags")
    public List<String> existingTags(@PathVariable Long projectId) {
        try {
            CmsAdapterAndCredentials resolved = resolveMasterSiteCmsAdapter(projectId);
            return resolved == null ? List.of() : resolved.adapter().listTagNames(resolved.credentials());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private record CmsAdapterAndCredentials(CmsAdapter adapter, CmsCredentials credentials) {
    }

    private CmsAdapterAndCredentials resolveMasterSiteCmsAdapter(Long projectId) {
        Project project = projectService.getProjectEntity(projectId);
        Site site = projectService.resolveMasterSite(project);
        if (site == null) {
            return null;
        }
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        return new CmsAdapterAndCredentials(cmsAdapter, credentials);
    }

    /** AdminAuthorizationService(ai-service)#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
    @GetMapping("/api/internal/ai/projects/{projectId}/members/{userId}")
    public boolean isProjectMember(@PathVariable Long projectId, @PathVariable Long userId) {
        return projectUserRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }

    public record SystemBraveSearchApiKeyResponse(String apiKey) {
    }

    /** WebSearchService(ai-service)のプロジェクト非依存フォールバック向け。未設定ならnull。 */
    @GetMapping("/api/internal/ai/system-settings/brave-search-api-key")
    public SystemBraveSearchApiKeyResponse systemBraveSearchApiKey() {
        String apiKey = systemSettingService.getBraveSearchApiKey();
        return new SystemBraveSearchApiKeyResponse(apiKey == null || apiKey.isBlank() ? null : apiKey);
    }

    public record LlmConfigResponse(
            String provider, String baseUrl, String apiKey, String defaultModel,
            List<String> availableModels, long requestTimeoutSeconds) {
    }

    /**
     * RemoteLlmConfigProvider(ai-service)が呼ぶ。providerを指定しなければシステム設定の既定
     * プロバイダーを使う(AppSettingServiceがLlmConfigProviderとして解決する値をそのまま返す)。
     */
    @GetMapping("/api/internal/ai/llm-config")
    public LlmConfigResponse llmConfig(@RequestParam(required = false) String provider) {
        AiProvider resolved = provider != null && !provider.isBlank()
                ? AiProvider.fromString(provider) : appSettingService.provider();
        return new LlmConfigResponse(
                resolved.name(),
                appSettingService.baseUrlFor(resolved),
                appSettingService.apiKeyFor(resolved),
                appSettingService.defaultModelFor(resolved),
                parseAvailableModels(appSettingService.getLlmAvailableModels()),
                appSettingService.requestTimeoutSeconds());
    }

    private List<String> parseAvailableModels(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
