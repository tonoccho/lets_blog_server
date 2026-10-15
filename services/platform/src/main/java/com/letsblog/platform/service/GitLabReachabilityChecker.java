package com.letsblog.platform.service;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
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

/**
 * 別途起動したGitLabの到達性を判定する(issue #1796)。認証情報は持たず、システム設定の接続先の
 * {@code GET /users/sign_in}へ1回だけ(再試行しない)、3秒のタイムアウトで問い合わせる。
 *
 * <p>{@code /-/readiness}は使わない(localhost以外からの要求に404を返すため)。接続先はシステム設定(管理者が
 * 設定する値)なので{@code ConnectionDestinationGuard}の検査は通さず、{@code lbx-*}のホスト名をそのまま使える。
 * リダイレクトは追従しない。判定は2xxだけを正常とし、それ以外(3xx・4xx・5xx)、接続できない、タイムアウトは
 * ERRORで理由を返す。
 */
@Component
public class GitLabReachabilityChecker {

    static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String LABEL = "GitLab";
    private static final String PATH = "/users/sign_in";

    /** 判定結果。{@code targetUrl}は実際に問い合わせたURL({@code <接続先>/users/sign_in})。 */
    public record Result(Status status, Integer httpStatus, String errorMessage, String targetUrl) {
    }

    private final Duration timeout;

    @Autowired
    public GitLabReachabilityChecker() {
        this(TIMEOUT);
    }

    /** テスト専用: タイムアウトを短くする。 */
    GitLabReachabilityChecker(Duration timeout) {
        this.timeout = timeout;
    }

    private RestClient client() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestInterceptor(new ExternalCallLoggingInterceptor("connected-service-status"))
                .requestFactory(requestFactory)
                .build();
    }

    public Result check(String baseUrl) {
        String targetUrl = baseUrl.strip().replaceFirst("/+$", "") + PATH;
        try {
            ResponseEntity<Void> response = client().get().uri(targetUrl).retrieve().toBodilessEntity();
            int httpStatus = response.getStatusCode().value();
            if (response.getStatusCode().is2xxSuccessful()) {
                return new Result(Status.NORMAL, httpStatus, null, targetUrl);
            }
            return notSuccessful(httpStatus, targetUrl);
        } catch (RestClientResponseException e) {
            return notSuccessful(e.getStatusCode().value(), targetUrl);
        } catch (RestClientException | IllegalArgumentException e) {
            return new Result(Status.ERROR, null, LABEL + " に到達できません: " + e.getMessage(), targetUrl);
        }
    }

    private static Result notSuccessful(int httpStatus, String targetUrl) {
        return new Result(Status.ERROR, httpStatus,
                LABEL + " が 2xx 以外を返しました(HTTP " + httpStatus + ")", targetUrl);
    }
}
