package com.letsblog.platform.service;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.GuardedTarget;
import com.letsblog.common.net.PinnedHttpClients;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;

/**
 * 別途起動した外部Penpotの到達性を判定する(issue #1774)。認証情報は持たず、システム設定の接続先の
 * ルート({@code GET /})へ1回だけ(再試行しない)、3秒のタイムアウトで問い合わせる。
 *
 * <p>接続先は接続のたびに{@link ConnectionDestinationGuard}で検査し、検査したアドレスへ固定して接続する
 * (検査と接続の間に別アドレスへ向け直すDNS rebindingを防ぐ)。LANのプライベートアドレスは通す。
 * 判定は他の外部連携と同じ規則で、何らかのHTTP応答(4xxを含む)があれば到達できている(NORMAL)、
 * 5xxは動いているが異常(WARNING)、接続できない・タイムアウト・検査で拒否はERRORで理由を返す。
 */
@Component
public class PenpotReachabilityChecker {

    static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String LABEL = "Penpot";

    /** 判定結果。{@code targetUrl}は利用者が設定した接続先(固定したアドレスではない)にスラッシュを付けたもの。 */
    public record Result(Status status, Integer httpStatus, String errorMessage, String targetUrl) {
    }

    private final ConnectionDestinationGuard guard;
    private final Function<GuardedTarget, RestClient> clientFactory;

    @Autowired
    public PenpotReachabilityChecker() {
        this(ConnectionDestinationGuard.system(), TIMEOUT, null);
    }

    /** {@code clientFactory}がnullなら、検査したアドレスへ固定した実クライアントを使う(テストで差し替える)。 */
    PenpotReachabilityChecker(
            ConnectionDestinationGuard guard, Duration timeout, Function<GuardedTarget, RestClient> clientFactory) {
        this.guard = guard;
        this.clientFactory = clientFactory != null ? clientFactory : target -> pinnedClient(target, timeout);
    }

    static RestClient pinnedClient(GuardedTarget target, Duration timeout) {
        HttpClient httpClient = PinnedHttpClients.builder(target.sniHost(), timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestInterceptor(new ExternalCallLoggingInterceptor("connected-service-status"))
                .baseUrl(target.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public Result check(String baseUrl) {
        String targetUrl = baseUrl.strip().replaceFirst("/+$", "") + "/";
        try {
            GuardedTarget target = guard.check(LABEL, targetUrl);
            ResponseEntity<Void> response = clientFactory.apply(target).get().uri("").retrieve().toBodilessEntity();
            return new Result(Status.NORMAL, response.getStatusCode().value(), null, targetUrl);
        } catch (ForbiddenDestinationException e) {
            return new Result(Status.ERROR, null, e.getMessage(), targetUrl);
        } catch (RestClientResponseException e) {
            int httpStatus = e.getStatusCode().value();
            if (e.getStatusCode().is5xxServerError()) {
                return new Result(Status.WARNING, httpStatus,
                        LABEL + " が異常な応答を返しました(HTTP " + httpStatus + ")", targetUrl);
            }
            return new Result(Status.NORMAL, httpStatus, null, targetUrl);
        } catch (RestClientException e) {
            return new Result(Status.ERROR, null, LABEL + " に到達できません: " + e.getMessage(), targetUrl);
        }
    }
}
