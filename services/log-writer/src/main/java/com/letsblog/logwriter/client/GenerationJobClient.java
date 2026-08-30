package com.letsblog.logwriter.client;

import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import com.letsblog.logwriter.service.GenerationJobUnavailableException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-serviceの{@code GET /api/generation-jobs}を問い合わせるクライアント(#572)。issue #581(C12)で
 * lbs-commonの{@link SyncServiceClient}(タイムアウト・リトライ・サーキットブレーカーの共通実装)へ
 * 移行した。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>統合操作ログ(/api/operation-logs/unified)のAI_JOBソースは{@code generation_jobs}テーブルに
 * 由来する。lbs_logスキーマからは直接参照できないため、IdentityClientと同様の
 * 同期HTTP呼び出しで取得する。
 *
 * <p><b>問い合わせ先(issue #825)</b>: #572の時点では{@code generation_jobs}はlegacy-apiが
 * 所有しており、本クライアントもlegacy-apiを向いていた。その後AIサービス抽出で
 * {@code GenerationJobController}はai-serviceへ移設され、<b>legacy-apiからは当該
 * エンドポイントが無くなった</b>。legacy-api自身の{@code GenerationJobClient}は移設時に
 * ai-serviceを向くよう更新されたが、本クライアントだけが取り残されて404を受け続けており、
 * 統合ログAPIが常に502になっていた。#825でai-serviceへ向け直した。
 *
 * <p>既定URIを{@code http://ai:8080}にしているのは、他サービスの{@code app.ai-service-uri}が
 * 使う{@code http://api:8080}(移設前の名残でlegacy-apiを指す)をそのまま踏襲すると、
 * 環境変数未設定時にまさに本Issueと同じ「存在しないエンドポイントを叩いて404」を
 * 再現するため。docker-compose.ymlは全サービスで{@code AI_SERVICE_URI: http://ai:8080}を
 * 明示設定しているので、通常この既定値は使われない。
 *
 * <p>{@code GenerationJobController}自体はadmin限定等の追加認可を課さないが、ai-serviceの
 * SecurityConfigが有効なBearer JWTの無いリクエストをコントローラの手前で一律401にする
 * (ADR-0008、docs/AUTHORIZATION_MATRIX.md参照)。そのため、この呼び出しも呼び出し元
 * (統合ログAPIの実際の利用者)のBearerトークンをそのまま転送する(IdentityClientと同じ
 * 理由・パターン)。ai-service側はユーザー単位の絞り込みをしないが、
 * 「認証済みの利用者からの呼び出しである」ことは転送したトークンで担保される。
 */
@Component
public class GenerationJobClient {

    private final SyncServiceClient client;

    public GenerationJobClient(RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri) {
        this.client = SyncServiceClient.builder(builder, "ai-service", aiServiceUri)
                .profile(SyncCallProfile.SHORT)
                .build();
    }

    /**
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     *                    nullの場合はヘッダーを付与せずに呼び出す(ai-service側で401になる)。
     */
    public List<GenerationJobSummary> listRecent(String bearerToken) {
        try {
            List<GenerationJobSummary> jobs = client.get(
                    "/api/generation-jobs", new Object[0],
                    new ParameterizedTypeReference<List<GenerationJobSummary>>() { },
                    ServiceAuthHeaders.forwardedBearer(bearerToken));
            return jobs != null ? jobs : List.of();
        } catch (SyncServiceException e) {
            // 原因(SyncServiceExceptionのメッセージ。"[ai-service] GET /api/generation-jobs: <詳細>")を
            // 必ず連結する。#825の縮退で失敗がHTTPレスポンスに出なくなったため、ログに原因が
            // 残らないと404(向き先ミス)・401(realm/audience不整合)・タイムアウト・
            // サーキットオープンのどれなのかを切り分けられない。legacy-api側の同名クラスと同じ書き方。
            throw new GenerationJobUnavailableException(
                    "ai-serviceの/api/generation-jobs呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }
}
