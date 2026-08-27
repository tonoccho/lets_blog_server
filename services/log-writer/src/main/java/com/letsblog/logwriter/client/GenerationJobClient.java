package com.letsblog.logwriter.client;

import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import com.letsblog.logwriter.service.IdentityServiceUnavailableException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * legacy-apiの{@code GET /api/generation-jobs}を問い合わせるクライアント(#572)。issue #581(C12)で
 * lbs-commonの{@link SyncServiceClient}(タイムアウト・リトライ・サーキットブレーカーの共通実装)へ
 * 移行した。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>統合操作ログ(/api/operation-logs/unified)のAI_JOBソースは、issue #572の時点では
 * legacy-apiが引き続き所有するgeneration_jobsテーブルに由来する(AIサービス抽出はPhase 19の
 * 別Issueで行う)。lbs_logスキーマからは直接参照できないため、IdentityClientと同様の
 * 同期HTTP呼び出しで取得する。
 *
 * <p>GenerationJobController自体はadmin限定等の追加認可を課さないが、legacy-apiの
 * SecurityConfig(issue #566で全面Keycloak JWT必須化)が有効なBearer JWTの無いリクエストを
 * コントローラの手前で一律401にする(docs/AUTHORIZATION_MATRIX.md、
 * AuthorizationMatrixIntegrationTest参照)。そのため、この呼び出しも呼び出し元
 * (統合ログAPIの実際の利用者)のBearerトークンをそのまま転送する(IdentityClientと同じ
 * 理由・パターン)。
 */
@Component
public class GenerationJobClient {

    private final SyncServiceClient client;

    public GenerationJobClient(RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri) {
        this.client = SyncServiceClient.builder(builder, "legacy-api", legacyApiUri)
                .profile(SyncCallProfile.SHORT)
                .build();
    }

    /**
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     *                    nullの場合はヘッダーを付与せずに呼び出す(legacy-api側で401になる)。
     */
    public List<GenerationJobSummary> listRecent(String bearerToken) {
        try {
            List<GenerationJobSummary> jobs = client.get(
                    "/api/generation-jobs", new Object[0],
                    new ParameterizedTypeReference<List<GenerationJobSummary>>() { },
                    ServiceAuthHeaders.forwardedBearer(bearerToken));
            return jobs != null ? jobs : List.of();
        } catch (SyncServiceException e) {
            throw new IdentityServiceUnavailableException("legacy-apiの/api/generation-jobs呼び出しに失敗しました", e);
        }
    }
}
