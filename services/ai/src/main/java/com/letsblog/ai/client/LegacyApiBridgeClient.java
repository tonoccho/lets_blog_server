package com.letsblog.ai.client;

import com.letsblog.ai.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * ArticlePlanService/WebSearchServiceがまだlegacy-apiに残るドメイン(Project/Site、project_user、
 * system_settings)へアクセスするための内部ブリッジ(issue #574)。
 *
 * <p>project-service/content-service/platform-serviceはまだ未抽出(Phase 19の他Issue)のため、
 * ArticlePlanServiceの「GitHubトークン解決」「プロジェクトメンバー判定」、WebSearchServiceの
 * 「システム全体既定のBrave Search APIキー」は、引き続きlegacy-api側のデータ・ロジックに依存する。
 * media-service(#573)のCmsBridgeClient/GenerationJobClientと同じ暫定策(呼び出し元のBearerトークンを
 * そのまま転送する。legacy-api側の対応エンドポイント(AiBridgeController、{@code /api/internal/ai/**})は
 * CmsMediaBridgeControllerと同じ方針で追加の認可チェックを行わない
 * = 呼び出し元(ai-service)が既にrequireAdmin/requireProjectMemberOrAdmin等を済ませたリクエストの
 * トークンをそのまま転送してもらう想定)。
 *
 * <p>「マスター環境サイトの既存カテゴリ/タグ取得」の3メソッドは、{@code CmsAdapterFactory}/
 * {@code cms/*}パッケージの所有権がpublishing-serviceへ移った(issue #707)のに伴い、
 * {@link PublishingServiceClient}へ分離した(issue #711、Epic #551 C6-5)。
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

    public record GithubAccess(String token, String owner, String repo) {
    }

    /**
     * プロジェクトに紐づくGitHubアクセス情報(トークン・owner/repo)を解決する。
     * legacy-apiのArticlePlanService#resolveGithubAccessと同じロジック
     * (プロジェクト自身のトークン優先、無ければ操作者本人のユーザー設定へフォールバック)を
     * legacy-api側で実行する(Project/ProjectApiKeyService/UserServiceがlegacy-apiに残るため)。
     */
    public GithubAccess resolveGithubAccess(Long projectId, Long actorUserId, String bearerToken) {
        try {
            GithubAccess access = restClient.get()
                    .uri("/api/internal/ai/projects/{projectId}/github-access?actorUserId={actorUserId}",
                            projectId, actorUserId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(GithubAccess.class);
            if (access == null) {
                throw new IllegalStateException("legacy-apiから空の応答を受け取りました");
            }
            return access;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのgithub-access呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** 操作者(userId)がプロジェクトのメンバーかどうかを判定する(AdminAuthorizationServiceが使う)。 */
    public boolean isProjectMember(Long projectId, Long userId, String bearerToken) {
        try {
            Boolean result = restClient.get()
                    .uri("/api/internal/ai/projects/{projectId}/members/{userId}", projectId, userId)
                    .headers(headers -> setAuthorization(headers, bearerToken))
                    .retrieve()
                    .body(Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException(
                    "legacy-apiのプロジェクトメンバー判定呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }

    private String bodyOrMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return (body != null && !body.isBlank()) ? body : e.getMessage();
    }
}
