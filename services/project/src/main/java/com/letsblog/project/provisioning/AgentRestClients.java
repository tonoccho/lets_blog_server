package com.letsblog.project.provisioning;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * provision-agent向けRestClientを、接続・読み取りタイムアウト付きで組み立てる(issue #1482)。
 *
 * <p>タイムアウト未設定だとagent無応答時に呼び出しスレッドが無期限に待ち、{@code @Transactional}配下では
 * HikariCPのコネクションを掴み続ける(#1122/#1123/#1124)。publishing-serviceのIdentityClient(#1264)と同じ
 * {@link JdkClientHttpRequestFactory} + {@code setReadTimeout}のパターンに揃える。
 * タイムアウトは{@code ResourceAccessException}(=RestClientException)になり、各クライアントの既存の
 * 例外変換(ProvisioningException等)にそのまま乗る。
 */
final class AgentRestClients {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private AgentRestClients() {
    }

    static RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("wordpress-agent")).baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
