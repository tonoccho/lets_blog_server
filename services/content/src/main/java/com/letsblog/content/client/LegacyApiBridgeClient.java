package com.letsblog.content.client;

import com.letsblog.content.dto.RoleOptionResponse;
import com.letsblog.content.dto.TagDesignColors;
import com.letsblog.content.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * まだlegacy-apiに残るドメイン(Project/Site、project_user、roles、tag_design_settings)へ
 * アクセスするための内部ブリッジ(issue #576)。
 *
 * <p>project-service(project_user含む)/tag_design_settings(未抽出の別ドメイン)は、Phase 19の
 * 他Issueがまだ完了していないため引き続きlegacy-apiに残る。CustomTagService(統合CSSバンドルの
 * cssSelectorPrefix解決・[toc]/[blogcard]/[amazon]組み込みタグのデザイン色/カスタムHTMLテンプレート)、
 * AdminAuthorizationService(プロジェクトメンバー判定)、MetadataController(ロール一覧)、
 * PostController(site key⇔id解決、サイト名一覧)は、いずれもこれらのデータに依存するため、
 * media-service(#573)のCmsBridgeClient/ai-service(#574)のLegacyApiBridgeClientと同じ暫定策
 * (呼び出し元のBearerトークンをそのまま転送する)でlegacy-apiの内部ブリッジエンドポイント
 * ({@code /api/internal/content/**}、legacy-api側のContentBridgeController)へ問い合わせる。
 */
@Component
public class LegacyApiBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public LegacyApiBridgeClient(RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(legacyApiUri).requestFactory(requestFactory).build();
    }

    /** AdminAuthorizationService#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
    public boolean isProjectMember(Long projectId, Long userId, String bearerToken) {
        try {
            Boolean result = restClient.get()
                    .uri("/api/internal/content/projects/{projectId}/members/{userId}", projectId, userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのプロジェクトメンバー判定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** MetadataController#rolesが使う、ロールの表示名一覧(特定の権限を要求しない、参照専用)。 */
    public List<RoleOptionResponse> listRoles(String bearerToken) {
        try {
            RoleOptionResponse[] result = restClient.get()
                    .uri("/api/internal/content/roles")
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(RoleOptionResponse[].class);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのロール一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record TagDesignResponse(
            String backgroundColor, String textColor, String accentColor, String customCss, String htmlTemplate) {
    }

    /**
     * [toc]/[blogcard]/[amazon]組み込みタグのデザイン(色+カスタムHTMLテンプレート)。
     * legacy-apiのTagDesignSettingService#resolveColors/#resolveHtmlTemplateを1回の呼び出しで
     * まとめて取得する(色とテンプレートは常にセットで必要になるレンダリング経路が大半のため)。
     * tagTypeは"TOC"/"BLOGCARD"/"AMAZON"(EmbedTagType#name())。
     */
    public TagDesignResponse resolveTagDesign(Long projectId, String tagType, String bearerToken) {
        try {
            TagDesignResponse result = restClient.get()
                    .uri("/api/internal/content/tag-design/{tagType}?projectId={projectId}", tagType, projectId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(TagDesignResponse.class);
            if (result == null) {
                throw new IllegalStateException("legacy-apiから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのタグデザイン設定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@link TagDesignResponse}のうち色のみを{@link TagDesignColors}へ変換するヘルパー。 */
    public TagDesignColors toColors(TagDesignResponse response) {
        return new TagDesignColors(
                response.backgroundColor(), response.textColor(), response.accentColor(), response.customCss());
    }

    /**
     * CustomTagService/RenderedContentWrapperServiceのcssSelectorPrefix解決フォールバック用、
     * プロジェクトのslug(legacy-apiのProject.slug、project_content_settingsに未設定の場合のみ使う)。
     */
    public String resolveProjectSlug(Long projectId, String bearerToken) {
        try {
            SlugResponse result = restClient.get()
                    .uri("/api/internal/content/projects/{projectId}/slug", projectId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(SlugResponse.class);
            return result == null ? null : result.slug();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのプロジェクトslug呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record SlugResponse(String slug) {
    }

    /** PostController#lookupBySlugが使う、siteKey→siteId解決。未登録のsiteKeyの場合はnullを返す。 */
    public Long resolveSiteIdByKey(String siteKey, String bearerToken) {
        try {
            SiteIdResponse result = restClient.get()
                    .uri("/api/internal/content/sites/by-key/{siteKey}", siteKey)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(SiteIdResponse.class);
            return result == null ? null : result.id();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのsiteKey解決呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record SiteIdResponse(Long id) {
    }

    /** PostController#listが使う、全サイトのid→name/siteKeyの一覧(投稿履歴一覧のサイト名表示用)。 */
    public List<SiteSummary> listSites(String bearerToken) {
        try {
            SiteSummary[] result = restClient.get()
                    .uri("/api/internal/content/sites")
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(SiteSummary[].class);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのサイト一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public record SiteSummary(Long id, String siteKey, String name) {
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
