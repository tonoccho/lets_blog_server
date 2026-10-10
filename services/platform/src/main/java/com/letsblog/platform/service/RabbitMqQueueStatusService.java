package com.letsblog.platform.service;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * RabbitMQ のキュー滞留と DLQ 滞留の監視(issue #589)。
 *
 * <p>#580 で非同期イベント基盤を入れた際、各コンシューマーは
 * {@code <queue>.dlq}(リトライ上限超過の受け皿)を宣言している。しかし
 * <b>DLQ に溜まっても誰も気付かない</b>状態だった。DLQ にメッセージがあるということは、
 * イベントが処理されずに落ちている(投稿公開後のキャッシュ無効化やプロジェクト削除の
 * 後始末が実行されていない)ということなので、静かに溜め続けてはいけない。
 *
 * <p>判定は RabbitMQ の Management HTTP API({@code /api/queues}) を使う。
 * AMQP の接続だけでは各キューの滞留数を取れないため。
 *
 * <ul>
 *   <li><b>DLQ に1件でもある</b> → ERROR。処理できなかったイベントが確実に存在する</li>
 *   <li><b>通常キューが閾値超え</b> → WARNING。コンシューマーが追いつけていない</li>
 *   <li>Management API へ到達できない → WARNING(滞留の有無が判定できないだけで、
 *       ブローカー自体の死活は他の経路でも分かる)</li>
 * </ul>
 */
@Service
public class RabbitMqQueueStatusService {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String DLQ_SUFFIX = ".dlq";

    /**
     * 通常キューの滞留をWARNINGにする閾値。
     *
     * <p>本システムのイベントは投稿公開・削除・プロジェクト削除など人の操作に紐づくもので、
     * 定常的に積み上がる性質のものではない。100件溜まっていれば、コンシューマーが
     * 止まっているか極端に遅いと考えてよい。
     */
    private static final int BACKLOG_WARN_THRESHOLD = 100;

    /** 1行分の判定結果。 */
    public record QueueStatus(boolean ok, boolean warning, String message, String targetUrl) {
    }

    private final RestClient client;
    private final String managementUri;
    private final String authorization;

    public RabbitMqQueueStatusService(
            @Value("${app.rabbitmq-management-uri}") String managementUri,
            @Value("${spring.rabbitmq.username}") String username,
            @Value("${spring.rabbitmq.password}") String password) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        RestClient.Builder builder = RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("rabbitmq-management")).baseUrl(managementUri).requestFactory(requestFactory);
        preferJackson2(builder);
        this.client = builder.build();
        this.managementUri = managementUri;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public String targetUrl() {
        return managementUri + "/api/queues";
    }

    /** テストから応答を差し替えるための継ぎ目。 */
    protected JsonNode fetchQueues() {
        return client.get()
                .uri("/api/queues")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve()
                .body(JsonNode.class);
    }

    public QueueStatus check() {
        JsonNode queues;
        try {
            queues = fetchQueues();
        } catch (RestClientException e) {
            return new QueueStatus(false, true,
                    "RabbitMQ Management APIへ到達できず、滞留を判定できません: " + e.getMessage(), targetUrl());
        }
        if (queues == null || !queues.isArray()) {
            return new QueueStatus(false, true, "RabbitMQ Management APIの応答を解釈できません", targetUrl());
        }

        List<String> deadLettered = new ArrayList<>();
        List<String> backlogged = new ArrayList<>();
        for (JsonNode queue : queues) {
            String name = queue.path("name").asText("");
            int messages = queue.path("messages").asInt(0);
            if (messages <= 0) {
                continue;
            }
            if (name.endsWith(DLQ_SUFFIX)) {
                deadLettered.add(name + "=" + messages);
            } else if (messages >= BACKLOG_WARN_THRESHOLD) {
                backlogged.add(name + "=" + messages);
            }
        }

        if (!deadLettered.isEmpty()) {
            return new QueueStatus(false, false,
                    "DLQに処理できなかったイベントが残っています: " + String.join(", ", deadLettered), targetUrl());
        }
        if (!backlogged.isEmpty()) {
            return new QueueStatus(false, true,
                    "キューが滞留しています(閾値" + BACKLOG_WARN_THRESHOLD + "件): "
                            + String.join(", ", backlogged), targetUrl());
        }
        return new QueueStatus(true, false, null, targetUrl());
    }

    /**
     * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、本クラスは
     * com.fasterxml.jackson.databind.JsonNode(Jackson2)でレスポンスを読む
     * (ContainerStatusService/KeycloakAdminClientと同じ理由)。
     */
    private static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }
}
