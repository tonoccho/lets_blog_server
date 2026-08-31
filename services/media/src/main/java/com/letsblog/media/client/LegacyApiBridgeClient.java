package com.letsblog.media.client;

import com.letsblog.media.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * media-serviceの{@code AdminAuthorizationService}が、まだlegacy-apiに残るドメイン
 * ({@code project_users})へ問い合わせるための内部ブリッジ(issue #830)。
 *
 * <p>ダイアグラム・生成画像はいずれも{@code projectId}を持つのでプロジェクト単位で
 * 絞れるはずだが、media-serviceにはこれまでプロジェクトメンバー判定の手段が無く、
 * 結果として「有効なJWTさえあれば誰でも他人のダイアグラム・生成画像を読み書き・削除できる」
 * 状態だった。ai-service(#574)/content-service(#576)/analytics-service(#578)/
 * publishing-service(#708)が既に持っている同名クラスと同じ暫定策を取る。
 *
 * <p>legacy-api側は新しいエンドポイントを足さず、{@code ProjectUserBridgeController}が既に
 * 公開している{@code /api/internal/project/projects/{projectId}/members/{userId}}を再利用する
 * (#583がlegacy-apiを縮小しようとしているところへ、同一実装のメンバー判定を5本目として
 * 増やしたくないため)。#583で{@code project_users}がproject-serviceへ移った時点で、
 * 各サービスのこのクライアントはまとめて向き先を変えることになる。
 *
 * <p>呼び出し元(media-service)のBearerトークンではなく、<b>元の利用者のトークンをそのまま
 * 転送する</b>。legacy-api側の{@code /api/internal/**}は追加の認可を行わない前提。
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

    /** 操作者(userId)がプロジェクトのメンバーかどうかを判定する。 */
    public boolean isProjectMember(Long projectId, Long userId, String bearerToken) {
        try {
            Boolean result = restClient.get()
                    .uri("/api/internal/project/projects/{projectId}/members/{userId}", projectId, userId)
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
}
