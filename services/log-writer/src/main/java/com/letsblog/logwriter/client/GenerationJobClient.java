package com.letsblog.logwriter.client;

import com.letsblog.logwriter.service.IdentityServiceUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * legacy-apiの{@code GET /api/generation-jobs}を問い合わせるクライアント(#572)。
 *
 * <p>統合操作ログ(/api/operation-logs/unified)のAI_JOBソースは、issue #572の時点では
 * legacy-apiが引き続き所有するgeneration_jobsテーブルに由来する(AIサービス抽出はPhase 19の
 * 別Issueで行う)。lbs_logスキーマからは直接参照できないため、IdentityClientと同様の
 * 暫定的な同期HTTP呼び出しで取得する。
 *
 * <p>GenerationJobController自体はadmin限定等の追加認可を課さないが、legacy-apiの
 * {@code ApiKeyAuthFilter}がX-API-Key/有効なBearer JWTのいずれも無いリクエストをコントローラの
 * 手前で一律401にする(docs/AUTHORIZATION_MATRIX.md、AuthorizationMatrixIntegrationTest参照)。
 * そのため、この呼び出しも呼び出し元(統合ログAPIの実際の利用者)のBearerトークンを
 * そのまま転送する(IdentityClientと同じ理由・パターン)。
 */
@Component
public class GenerationJobClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient restClient;

    public GenerationJobClient(RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = builder
                .baseUrl(legacyApiUri)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     *                    nullの場合はヘッダーを付与せずに呼び出す(legacy-api側で401になる)。
     */
    public List<GenerationJobSummary> listRecent(String bearerToken) {
        try {
            List<GenerationJobSummary> jobs = restClient.get()
                    .uri("/api/generation-jobs")
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
                        }
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GenerationJobSummary>>() {
                    });
            return jobs != null ? jobs : List.of();
        } catch (RestClientException e) {
            throw new IdentityServiceUnavailableException("legacy-apiの/api/generation-jobs呼び出しに失敗しました", e);
        }
    }
}
