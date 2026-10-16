package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProviderModelCatalog(issue #1674)。実際のHTTPサーバ(JDK HttpServer)をプロバイダーに見立て、
 * 各プロバイダーのモデル一覧API(Ollama /api/tags、OpenAI・Anthropic /v1/models)への問い合わせと、
 * 失敗時(接続先に届かない・APIキー未設定・認証エラー・宛先検査の拒否・タイムアウト)に空を返すことを確かめる。
 *
 * <p>issue #1823: 設定の解決はRemoteLlmConfigProvider(実物)がplatform-serviceのHTTPフェイクへ問い合わせ、
 * プロジェクトのAPIキーはProjectAiSettingsService(実物)とCredentialCipher(実物)で保存・復号する。
 */
class ProviderModelCatalogTest {

    private AiWorld world;
    private FakeHttpServer http;
    private String root;

    @BeforeEach
    void setUp() {
        world = new AiWorld().admin();
        http = world.http;
        root = http.baseUrl();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ProviderModelCatalog catalog() {
        return new ProviderModelCatalog(world.llmConfigProvider, Duration.ofSeconds(3));
    }

    private void ollama(String baseUrl) {
        world.llmConfig(AiProvider.OLLAMA, baseUrl, "", "m", List.of("m"), true);
    }

    /** プロジェクト1のAPIキーを保存し、以降の問い合わせの対象プロジェクトに宣言する。 */
    private void projectKey(AiProvider provider, String key) {
        byte[] encrypted = world.credentialCipher.encrypt(key);
        if (provider == AiProvider.OPENAI) {
            world.projectAiSettingsService.setOpenAiApiKeyEncrypted(1L, encrypted);
        } else {
            world.projectAiSettingsService.setClaudeApiKeyEncrypted(1L, encrypted);
        }
        world.llmConfigProvider.useProject(1L);
    }

    private static String headerOf(FakeHttpServer.Request request, String name) {
        return request.header(name);
    }

    // ---------------------------------------------------------------- Ollama

    @Test
    void ollama_接続先の末尾の_v1_を除いた根の_api_tags_から名前を返す() {
        ollama(root + "/v1");
        http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"qwen2.5:7b\"},{\"name\":\"llama3:8b\"}]}");

        Optional<List<String>> result = catalog().fetch(AiProvider.OLLAMA);

        assertEquals(Optional.of(List.of("qwen2.5:7b", "llama3:8b")), result);
        assertEquals("GET", http.requests("GET", "/api/tags").get(0).method());
    }

    @Test
    void ollama_末尾のスラッシュ付きでも_v1_なしでも根の_api_tags_を呼ぶ() {
        ollama(root + "/v1/");
        http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"a\"}]}");

        assertEquals(Optional.of(List.of("a")), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_接続先が根のままでも呼べる() {
        ollama(root);
        http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"a\"}]}");

        assertEquals(Optional.of(List.of("a")), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_Authorizationヘッダーを付けない() {
        ollama(root);
        http.json("GET", "/api/tags", "{\"models\":[]}");

        catalog().fetch(AiProvider.OLLAMA);

        assertNull(headerOf(http.requests("GET", "/api/tags").get(0), "Authorization"));
    }

    @Test
    void ollama_接続先が未設定なら問い合わせず空を返す() {
        ollama("");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
        assertEquals(0, http.requests("GET", "/api/tags").size());
    }

    @Test
    void ollama_接続先がnullでも空を返す() {
        ollama(null);

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_プロジェクトの上書きが宛先検査で拒否されたら空を返す() {
        // ループバックは宛先検査(実物)が必ず拒否する。接続は試みない。
        ollama(root + "/v1");
        world.projectAiSettingsService.getOrCreate(1L).setOllamaBaseUrl(root + "/v1");
        world.llmConfigProvider.useProject(1L);
        http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"a\"}]}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
        assertEquals(0, http.requests("GET", "/api/tags").size());
    }

    @Test
    void ollama_宛先検査を通ったアドレスへ固定したURLで接続する() {
        // プロジェクトの上書きは、検査を通ったアドレス(IPリテラル)へ置き換えたURLでそのまま接続する。
        ollama(root + "/v1");
        world.lan().json("GET", "/api/tags", "{\"models\":[{\"name\":\"a\"}]}");
        world.projectAiSettingsService.setConnectionUrls(1L, world.lan().lanBaseUrl() + "/v1", null);
        world.llmConfigProvider.useProject(1L);

        assertEquals(Optional.of(List.of("a")), catalog().fetch(AiProvider.OLLAMA));
        assertEquals(1, world.lan().requests("GET", "/api/tags").size());
        assertEquals(0, http.requests("GET", "/api/tags").size());
    }

    // ---------------------------------------------------------------- OpenAI

    @Test
    void openai_v1_で終わる接続先の_models_からIDを絞り込まずに全件返す() {
        projectKey(AiProvider.OPENAI, "sk-project");
        http.json("GET", "/cloud/v1/models",
                "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"text-embedding-3-small\"},{\"id\":\"whisper-1\"}]}");

        Optional<List<String>> result = catalog().fetch(AiProvider.OPENAI);

        assertEquals(Optional.of(List.of("gpt-4o", "text-embedding-3-small", "whisper-1")), result);
        assertEquals("Bearer sk-project", headerOf(http.requests("GET", "/cloud/v1/models").get(0), "Authorization"));
    }

    @Test
    void openai_APIキーが未設定ならAiServiceExceptionを握りつぶし問い合わせず空を返す() {
        // プロジェクトが宣言されていない(キーを引けない)ので、RemoteLlmConfigProviderがAiServiceExceptionを投げる。
        http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"gpt-4o\"}]}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
        assertEquals(0, http.requests("GET", "/cloud/v1/models").size());
    }

    @Test
    void openai_プロジェクトにキーが無ければ問い合わせず空を返す() {
        world.llmConfigProvider.useProject(1L);
        http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"gpt-4o\"}]}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
        assertEquals(0, http.requests("GET", "/cloud/v1/models").size());
    }

    @Test
    void openai_APIキーが空白なら問い合わせず空を返す() {
        projectKey(AiProvider.OPENAI, "  ");
        http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"gpt-4o\"}]}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
        assertEquals(0, http.requests("GET", "/cloud/v1/models").size());
    }

    @Test
    void openai_認証エラー_401なら空を返す() {
        projectKey(AiProvider.OPENAI, "sk-bad");
        http.status("GET", "/cloud/v1/models", 401, "{\"error\":{\"message\":\"Incorrect API key\"}}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
    }

    // ---------------------------------------------------------------- Claude

    @Test
    void claude_x_api_key_と_anthropic_version_を付けて_v1_models_へ問い合わせ返ったIDを返す() {
        projectKey(AiProvider.CLAUDE, "sk-ant-project");
        http.json("GET", "/cloud/v1/models",
                "{\"data\":[{\"id\":\"claude-opus-4\",\"type\":\"model\"},{\"id\":\"claude-3-5-haiku-20241022\"}],\"has_more\":false}");

        Optional<List<String>> result = catalog().fetch(AiProvider.CLAUDE);

        assertEquals(Optional.of(List.of("claude-opus-4", "claude-3-5-haiku-20241022")), result);
        FakeHttpServer.Request request = http.requests("GET", "/cloud/v1/models").get(0);
        assertEquals("sk-ant-project", request.header("x-api-key"));
        assertEquals("2023-06-01", request.header("anthropic-version"));
        assertNull(request.header("Authorization"));
        // 既定の20件で打ち切られないよう、上限を明示する。
        assertEquals("limit=1000", request.query());
    }

    @Test
    void claude_APIキーが未設定なら問い合わせず空を返す() {
        assertEquals(Optional.empty(), catalog().fetch(AiProvider.CLAUDE));
        assertEquals(0, http.requests("GET", "/cloud/v1/models").size());
    }

    @Test
    void claude_認証エラー_403なら空を返す() {
        projectKey(AiProvider.CLAUDE, "sk-ant-bad");
        http.status("GET", "/cloud/v1/models", 403, "{\"error\":{\"type\":\"permission_error\"}}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.CLAUDE));
    }

    // ---------------------------------------------------------------- 応答の形・失敗

    @Test
    void 応答のIDが空やnullの要素は捨てる() {
        projectKey(AiProvider.OPENAI, "sk");
        http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"a\"},{\"id\":\"\"},{\"id\":null},{},{\"id\":\" b \"}]}");

        assertEquals(Optional.of(List.of("a", "b")), catalog().fetch(AiProvider.OPENAI));
    }

    @Test
    void 配列が空なら取得できたものとして空の一覧を返す() {
        ollama(root);
        http.json("GET", "/api/tags", "{\"models\":[]}");

        assertEquals(Optional.of(List.of()), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void 一覧の配列が無い応答は失敗として空を返す() {
        ollama(root);
        http.json("GET", "/api/tags", "{\"unexpected\":true}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void 配列でない一覧の応答は失敗として空を返す() {
        ollama(root);
        http.json("GET", "/api/tags", "{\"models\":\"nope\"}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void JSONでない応答は失敗として空を返す() {
        ollama(root);
        http.json("GET", "/api/tags", "<html>not json</html>");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void 接続先に届かなければ空を返す() throws IOException {
        // 一度待ち受けたポートを閉じて、接続拒否を作る。
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ollama("http://127.0.0.1:" + port);

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    private void stallingTags(long millis) {
        http.on("GET", "/api/tags", request -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeHttpServer.Response.ok("{\"models\":[]}");
        });
    }

    @Test
    void 応答が返らなければタイムアウトで空を返す() {
        ollama(root);
        stallingTags(3_000);

        long started = System.nanoTime();
        Optional<List<String>> result = new ProviderModelCatalog(world.llmConfigProvider, Duration.ofMillis(300))
                .fetch(AiProvider.OLLAMA);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(Optional.empty(), result);
        assertTrue(elapsedMillis < 2_000, "タイムアウトが効いていない: " + elapsedMillis + "ms");
    }

    @Test
    void 呼び出し側の待ち時間がタイムアウトより短ければそちらで打ち切る() {
        ollama(root);
        stallingTags(3_000);

        long started = System.nanoTime();
        Optional<List<String>> result = catalog().fetch(AiProvider.OLLAMA, Duration.ofMillis(300));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(Optional.empty(), result);
        assertTrue(elapsedMillis < 2_000, "待ち時間の指定が効いていない: " + elapsedMillis + "ms");
    }

    @Test
    void 呼び出し側の待ち時間がタイムアウトより長くてもタイムアウトを超えない() {
        ollama(root);
        stallingTags(3_000);

        long started = System.nanoTime();
        Optional<List<String>> result = new ProviderModelCatalog(world.llmConfigProvider, Duration.ofMillis(300))
                .fetch(AiProvider.OLLAMA, Duration.ofSeconds(30));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(Optional.empty(), result);
        assertTrue(elapsedMillis < 2_000, "タイムアウトを超えた: " + elapsedMillis + "ms");
    }

    @Test
    void 待ち時間が残っていなければ問い合わせず空を返す() {
        ollama(root);
        http.json("GET", "/api/tags", "{\"models\":[]}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA, Duration.ZERO));
        assertEquals(0, http.requests("GET", "/api/tags").size());
    }

    @Test
    void 待ち時間が負なら問い合わせず空を返す() {
        ollama(root);
        http.json("GET", "/api/tags", "{\"models\":[]}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA, Duration.ofMillis(-5)));
        assertEquals(0, http.requests("GET", "/api/tags").size());
    }

    @Test
    void 既定のタイムアウトは応答時間予算の3秒以内である() {
        assertEquals(Duration.ofSeconds(2), ProviderModelCatalog.DEFAULT_TIMEOUT);
    }

    @Test
    void 呼び出しスレッドが中断されたら空を返し中断状態を保つ() {
        ollama(root);
        stallingTags(2_000);

        Thread.currentThread().interrupt();
        try {
            assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
