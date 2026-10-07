package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmClient;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.common.net.GuardedTarget;
import com.letsblog.common.net.PinnedHttpClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * プロバイダーが実際に提供しているLLMのモデルの一覧を、プロバイダーへ問い合わせて取得する(issue #1674)。
 *
 * <ul>
 *   <li>Ollama: 実効接続先の{@code GET /api/tags}の{@code models[].name}。接続先はOpenAI互換の
 *       {@code /v1}で終わっていてもよい({@code /api/tags}はネイティブAPIなので{@code /v1}を除いた根へ呼ぶ)。</li>
 *   <li>OpenAI(ChatGPT): {@code GET /v1/models}の{@code data[].id}。返ったIDは絞り込まずすべて返す。
 *       認証はプロジェクトのAPIキー。</li>
 *   <li>Anthropic(Claude): {@code GET /v1/models}の{@code data[].id}。プロジェクトのAPIキーを
 *       {@code x-api-key}で送り、{@code anthropic-version}を付ける。</li>
 * </ul>
 *
 * <p>接続先は{@link LlmConfigProvider#targetFor}で解決する。プロジェクトのOllama接続先の上書きは、
 * そこで接続時の宛先検査({@code DestinationGuard}、issue #1547)を通り、検査したアドレスへ固定したURLになる。
 * 呼び出しの前に{@link LlmConfigProvider#useProject}で対象プロジェクトを宣言しておくこと。
 *
 * <p>問い合わせには有限のタイムアウトを置く({@link #DEFAULT_TIMEOUT}。応答時間予算の3秒に収める。最悪でも接続と読み取りの各2秒)。
 * どんな失敗(接続先に届かない・APIキー未設定・認証エラー・宛先検査の拒否・タイムアウト・想定外の応答)も
 * 例外にせず空を返し、呼び出し側({@link LlmModelService})がシステム設定の一覧へ戻す。
 */
@Service
public class ProviderModelCatalog {

    /** 応答時間予算(docs/ACCEPTANCE_CRITERIA.md §10)に収まる、問い合わせ全体のタイムアウト。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    /** Anthropicの一覧は既定で20件に打ち切られるため、上限を明示する(APIの最大値)。 */
    private static final String CLAUDE_LIST_QUERY = "?limit=1000";

    private static final Logger log = LoggerFactory.getLogger(ProviderModelCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfigProvider configProvider;
    private final Duration timeout;

    @Autowired
    public ProviderModelCatalog(LlmConfigProvider configProvider) {
        this(configProvider, DEFAULT_TIMEOUT);
    }

    /** テスト専用: タイムアウトを差し替える。 */
    ProviderModelCatalog(LlmConfigProvider configProvider, Duration timeout) {
        this.configProvider = configProvider;
        this.timeout = timeout;
    }

    /** 取得できたモデルの一覧(0件でもよい)。取得に失敗したら空のOptional。 */
    public Optional<List<String>> fetch(AiProvider provider) {
        return fetch(provider, timeout);
    }

    /**
     * {@link #fetch(AiProvider)}と同じだが、待ち時間を{@code maxWait}でも打ち切る(実際の待ち時間は
     * 設定のタイムアウトと{@code maxWait}の短いほう)。複数のプロバイダーを順に問い合わせる呼び出し側が、
     * 全体の予算の残りを渡すために使う。待ち時間が残っていなければ問い合わせず空を返す。
     */
    public Optional<List<String>> fetch(AiProvider provider, Duration maxWait) {
        Duration effective = maxWait.compareTo(timeout) < 0 ? maxWait : timeout;
        if (effective.isZero() || effective.isNegative()) {
            return Optional.empty();
        }
        try {
            return Optional.of(query(provider, effective));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Model list request for {} was interrupted", provider);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Could not fetch the model list from {}: {}", provider, e.toString());
            return Optional.empty();
        }
    }

    private List<String> query(AiProvider provider, Duration timeout) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().timeout(timeout).GET();
        String apiKey = provider == AiProvider.OLLAMA ? null : requireApiKey(provider);
        GuardedTarget target = configProvider.targetFor(provider);
        String baseUrl = target.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("接続先が設定されていません");
        }
        String listField;
        String nameField;
        switch (provider) {
            case OLLAMA -> {
                request.uri(URI.create(withoutV1(baseUrl) + "/api/tags"));
                listField = "models";
                nameField = "name";
            }
            case OPENAI -> {
                request.uri(URI.create(modelsUrl(baseUrl)));
                request.header("Authorization", "Bearer " + apiKey);
                listField = "data";
                nameField = "id";
            }
            default -> {
                request.uri(URI.create(modelsUrl(baseUrl) + CLAUDE_LIST_QUERY));
                request.header("x-api-key", apiKey);
                request.header("anthropic-version", LlmClient.ANTHROPIC_VERSION);
                listField = "data";
                nameField = "id";
            }
        }
        HttpClient client = PinnedHttpClients.builder(target.sniHost(), timeout).build();
        CompletableFuture<HttpResponse<String>> pending =
                client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.cancel(true);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return names(MAPPER.readTree(response.body()), listField, nameField);
    }

    /** APIキー(プロジェクト単位)。無ければ例外(問い合わせない)。 */
    private String requireApiKey(AiProvider provider) {
        String apiKey = configProvider.apiKeyFor(provider);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(provider + "のAPIキーが設定されていません");
        }
        return apiKey;
    }

    private static List<String> names(JsonNode root, String listField, String nameField) {
        JsonNode list = root.path(listField);
        if (!list.isArray()) {
            throw new IllegalStateException("モデルの一覧が応答に含まれていません");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : list) {
            String name = item.path(nameField).asText("").strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static String trimmed(String url) {
        String result = url.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 末尾の{@code /v1}を除いた根のURL。 */
    private static String withoutV1(String url) {
        String result = trimmed(url);
        return result.endsWith("/v1") ? result.substring(0, result.length() - "/v1".length()) : result;
    }

    /** OpenAI/Anthropic互換の一覧のURL。接続先が{@code /v1}で終わっていてもいなくても{@code /v1/models}になる。 */
    private static String modelsUrl(String baseUrl) {
        return withoutV1(baseUrl) + "/v1/models";
    }
}
