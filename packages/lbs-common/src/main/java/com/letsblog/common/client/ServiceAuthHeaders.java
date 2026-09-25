package com.letsblog.common.client;

import com.letsblog.common.auth.ServiceTokenClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;

/**
 * {@link SyncServiceClient}呼び出しへAuthorizationヘッダーを付与するための共通ヘルパー(issue #581)。
 * サービス間同期呼び出しの認証には2つのパターンがあり、呼び出し内容によってどちらを使うかが決まる。
 *
 * <ul>
 *   <li>{@link #forwardedBearer}: 呼び出し元(このサービスを呼んだユーザー)のBearerトークンを
 *       そのまま下流へ転送する。下流側が「そのユーザーの権限」で認可判断をするAPI
 *       (例: identity-serviceの{@code /api/identity/me}、legacy-apiの内部ブリッジのプロジェクト
 *       メンバー判定)を呼ぶ場合に使う。Phase 19の各抽出Issue(#572/#573/#574/#576/#578)が
 *       個別に実装していた暫定策で、ユーザーコンテキストが必要な呼び出しでは引き続きこの方式を使う
 *       (下流の認可モデル自体を変えるのはこのIssueのスコープ外。docs/SYNC_SERVICE_CALLS.md参照)。</li>
 *   <li>{@link #clientCredentials}: issue #567(B9)のサービス間Client Credentials認証
 *       ({@link ServiceTokenClient})でこのサービス自身の身元を示すトークンを付与する。
 *       ユーザーコンテキストが無い呼び出し(スケジュールジョブ、バックグラウンド処理で元のHTTP
 *       リクエストが既に終わっている場合等)に使う。</li>
 * </ul>
 */
public final class ServiceAuthHeaders {

    private ServiceAuthHeaders() {
    }

    /** 呼び出し元のAuthorizationヘッダーの値をそのまま転送する({@link HttpServletRequest}から読む版)。 */
    public static Consumer<HttpHeaders> forwardedBearer(HttpServletRequest request) {
        return headers -> {
            String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
            setIfPresent(headers, bearerToken);
        };
    }

    /**
     * 呼び出し元のAuthorizationヘッダーの値をそのまま転送する(値を明示的に受け取る版)。
     * {@code @Async}なバックグラウンドスレッド等、リクエストスコープのBeanを注入できない
     * 呼び出し元がトリガー時点のトークンを引き回す場合に使う
     * (media-service GenerationJobClient/CmsBridgeClientと同じパターン)。
     *
     * @param bearerToken {@code "Bearer xxx"}形式。nullまたは空文字ならヘッダーを付与しない。
     */
    public static Consumer<HttpHeaders> forwardedBearer(String bearerToken) {
        return headers -> setIfPresent(headers, bearerToken);
    }

    /**
     * issue #567(B9)のClient Credentials Grantで取得したこのサービス自身のアクセストークンを付与する。
     *
     * @throws com.letsblog.common.auth.ServiceTokenUnavailableException トークン取得に失敗した場合。
     *         呼び出し元はこの例外を{@link SyncServiceClient}の呼び出しより前に処理するか、
     *         または呼び出しごと失敗させて構わない箇所でのみ使うこと。
     */
    public static Consumer<HttpHeaders> clientCredentials(ServiceTokenClient serviceTokenClient) {
        return headers -> headers.setBearerAuth(serviceTokenClient.getAccessToken());
    }

    /** 任意のSupplierからBearerトークンを取得して付与する汎用版。 */
    public static Consumer<HttpHeaders> bearer(Supplier<String> tokenSupplier) {
        return headers -> {
            String token = tokenSupplier.get();
            if (token != null && !token.isBlank()) {
                headers.setBearerAuth(token);
            }
        };
    }

    private static void setIfPresent(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
