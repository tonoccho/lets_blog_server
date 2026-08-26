package com.letsblog.project.client;

import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.cms.DatabaseExport;
import com.letsblog.project.cms.ProvisioningResult;
import com.letsblog.project.cms.WpCliInstallResult;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * legacy-apiの内部CMSブリッジ{@code /api/internal/project/cms/**}
 * ({@code com.letsblog.api.controller.CmsProvisioningBridgeController})を呼び出すクライアント(issue #577 stage2)。
 *
 * <p>WordPressへの実際の接続処理(SSH/wp-cliエージェント経由、{@code CmsAdapter}/{@code WordPressSshOperations})は
 * まだ移設せずlegacy-apiに残る(media-service(#573)のCmsBridgeClientと同じ方針)。project-serviceは
 * 復号したその場限りの認証情報(SSH秘密鍵等を含む)をこの呼び出しの間だけ送り、legacy-api側では
 * 永続化しない。認証は他の内部ブリッジ(LegacyApiBridgeClient等)と同じ暫定策
 * (呼び出し元ユーザーのBearerトークンをそのまま転送する)。
 */
@Component
public class CmsProvisioningBridgeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // SSH接続・wp-cli実行・DB/メディア/テーマのエクスポートは数十秒かかりうるため、通常の内部ブリッジ
    // より長めの読み取りタイムアウトにする(WordPressProvisioningClient/WordPressSyncClientと同水準)。
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public CmsProvisioningBridgeClient(
            RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(legacyApiUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public ConnectionCheckResult testConnection(String cmsType, Map<String, String> credentials) {
        try {
            ConnectionCheckResult result = restClient.post()
                    .uri("/api/internal/project/cms/test-connection")
                    .headers(this::setAuthorization)
                    .body(credentialsBody(cmsType, credentials))
                    .retrieve()
                    .body(ConnectionCheckResult.class);
            return result != null ? result : ConnectionCheckResult.failure("legacy-apiから空の応答を受け取りました");
        } catch (RestClientException e) {
            return ConnectionCheckResult.failure(e.getMessage());
        }
    }

    public WpCliInstallResult installWpCli(String cmsType, Map<String, String> credentials) {
        return post("/api/internal/project/cms/install-wp-cli", credentialsBody(cmsType, credentials), WpCliInstallResult.class);
    }

    public boolean hasAuthorProvisioningCapability(String cmsType, Map<String, String> credentials) {
        Boolean result = post(
                "/api/internal/project/cms/has-author-capability", credentialsBody(cmsType, credentials), Boolean.class);
        return Boolean.TRUE.equals(result);
    }

    public ProvisioningResult provision(String cmsType, Map<String, String> credentials, String actorEmail) {
        Map<String, Object> body = credentialsBody(cmsType, credentials);
        body.put("actorEmail", actorEmail);
        return post("/api/internal/project/cms/provision", body, ProvisioningResult.class);
    }

    @SuppressWarnings("unchecked")
    public java.util.List<String> listActivePlugins(Map<String, String> credentials) {
        return post(
                "/api/internal/project/cms/list-active-plugins", credentialsBody("WORDPRESS", credentials),
                java.util.List.class);
    }

    public DatabaseExport exportDatabase(Map<String, String> credentials) {
        ExportDatabaseResponse response = post(
                "/api/internal/project/cms/export-database", credentialsBody("WORDPRESS", credentials),
                ExportDatabaseResponse.class);
        return new DatabaseExport(response.tablePrefix(), Base64.getDecoder().decode(response.dumpBase64()));
    }

    public byte[] exportMedia(Map<String, String> credentials) {
        return postForBytes("/api/internal/project/cms/export-media", credentialsBody("WORDPRESS", credentials));
    }

    public byte[] exportThemes(Map<String, String> credentials) {
        return postForBytes("/api/internal/project/cms/export-themes", credentialsBody("WORDPRESS", credentials));
    }

    private record ExportDatabaseResponse(String tablePrefix, String dumpBase64) {
    }

    private Map<String, Object> credentialsBody(String cmsType, Map<String, String> credentials) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cmsType", cmsType);
        body.put("credentials", credentials);
        return body;
    }

    private <T> T post(String uri, Object body, Class<T> responseType) {
        try {
            T result = restClient.post()
                    .uri(uri)
                    .headers(this::setAuthorization)
                    .body(body)
                    .retrieve()
                    .body(responseType);
            if (result == null) {
                throw new CmsBridgeException("legacy-apiから空の応答を受け取りました(" + uri + ")", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new CmsBridgeException("legacy-apiの" + uri + "呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private byte[] postForBytes(String uri, Object body) {
        try {
            byte[] result = restClient.post()
                    .uri(uri)
                    .headers(this::setAuthorization)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            return result != null ? result : new byte[0];
        } catch (RestClientException e) {
            throw new CmsBridgeException("legacy-apiの" + uri + "呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
