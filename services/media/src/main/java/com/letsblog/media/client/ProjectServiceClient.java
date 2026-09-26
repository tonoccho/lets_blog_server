package com.letsblog.media.client;

import com.letsblog.media.service.ProjectNotFoundException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * project-serviceの{@code /api/internal/project/**}への内部ブリッジ(issue #583)。
 *
 * <p>{@code project_image_settings}と画像生成の所有権がmedia-serviceへ移った(#583)一方、
 * プロジェクト本体の所有権はproject-service(#577 stage2)にある。
 * 画像設定の読み書き・解決は「そのプロジェクトが実在すること」を前提にするため、その確認だけを行う。
 *
 * <p>認証は{@link OutboundAuthHeaders}: リクエスト中は呼び出し元のBearerを転送し、
 * 非同期ジョブ(#1405)などリクエストの無いスレッドではサービス自身のトークンを使う。
 */
@Component
public class ProjectServiceClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final OutboundAuthHeaders authHeaders;

    public ProjectServiceClient(
            RestClient.Builder builder,
            @Value("${app.project-service-uri}") String projectServiceUri,
            OutboundAuthHeaders authHeaders) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(projectServiceUri).requestFactory(requestFactory).build();
        this.authHeaders = authHeaders;
    }

    private record ProjectBridge(Long id, String name, String slug) {
    }

    /**
     * プロジェクトが実在することを確認する。存在しなければ{@link ProjectNotFoundException}。
     * {@code projectId}が{@code null}の場合は何もしない(プロジェクト未選択の呼び出しを許容する)。
     */
    public void requireProjectExists(Long projectId) {
        if (projectId == null) {
            return;
        }
        try {
            restClient.get()
                    .uri("/api/internal/project/projects/{projectId}", projectId)
                    .headers(headers -> authHeaders.current().accept(headers))
                    .retrieve()
                    .body(ProjectBridge.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません");
            }
            throw new IllegalStateException(
                    "project-serviceのプロジェクト照会呼び出しに失敗しました: " + bodyOrMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException(
                    "project-serviceのプロジェクト照会呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private String bodyOrMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return (body != null && !body.isBlank()) ? body : e.getMessage();
    }
}
