package com.letsblog.project.client;

import com.letsblog.project.service.PlatformServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * platform-serviceの内部ブリッジ{@code /api/internal/platform/**}を呼ぶクライアント(issue #1858)。
 *
 * <p>GitLabのベースURL(システム設定{@code gitlab_base_url}、#1796)はplatform-serviceだけが読める。
 * リクエストの処理中は呼び出し元のBearerを転送し、リクエストの無いスレッドではClient Credentialsを使う
 * ({@link OutboundAuthHeaders})。読めないとき(不達・タイムアウト・2xx以外・空の応答)は
 * {@link PlatformServiceUnavailableException}を投げ、GlobalExceptionHandlerが503にする。
 */
@Component
public class PlatformServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final OutboundAuthHeaders authHeaders;

    public PlatformServiceClient(
            RestClient.Builder builder, @Value("${app.platform-service-uri}") String platformServiceUri,
            OutboundAuthHeaders authHeaders) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(platformServiceUri).requestFactory(requestFactory).build();
        this.authHeaders = authHeaders;
    }

    /** 未設定のときは{@code baseUrl=null}・{@code configured=false}。 */
    public record GitLabConfig(String baseUrl, boolean configured) {
    }

    public GitLabConfig gitLabConfig() {
        try {
            GitLabConfig config = restClient.get()
                    .uri("/api/internal/platform/gitlab-config")
                    .headers(authHeaders.current())
                    .retrieve()
                    .body(GitLabConfig.class);
            if (config == null) {
                throw new PlatformServiceUnavailableException("platform-serviceから空の応答を受け取りました", null);
            }
            return config;
        } catch (RestClientException e) {
            throw new PlatformServiceUnavailableException(
                    "platform-serviceのgitlab-config呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }
}
