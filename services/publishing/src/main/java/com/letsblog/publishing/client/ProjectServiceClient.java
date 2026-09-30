package com.letsblog.publishing.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.service.ProjectNotFoundException;
import com.letsblog.publishing.service.SiteNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * project-serviceの{@code /api/internal/project/**}内部ブリッジを呼び出すクライアント(issue #707、
 * #575設計判断1・2の実装)。publishing-serviceはサイト・プロジェクトの本体情報やCMS認証情報を
 * 保持せず、公開/削除の都度project-serviceへ問い合わせる。project-service側のエンドポイント自体は
 * issue #577(project-service抽出)で既に実装済みのものをそのまま利用する
 * (SiteCredentialsInternalController等)。
 *
 * <p>認証は、他サービスの同種ブリッジ(project-serviceのIdentityBridgeClient等)と同じ暫定策として、
 * 呼び出し元のBearerトークンをそのまま転送する。
 */
@Component
public class ProjectServiceClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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

    public record SiteBridge(
            Long id, String siteKey, String name, String baseUrl, CmsType cmsType, boolean managedWordpress,
            String wpSlug) {
    }

    public record SiteCredentialsBridge(Long siteId, CmsType cmsType, Map<String, String> credentials) {

        /**
         * project-serviceが返す生のcredentialsマップ({@code SiteService#buildCredentialsFromMap}と
         * 同じキー)を、publishing-serviceのCmsAdapterがそのまま扱える{@link CmsCredentials}へ変換する。
         */
        public CmsCredentials toCmsCredentials() {
            return switch (cmsType) {
                case WORDPRESS -> new CmsCredentials.WordPressCredentials(
                        credentials.get("baseUrl"),
                        credentials.get("username"),
                        credentials.get("transport"),
                        credentials.get("sshHost"),
                        parseSshPort(credentials.get("sshPort")),
                        credentials.get("sshUser"),
                        credentials.get("wpPath"),
                        credentials.get("sshPrivateKeyPem"),
                        credentials.get("sshHostKeyFingerprint"),
                        credentials.get("wpSlug"));
            };
        }

        private static Integer parseSshPort(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public record ProjectBridge(
            Long id, Long localSiteId, Long testSiteId, Long productionSiteId, String masterEnvironment) {
    }

    /** project-serviceのgithub-accessブリッジが返す、GitHubのトークンとowner/repo(issue #1337)。 */
    public record GithubAccess(String token, String owner, String repo) {
    }

    /**
     * プロジェクトに紐づくGitHubアクセス情報を解決する(issue #1337)。トークンの解決規則
     * (プロジェクト自身のトークン優先、無ければ操作者本人の設定)はproject-service側が持ち、
     * ここでは呼ぶだけで新しい規則を作らない。リポジトリ・トークン未設定はproject-serviceが409で
     * 原因の分かる文面を返すので、その文面をそのまま{@link IllegalStateException}のメッセージにする
     * (publishing-serviceのGlobalExceptionHandlerが409として返す)。
     */
    public GithubAccess resolveGithubAccess(Long projectId, Long actorUserId) {
        try {
            GithubAccess result = authorized(restClient.get()
                    .uri("/api/internal/project/projects/{projectId}/github-access?actorUserId={actorUserId}",
                            projectId, actorUserId))
                    .retrieve()
                    .body(GithubAccess.class);
            if (result == null) {
                throw new IllegalStateException("project-serviceから空の応答を受け取りました");
            }
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new IllegalStateException(conflictMessage(e), e);
            }
            throw new IllegalStateException(
                    "project-serviceのgithub-access呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのgithub-access呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /** 409の本文({@code {"error":"..."}})から利用者向けの文面を取り出す。JSONでなければ本文をそのまま使う。 */
    private String conflictMessage(RestClientResponseException e) {
        String body = bodyOrMessage(e);
        try {
            JsonNode error = OBJECT_MAPPER.readTree(body).path("error");
            return error.isTextual() && !error.asText().isBlank() ? error.asText() : body;
        } catch (JsonProcessingException | RuntimeException parseFailure) {
            return body;
        }
    }

    /** PostPublishService/PostDeleteServiceが使う。未登録なら{@link SiteNotFoundException}。 */
    public SiteBridge getSiteByKey(String siteKey) {
        try {
            SiteBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/sites/by-key/{siteKey}", siteKey))
                    .retrieve()
                    .body(SiteBridge.class);
            if (result == null) {
                throw new SiteNotFoundException("siteKey '" + siteKey + "' は登録されていません");
            }
            return result;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new SiteNotFoundException("siteKey '" + siteKey + "' は登録されていません");
            }
            throw new IllegalStateException(
                    "project-serviceのサイト照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("project-serviceのサイト照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * id指定でサイトを取得する({@code SiteService#getById}が使う、issue #708)。未登録ならempty。
     */
    public Optional<SiteBridge> getSite(Long siteId) {
        try {
            SiteBridge result = authorized(restClient.get()
                    .uri("/api/internal/project/sites/{id}", siteId))
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

    /**
     * サイトのCMS認証情報を取得する({@code SiteCredentialsInternalController}、issue #577受入基準)。
     * 未登録なら{@link SiteNotFoundException}。
     */
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

    /** 指定サイトが所属するプロジェクトのIDを返す(いずれの環境にも紐付いていなければnull)。 */
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

    /** 予約投稿の可否判定(isProductionSite)向け。未登録なら{@link ProjectNotFoundException}。 */
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
