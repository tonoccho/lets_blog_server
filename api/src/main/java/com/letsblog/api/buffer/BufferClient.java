package com.letsblog.api.buffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Buffer REST API(/1/updates/create.json)を呼び出す薄いクライアント(issue #379)。
 * 複数SNSプラットフォームへの投稿は、Buffer側で各プラットフォームアカウントに対応付けられた
 * profile_idsを複数指定することでまとめて扱う。
 * アクセストークンはプロジェクト単位の設定(issue #402)のため、呼び出し側(BufferNotificationService/
 * SocialStatsService)が都度渡す(このクラス自体はどこから鍵を得るかを知らない、BraveSearchClientと同じ方針)。
 */
@Component
public class BufferClient {

    private final RestClient client;

    @Autowired
    public BufferClient(
            @Value("${app.buffer-api-base-url}") String baseUrl,
            @Value("${app.buffer-request-timeout-seconds}") long requestTimeoutSeconds) {
        this(builderFor(baseUrl, requestTimeoutSeconds));
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    BufferClient(RestClient.Builder restClientBuilder) {
        RestClient.Builder clonedBuilder = restClientBuilder.clone();
        LegacyJacksonRestClientConfig.preferJackson2(clonedBuilder);
        this.client = clonedBuilder.build();
    }

    private static RestClient.Builder builderFor(String baseUrl, long requestTimeoutSeconds) {
        Duration requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(requestTimeout).build());
        requestFactory.setReadTimeout(requestTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
    }

    /**
     * 指定したprofile_ids(SNSアカウント)へ、scheduledAt時刻に投稿されるようBufferへ予約する。
     */
    public List<BufferUpdate> createUpdate(List<String> profileIds, String text, Instant scheduledAt, String accessToken) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("access_token", accessToken);
            for (String profileId : profileIds) {
                form.add("profile_ids[]", profileId);
            }
            form.add("text", text);
            form.add("scheduled_at", String.valueOf(scheduledAt.getEpochSecond()));

            JsonNode response = client.post()
                    .uri("/updates/create.json")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("success").asBoolean(false)) {
                String message = response != null ? response.path("message").asText("不明なエラー") : "空のレスポンス";
                throw new BufferApiException("Bufferへの投稿予約に失敗しました: " + message);
            }

            List<BufferUpdate> updates = new ArrayList<>();
            for (JsonNode node : response.path("updates")) {
                updates.add(new BufferUpdate(node.path("id").asText(), node.path("profile_id").asText()));
            }
            return updates;
        } catch (BufferApiException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new BufferApiException(
                    "Buffer API呼び出しに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new BufferApiException(
                    "Buffer API呼び出し中にエラーが発生しました（タイムアウトまたはネットワークエラーの可能性があります）: " + e.getMessage(), e);
        }
    }

    /**
     * 指定したBuffer update(SNSプラットフォームごとの個別投稿)の統計を取得する(issue #390)。
     * プラットフォームによってstatisticsのフィールド名が揺れる(例: shares/retweets、comments/mentions)
     * ため、代表的なフィールド名をフォールバック付きで読む。
     */
    public BufferUpdateStatistics getUpdateStatistics(String updateId, String accessToken) {
        try {
            JsonNode response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/updates/{id}.json")
                            .queryParam("access_token", accessToken)
                            .build(updateId))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return new BufferUpdateStatistics(0, 0, 0, 0);
            }
            JsonNode stats = response.path("statistics");
            return new BufferUpdateStatistics(
                    stats.path("clicks").asLong(0),
                    stats.path("favorites").asLong(0),
                    firstPresent(stats, "comments", "mentions"),
                    firstPresent(stats, "shares", "retweets"));
        } catch (RestClientResponseException e) {
            throw new BufferApiException(
                    "Buffer統計取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new BufferApiException("Buffer統計取得中にエラーが発生しました: " + e.getMessage(), e);
        }
    }

    private long firstPresent(JsonNode node, String primaryField, String fallbackField) {
        if (node.has(primaryField)) {
            return node.path(primaryField).asLong(0);
        }
        return node.path(fallbackField).asLong(0);
    }
}
