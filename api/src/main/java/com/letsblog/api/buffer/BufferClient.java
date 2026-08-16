package com.letsblog.api.buffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BufferのGraphQL API(単一エンドポイントへのPOST、{@code Authorization: Bearer <APIキー>})を呼び出す
 * 薄いクライアント(issue #379、GraphQL移行はissue #411)。レガシーREST API(/1/updates/create.json等)は
 * 2027-02-01に廃止予定で、既に旧来の個人用アクセストークン("Public API token")によるREST呼び出しは
 * 401で拒否されるため、GraphQLの{@code createPost}/{@code post}に置き換えた。
 * REST版はprofile_ids[]を複数指定した1回の呼び出しで複数SNSアカウントへまとめて予約できたが、
 * GraphQLの{@code createPost}はmutation1回につき単一channelIdしか受け付けないため、
 * 呼び出し元から見た{@link #createUpdate}のシグネチャは変えずに内部でchannelIdごとにループする。
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
     * 指定したprofileIds(Buffer用語では現在「channel」だが、呼び出し元の互換性のため引数名は維持)へ、
     * scheduledAt時刻に投稿されるようBufferへ予約する。channelIdごとに{@code createPost} mutationを
     * 個別に呼び出すため、途中のchannelで失敗すると例外を投げてそれ以降のchannelへは呼び出さない
     * (呼び出し元のBufferNotificationServiceが全体をリトライする。成功済みchannelへの重複投稿の
     * 可能性はissue #411で既知の制約として記録済み)。
     */
    public List<BufferUpdate> createUpdate(List<String> profileIds, String text, Instant scheduledAt, String accessToken) {
        String dueAt = DateTimeFormatter.ISO_INSTANT.format(scheduledAt);
        List<BufferUpdate> updates = new ArrayList<>();
        for (String channelId : profileIds) {
            updates.add(createPost(channelId, text, dueAt, accessToken));
        }
        return updates;
    }

    private BufferUpdate createPost(String channelId, String text, String dueAt, String accessToken) {
        String query = "mutation { createPost(input: { "
                + "text: " + gqlString(text) + ", "
                + "channelId: " + gqlString(channelId) + ", "
                + "schedulingType: automatic, "
                + "mode: customScheduled, "
                + "dueAt: " + gqlString(dueAt)
                + " }) { "
                + "... on PostActionSuccess { post { id } } "
                + "... on MutationError { message } "
                + "} }";

        try {
            JsonNode response = client.post()
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query))
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode topLevelError = firstError(response);
            if (topLevelError != null) {
                throw new BufferApiException("Bufferへの投稿予約に失敗しました: " + topLevelError.path("message").asText("不明なエラー"));
            }

            JsonNode result = response == null ? null : response.path("data").path("createPost");
            JsonNode postNode = result == null ? null : result.path("post");
            if (postNode == null || postNode.isMissingNode() || postNode.isNull()) {
                String message = result != null ? result.path("message").asText("不明なエラー") : "空のレスポンス";
                throw new BufferApiException("Bufferへの投稿予約に失敗しました: " + message);
            }
            return new BufferUpdate(postNode.path("id").asText(), channelId);
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
     * 指定したBuffer post(updateId、SNSプラットフォームごとの個別投稿)の統計を取得する(issue #390)。
     * GraphQLの{@code post.metrics}は{@code type}/{@code value}の配列で返るため、代表的なtype名
     * (reactions/reposts/comments等)へベストエフォートでマッピングする。postが見つからない/
     * metricsが空の場合はREST版と同様に全て0を返す(統計取得の一部失敗で呼び出し元の集計全体を
     * 失敗させないため、HTTPレベルの失敗のみ例外にする)。
     */
    public BufferUpdateStatistics getUpdateStatistics(String updateId, String accessToken) {
        String query = "query { post(input: { id: " + gqlString(updateId) + " }) { metrics { type value } } }";

        try {
            JsonNode response = client.post()
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", query))
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode post = response == null ? null : response.path("data").path("post");
            if (post == null || post.isMissingNode() || post.isNull()) {
                return new BufferUpdateStatistics(0, 0, 0, 0);
            }

            JsonNode metrics = post.path("metrics");
            return new BufferUpdateStatistics(
                    metricValue(metrics, "clicks"),
                    firstPresentMetric(metrics, "reactions", "likes", "favorites"),
                    firstPresentMetric(metrics, "comments", "replies", "mentions"),
                    firstPresentMetric(metrics, "reposts", "shares", "retweets"));
        } catch (RestClientResponseException e) {
            throw new BufferApiException(
                    "Buffer統計取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new BufferApiException("Buffer統計取得中にエラーが発生しました: " + e.getMessage(), e);
        }
    }

    private JsonNode firstError(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode errors = response.path("errors");
        return errors.isArray() && !errors.isEmpty() ? errors.get(0) : null;
    }

    private long metricValue(JsonNode metrics, String type) {
        for (JsonNode metric : metrics) {
            if (type.equals(metric.path("type").asText())) {
                return metric.path("value").asLong(0);
            }
        }
        return 0;
    }

    private long firstPresentMetric(JsonNode metrics, String... typesInPriorityOrder) {
        for (String type : typesInPriorityOrder) {
            for (JsonNode metric : metrics) {
                if (type.equals(metric.path("type").asText())) {
                    return metric.path("value").asLong(0);
                }
            }
        }
        return 0;
    }

    /** GraphQL文字列リテラルとして安全に埋め込めるようエスケープする(JSON文字列と同じ規則)。 */
    private static String gqlString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
