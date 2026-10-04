package com.letsblog.project.client;

import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.cms.DatabaseExport;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.LetsblogSyncResult;
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
 * publishing-serviceの内部CMSブリッジ{@code /api/internal/project/cms/**}
 * ({@code com.letsblog.publishing.controller.CmsProvisioningBridgeController})を呼び出すクライアント
 * (issue #577 stage2でlegacy-api向けに新設、issue #710でpublishing-serviceへ呼び出し先を切り替え、
 * Epic #551 C6-4)。
 *
 * <p>WordPressへの実際の接続処理(SSH/wp-cliエージェント経由、{@code CmsAdapter}/{@code WordPressSshOperations})は
 * publishing-service側に集約されたまま(media-service(#573→#709)のCmsBridgeClientと同じ方針)。
 * project-serviceは復号したその場限りの認証情報(SSH秘密鍵等を含む)をこの呼び出しの間だけ送り、
 * publishing-service側では永続化しない。認証は他の内部ブリッジ(IdentityBridgeClient等)と同じ暫定策
 * (呼び出し元ユーザーのBearerトークンをそのまま転送する)。
 *
 * <p>SSH接続・wp-cli実行・DB/メディア/テーマのエクスポートは数十秒かかりうるため、
 * {@code SyncServiceClient}のプロファイル(SHORT/STANDARD/RENDER/LLM、docs/SYNC_SERVICE_CALLS.md参照)
 * のうち最も長いRENDER(30秒)でも足りない可能性がある。issue #710のスコープでは
 * {@code SyncServiceClient}への移行は必須要件ではない(移行後の挙動変化のリスクを避けるため見送り)
 * ため、移管元と同じ固定タイムアウト(接続3秒・読み取り60秒)のRestClientをそのまま維持する。
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
            RestClient.Builder builder, @Value("${app.publishing-service-uri}") String publishingServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(publishingServiceUri).requestFactory(requestFactory).build();
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
            return result != null ? result : ConnectionCheckResult.failure("publishing-serviceから空の応答を受け取りました");
        } catch (RestClientException e) {
            return ConnectionCheckResult.failure(e.getMessage());
        }
    }

    public WpCliInstallResult installWpCli(String cmsType, Map<String, String> credentials) {
        return post("/api/internal/project/cms/install-wp-cli", credentialsBody(cmsType, credentials), WpCliInstallResult.class);
    }

    public LetsblogPluginStatus letsblogPluginStatus(String cmsType, Map<String, String> credentials) {
        return post("/api/internal/project/cms/letsblog-plugin-status", credentialsBody(cmsType, credentials),
                LetsblogPluginStatus.class);
    }

    public LetsblogPluginStatus installLetsblogPlugin(String cmsType, Map<String, String> credentials) {
        return post("/api/internal/project/cms/install-letsblog-plugin", credentialsBody(cmsType, credentials),
                LetsblogPluginStatus.class);
    }

    /**
     * タグ定義・統合CSS等をletsblogプラグインへ送る(issue #1558)。送信は wp-cli だけで行う。
     * プラグインが保存した内容のハッシュを返す。
     */
    public LetsblogSyncResult syncLetsblogPlugin(
            String cmsType, Map<String, String> credentials, String payload, String hash) {
        Map<String, Object> body = credentialsBody(cmsType, credentials);
        body.put("payload", payload);
        body.put("hash", hash);
        return post("/api/internal/project/cms/sync-letsblog-plugin", body, LetsblogSyncResult.class);
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
                throw new CmsBridgeException("publishing-serviceから空の応答を受け取りました(" + uri + ")", null);
            }
            return result;
        } catch (RestClientException e) {
            throw new CmsBridgeException("publishing-serviceの" + uri + "呼び出しに失敗しました: " + e.getMessage(), e);
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
            throw new CmsBridgeException("publishing-serviceの" + uri + "呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private void setAuthorization(HttpHeaders headers) {
        // 非同期の同期処理(issue #1558)には現在のリクエストが無いため、取り置いたトークンを優先する。
        String bearerToken = BearerScope.current() != null
                ? BearerScope.current() : request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
