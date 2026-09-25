package com.letsblog.ai.client;

import com.letsblog.ai.dto.CategoryOption;
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
 * publishing-serviceの内部ブリッジ{@code /api/internal/ai/projects/{projectId}/existing-*}
 * ({@code com.letsblog.publishing.controller.AiExistingTaxonomyBridgeController}）を呼び出すクライアント。
 *
 * <p>ArticlePlanServiceの「マスター環境サイトの既存カテゴリ/タグ取得」は、当初{@link IdentityBridgeClient}
 * 経由でlegacy-apiのAiBridgeControllerへ問い合わせていた(issue #574)が、{@code CmsAdapterFactory}/
 * {@code cms/*}パッケージの所有権がpublishing-serviceへ移った(issue #707)のに伴い、この3メソッドのみを
 * publishing-service向けの本クライアントへ分離した(issue #711、Epic #551 C6-5)。GitHubトークン解決・
 * プロジェクトメンバー判定・Brave Search APIキー・LLM接続設定の4メソッドは引き続き
 * {@link IdentityBridgeClient}経由でlegacy-apiを参照する(変更なし)。
 *
 * <p>認証は他の内部ブリッジ(IdentityBridgeClient等)と同じ暫定策(呼び出し元のBearerトークンを
 * そのまま転送する)。取得失敗時は例外を投げずに空リストへフォールバックする方針も移管元と同じ
 * (カテゴリ/タグ提示はメタデータ提案の主目的ではなく補助情報のため)。
 */
@Component
public class PublishingServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    public PublishingServiceClient(
            RestClient.Builder builder, @Value("${app.publishing-service-uri}") String publishingServiceUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(publishingServiceUri).requestFactory(requestFactory).build();
    }

    /** プロジェクトのマスター環境サイトに既に存在するカテゴリ名一覧。取得失敗時は空リスト。 */
    public List<String> listExistingCategories(Long projectId, String bearerToken) {
        return getListSafely("/api/internal/ai/projects/{projectId}/existing-categories", projectId, bearerToken,
                String[].class);
    }

    /** 親カテゴリ名付きの既存カテゴリ一覧(issue #289)。取得失敗時は空リスト。 */
    public List<CategoryOption> listExistingCategoriesWithParents(Long projectId, String bearerToken) {
        return getListSafely(
                "/api/internal/ai/projects/{projectId}/existing-categories-with-parents", projectId, bearerToken,
                CategoryOption[].class);
    }

    /** プロジェクトのマスター環境サイトに既に存在するタグ名一覧(issue #525)。取得失敗時は空リスト。 */
    public List<String> listExistingTags(Long projectId, String bearerToken) {
        return getListSafely("/api/internal/ai/projects/{projectId}/existing-tags", projectId, bearerToken,
                String[].class);
    }

    private <T> List<T> getListSafely(String uriTemplate, Long projectId, String bearerToken, Class<T[]> arrayType) {
        try {
            T[] result = restClient.get()
                    .uri(uriTemplate, projectId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(arrayType);
            return result == null ? List.of() : List.of(result);
        } catch (RestClientException e) {
            return List.of();
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
