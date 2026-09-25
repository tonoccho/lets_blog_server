package com.letsblog.content.client;

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
 * project-serviceが所有するドメイン({@code projects}・{@code sites}・{@code tag_design_settings})
 * への内部ブリッジ。
 *
 * <p>issue #583以前、content-serviceはこれらを legacy-api の {@code ContentBridgeController}
 * ({@code /api/internal/content/**})経由で取得していたが、その実装は<b>project-serviceへの
 * 素通し</b>になっていた(所有権は#577 stage1/stage2で既に移っていたため)。
 * legacy-api の解体にあたり、その中継を外して project-service を直接呼ぶようにした。
 *
 * <p>{@code accessibleSiteIds}だけは素通しではなく、{@code project_users}(identity-service)と
 * {@code projects}/{@code sites}(project-service)をまたぐ集約なので、
 * project-service側に同等の実装を置いてそちらへ向けている。
 *
 * <p>認証は呼び出し元ユーザーのBearerトークンをそのまま転送する。
 */
@Component
public class ProjectBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public ProjectBridgeClient(
            RestClient.Builder builder, @Value("${app.project-service-uri}") String projectServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(projectServiceUri).requestFactory(requestFactory).build();
    }

    public record TagDesignResponse(
            String backgroundColor, String textColor, String accentColor, String customCss, String htmlTemplate) {
    }

    /**
     * [toc]/[blogcard]/[amazon]組み込みタグのデザイン(色+カスタムHTMLテンプレート)。
     * 色とテンプレートは常にセットで必要になるレンダリング経路が大半のため1回で取得する。
     * tagTypeは"TOC"/"BLOGCARD"/"AMAZON"({@code EmbedTagType#name()})。
     *
     * <p>projectIdはnull可(プロジェクトに紐付いていないサイトへの公開、issue #760)。
     */
    public TagDesignResponse resolveTagDesign(Long projectId, String tagType, String bearerToken) {
        try {
            TagDesignResponse result = restClient.get()
                    .uri("/api/internal/project/tag-design/{tagType}?projectId={projectId}", tagType, projectId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(TagDesignResponse.class);
            if (result == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "project-serviceのタグデザイン設定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** {@link TagDesignResponse}のうち色のみを{@link TagDesignColors}へ変換するヘルパー。 */
    public TagDesignColors toColors(TagDesignResponse response) {
        return new TagDesignColors(
                response.backgroundColor(), response.textColor(), response.accentColor(), response.customCss());
    }

    public record ProjectSummary(Long id, String name, String slug) {
    }

    /**
     * CustomTagService/RenderedContentWrapperServiceのcssSelectorPrefix解決フォールバック用、
     * プロジェクトのslug({@code project_content_settings}に未設定の場合のみ使う)。
     */
    public String resolveProjectSlug(Long projectId, String bearerToken) {
        try {
            ProjectSummary result = restClient.get()
                    .uri("/api/internal/project/projects/{projectId}", projectId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(ProjectSummary.class);
            return result == null ? null : result.slug();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "project-serviceのプロジェクト照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** PostController#lookupBySlugが使う、siteKey→siteId解決。未登録のsiteKeyの場合はnullを返す。 */
    public Long resolveSiteIdByKey(String siteKey, String bearerToken) {
        try {
            SiteSummary result = restClient.get()
                    .uri("/api/internal/project/sites/by-key/{siteKey}", siteKey)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(SiteSummary.class);
            return result == null ? null : result.id();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "project-serviceのsiteKey解決呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** PostController#listが使う、全サイトのid→name/siteKeyの一覧(投稿履歴一覧のサイト名表示用)。 */
    public List<SiteSummary> listSites(String bearerToken) {
        try {
            SiteSummary[] result = restClient.get()
                    .uri("/api/internal/project/sites")
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(SiteSummary[].class);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "project-serviceのサイト一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 操作者がアクセスできるサイトのID一覧(issue #830)。
     *
     * <p>{@code PostController#list} / {@code #lookupBySlug}が「自分が所属するプロジェクトの
     * サイトの投稿だけ」に絞るために使う。{@code project_users}(identity-service)と
     * {@code projects}/{@code sites}(project-service)をまたぐため、project-service側で解決する。
     */
    public List<Long> accessibleSiteIds(Long userId, String bearerToken) {
        try {
            Long[] result = restClient.get()
                    .uri("/api/internal/project/users/{userId}/site-ids", userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(Long[].class);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "project-serviceのアクセス可能サイト一覧呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** サイトの基本情報。project-serviceの{@code SiteBridgeResponse}のうち必要な3項目だけを受ける。 */
    public record SiteSummary(Long id, String siteKey, String name) {
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
