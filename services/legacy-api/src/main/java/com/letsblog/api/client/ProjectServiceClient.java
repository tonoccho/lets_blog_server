package com.letsblog.api.client;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.service.ProjectNotFoundException;
import com.letsblog.api.service.SiteNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.stereotype.Component;

/**
 * Project/Site本体の所有権がproject-serviceへ移った(issue #577 stage2)ことに伴う内部ブリッジ。
 * ProjectUserSyncService/ArticlePreviewService/ContentBridgeController/AiBridgeController
 * (いずれもWordPress用CmsAdapter・SSH実行・wp-cliエージェント連携への深い依存のため#577では
 * 移設せずlegacy-apiに残る。BulkManagementService/TermComparisonService/
 * PluginThemeComparisonService/PostComparisonService/TaxonomyControllerは一括管理機能一式として
 * issue #708で、PostPublishService/PostDeleteServiceはissue #707で、CmsMediaBridgeControllerは
 * issue #709でpublishing-serviceへ移設した)は、
 * project-serviceの{@code /api/internal/project/**}経由でプロジェクト/サイトの
 * 基本情報・CMS認証情報を取得する({@link com.letsblog.api.service.ProjectService}/
 * {@link com.letsblog.api.service.SiteService}がこのクライアントを内部で使い、legacy-apiの
 * ローカルJPAエンティティ(旧{@code ProjectRepository}/{@code SiteRepository})を置き換える。
 * issue #577 stage3)。{@link com.letsblog.api.service.ProjectApiKeyService}/
 * {@link com.letsblog.api.service.AdSenseReportService}/{@link com.letsblog.api.service.GoogleAnalyticsReportService}/
 * {@code ImageModelService}/{@code ComfyUiModelService}(いずれも分析/AI資格情報ドメインのため#577
 * スコープ外)も、プロジェクトの存在確認・GitHubトークンの読み書きにこのクライアントを使う(stage3で、
 * これらが個別に持っていたローカル{@code ProjectRepository}参照を置き換えた。project-service側で
 * 新規作成されたプロジェクトはlegacy-apiのローカルprojectsテーブルに行を持たないため、ローカル
 * 参照のままでは全ての新規プロジェクトでNotFoundになっていた欠陥の修正でもある)。
 *
 * <p>認証は、project-service側の他の内部ブリッジ({@link com.letsblog.project.client.LegacyApiBridgeClient}等)
 * と同じ暫定策(呼び出し元のBearerトークンをそのまま転送する)。
 */
@Component
public class ProjectServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public ProjectServiceClient(
            RestClient.Builder builder, @Value("${app.project-service-uri}") String projectServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(projectServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public record ProjectBridge(
            Long id, String name, String slug, String masterEnvironment,
            Long localSiteId, Long testSiteId, Long productionSiteId, String githubRepository,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record SiteBridge(
            Long id, String siteKey, String name, String baseUrl, CmsType cmsType, boolean managedWordpress,
            String wpSlug, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record SiteCredentialsBridge(Long siteId, CmsType cmsType, Map<String, String> credentials) {
    }

    public record GithubTokenBridge(boolean configured, byte[] encryptedToken) {
    }

    private record SetGithubTokenRequest(byte[] encryptedToken) {
    }

    public record TagDesignBridge(
            String backgroundColor, String textColor, String accentColor, String customCss, String htmlTemplate) {
    }

    /** {@code ProjectService#getProjectEntity}が使う。未登録なら{@link ProjectNotFoundException}。 */
    public ProjectBridge getProject(Long projectId) {
        try {
            ProjectBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/projects/{projectId}", projectId))
                    .retrieve()
                    .body(ProjectBridge.class);
            if (result == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            throw new IllegalStateException(
                    "project-serviceのプロジェクト照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのプロジェクト照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@code SiteService#getById}が使う。未登録ならOptional.empty。 */
    public Optional<SiteBridge> getSite(Long siteId) {
        return getSite("/api/internal/project/sites/{id}", siteId);
    }

    /** {@code SiteService#findBySiteKeyOptional}が使う。未登録ならOptional.empty。 */
    public Optional<SiteBridge> getSiteByKey(String siteKey) {
        return getSite("/api/internal/project/sites/by-key/{siteKey}", siteKey);
    }

    private Optional<SiteBridge> getSite(String uri, Object pathVariable) {
        try {
            SiteBridge result = authorized(restClient.get().uri(uri, pathVariable))
                    .retrieve()
                    .body(SiteBridge.class);
            return Optional.ofNullable(result);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new IllegalStateException("project-serviceのサイト照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのサイト照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@code SiteService#listAll}が使う、全サイトの基本情報一覧(ContentBridgeController#sites向け)。 */
    public List<SiteBridge> listSites() {
        try {
            SiteBridge[] result = authorized(restClient.get().uri("/api/internal/project/sites"))
                    .retrieve()
                    .body(SiteBridge[].class);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのサイト一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@code ProjectService#findProjectIdBySiteId}が使う。未紐付けならnull。 */
    public Long findProjectIdBySiteId(Long siteId) {
        try {
            ProjectIdResponse result = authorized(restClient.get()
                    .uri("/api/internal/project/sites/{siteId}/project-id", siteId))
                    .retrieve()
                    .body(ProjectIdResponse.class);
            return result == null ? null : result.projectId();
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのプロジェクトID逆引き呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private record ProjectIdResponse(Long projectId) {
    }

    /** {@code SiteService#getCredentials}が使う。未登録なら{@link SiteNotFoundException}。 */
    public SiteCredentialsBridge getCredentials(String siteKey) {
        try {
            SiteCredentialsBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/sites/{siteKey}/credentials", siteKey))
                    .retrieve()
                    .body(SiteCredentialsBridge.class);
            if (result == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new SiteNotFoundException("siteKey '" + siteKey + "' は登録されていません");
            }
            throw new IllegalStateException(
                    "project-serviceのサイト認証情報照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのサイト認証情報照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@code ProjectApiKeyService}が使う。未登録なら{@link ProjectNotFoundException}。 */
    public GithubTokenBridge getGithubToken(Long projectId) {
        try {
            GithubTokenBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/projects/{projectId}/github-token", projectId))
                    .retrieve()
                    .body(GithubTokenBridge.class);
            if (result == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            throw new IllegalStateException(
                    "project-serviceのGitHubトークン照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのGitHubトークン照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@code ProjectApiKeyService}が使う。 */
    public void setGithubToken(Long projectId, byte[] encryptedToken) {
        try {
            authorized(restClient.put()
                    .uri("/api/internal/project/projects/{projectId}/github-token", projectId)
                    .body(new SetGithubTokenRequest(encryptedToken)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            throw new IllegalStateException(
                    "project-serviceのGitHubトークン更新呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのGitHubトークン更新呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@code ContentBridgeController#tagDesign}が使う。 */
    public TagDesignBridge getTagDesign(Long projectId, String tagType) {
        try {
            TagDesignBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/tag-design/{tagType}?projectId={projectId}", tagType, projectId))
                    .retrieve()
                    .body(TagDesignBridge.class);
            if (result == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのタグデザイン照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private RestClient.RequestHeadersSpec<?> authorized(RestClient.RequestHeadersSpec<?> spec) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        return spec;
    }

    private String bodyOrMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return (body != null && !body.isBlank()) ? body : e.getMessage();
    }
}
