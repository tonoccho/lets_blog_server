package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.AiServiceException;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.GuardedTarget;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderModelCatalog(issue #1674)。実際のHTTPサーバ(JDK HttpServer)をプロバイダーに見立て、
 * 各プロバイダーのモデル一覧API(Ollama /api/tags、OpenAI・Anthropic /v1/models)への問い合わせと、
 * 失敗時(接続先に届かない・APIキー未設定・認証エラー・宛先検査の拒否・タイムアウト)に空を返すことを確かめる。
 */
@ExtendWith(MockitoExtension.class)
class ProviderModelCatalogTest {

    @Mock
    private LlmConfigProvider configProvider;

    private HttpServer server;
    private String root;
    /** 受けた要求。「METHOD path?query」「ヘッダー名=値」を並べる。 */
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        root = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ProviderModelCatalog catalog() {
        return new ProviderModelCatalog(configProvider, Duration.ofSeconds(3));
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.getRequestHeaders().forEach((name, values) -> received.add(name.toLowerCase() + "=" + values.get(0)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    // ---------------------------------------------------------------- Ollama

    @Test
    void ollama_接続先の末尾の_v1_を除いた根の_api_tags_から名前を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root + "/v1", null));
        respond("/api/tags", 200, "{\"models\":[{\"name\":\"qwen2.5:7b\"},{\"name\":\"llama3:8b\"}]}");

        Optional<List<String>> result = catalog().fetch(AiProvider.OLLAMA);

        assertEquals(Optional.of(List.of("qwen2.5:7b", "llama3:8b")), result);
        assertTrue(received.contains("GET /api/tags"), received.toString());
    }

    @Test
    void ollama_末尾のスラッシュ付きでも_v1_なしでも根の_api_tags_を呼ぶ() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root + "/v1/", null));
        respond("/api/tags", 200, "{\"models\":[{\"name\":\"a\"}]}");

        assertEquals(Optional.of(List.of("a")), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_接続先が根のままでも呼べる() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        respond("/api/tags", 200, "{\"models\":[{\"name\":\"a\"}]}");

        assertEquals(Optional.of(List.of("a")), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_Authorizationヘッダーを付けない() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        respond("/api/tags", 200, "{\"models\":[]}");

        catalog().fetch(AiProvider.OLLAMA);

        assertTrue(received.stream().noneMatch(line -> line.startsWith("authorization=")), received.toString());
    }

    @Test
    void ollama_接続先が未設定なら問い合わせず空を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget("", null));

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_接続先がnullでも空を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(null, null));

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_プロジェクトの上書きが宛先検査で拒否されたら空を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA))
                .thenThrow(new ForbiddenDestinationException("Ollamaの接続先が許可されていません"));

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void ollama_宛先検査を通ったアドレスへ固定したURLで接続する() {
        // 検査済みのIPリテラルへ置き換えたURL(GuardedTarget#baseUrl)をそのまま使い、改めて名前解決しない。
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root + "/v1", null));
        respond("/api/tags", 200, "{\"models\":[{\"name\":\"a\"}]}");

        catalog().fetch(AiProvider.OLLAMA);

        verify(configProvider).targetFor(AiProvider.OLLAMA);
        verify(configProvider, never()).baseUrlFor(AiProvider.OLLAMA);
    }

    // ---------------------------------------------------------------- OpenAI

    @Test
    void openai_v1_で終わる接続先の_models_からIDを絞り込まずに全件返す() {
        when(configProvider.targetFor(AiProvider.OPENAI)).thenReturn(new GuardedTarget(root + "/v1", null));
        when(configProvider.apiKeyFor(AiProvider.OPENAI)).thenReturn("sk-project");
        respond("/v1/models", 200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"text-embedding-3-small\"},{\"id\":\"whisper-1\"}]}");

        Optional<List<String>> result = catalog().fetch(AiProvider.OPENAI);

        assertEquals(Optional.of(List.of("gpt-4o", "text-embedding-3-small", "whisper-1")), result);
        assertTrue(received.contains("authorization=Bearer sk-project"), received.toString());
    }

    @Test
    void openai_接続先が_v1_で終わらない場合も_v1_models_を呼ぶ() {
        when(configProvider.targetFor(AiProvider.OPENAI)).thenReturn(new GuardedTarget(root, null));
        when(configProvider.apiKeyFor(AiProvider.OPENAI)).thenReturn("sk-project");
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"gpt-4o\"}]}");

        assertEquals(Optional.of(List.of("gpt-4o")), catalog().fetch(AiProvider.OPENAI));
    }

    @Test
    void openai_APIキーが未設定ならAiServiceExceptionを握りつぶし問い合わせず空を返す() {
        when(configProvider.apiKeyFor(AiProvider.OPENAI))
                .thenThrow(new AiServiceException("OPENAIのAPIキーが設定されていません。", null));

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
        verify(configProvider, never()).targetFor(AiProvider.OPENAI);
    }

    @Test
    void openai_APIキーが空白なら問い合わせず空を返す() {
        when(configProvider.apiKeyFor(AiProvider.OPENAI)).thenReturn("  ");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
    }

    @Test
    void openai_APIキーがnullなら問い合わせず空を返す() {
        when(configProvider.apiKeyFor(AiProvider.OPENAI)).thenReturn(null);

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
    }

    @Test
    void openai_認証エラー_401なら空を返す() {
        when(configProvider.targetFor(AiProvider.OPENAI)).thenReturn(new GuardedTarget(root + "/v1", null));
        when(configProvider.apiKeyFor(AiProvider.OPENAI)).thenReturn("sk-bad");
        respond("/v1/models", 401, "{\"error\":{\"message\":\"Incorrect API key\"}}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OPENAI));
    }

    // ---------------------------------------------------------------- Claude

    @Test
    void claude_x_api_key_と_anthropic_version_を付けて_v1_models_へ問い合わせ返ったIDを返す() {
        when(configProvider.targetFor(AiProvider.CLAUDE)).thenReturn(new GuardedTarget(root, null));
        when(configProvider.apiKeyFor(AiProvider.CLAUDE)).thenReturn("sk-ant-project");
        respond("/v1/models", 200,
                "{\"data\":[{\"id\":\"claude-opus-4\",\"type\":\"model\"},{\"id\":\"claude-3-5-haiku-20241022\"}],\"has_more\":false}");

        Optional<List<String>> result = catalog().fetch(AiProvider.CLAUDE);

        assertEquals(Optional.of(List.of("claude-opus-4", "claude-3-5-haiku-20241022")), result);
        assertTrue(received.contains("x-api-key=sk-ant-project"), received.toString());
        assertTrue(received.contains("anthropic-version=2023-06-01"), received.toString());
        assertTrue(received.stream().noneMatch(line -> line.startsWith("authorization=")), received.toString());
        // 既定の20件で打ち切られないよう、上限を明示する。
        assertTrue(received.contains("GET /v1/models?limit=1000"), received.toString());
    }

    @Test
    void claude_APIキーが未設定なら問い合わせず空を返す() {
        when(configProvider.apiKeyFor(AiProvider.CLAUDE))
                .thenThrow(new AiServiceException("CLAUDEのAPIキーが設定されていません。", null));

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.CLAUDE));
    }

    @Test
    void claude_認証エラー_403なら空を返す() {
        when(configProvider.targetFor(AiProvider.CLAUDE)).thenReturn(new GuardedTarget(root, null));
        when(configProvider.apiKeyFor(AiProvider.CLAUDE)).thenReturn("sk-ant-bad");
        respond("/v1/models", 403, "{\"error\":{\"type\":\"permission_error\"}}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.CLAUDE));
    }

    // ---------------------------------------------------------------- 応答の形・失敗

    @Test
    void 応答のIDが空やnullの要素は捨てる() {
        when(configProvider.targetFor(AiProvider.OPENAI)).thenReturn(new GuardedTarget(root, null));
        when(configProvider.apiKeyFor(AiProvider.OPENAI)).thenReturn("sk");
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"a\"},{\"id\":\"\"},{\"id\":null},{},{\"id\":\" b \"}]}");

        assertEquals(Optional.of(List.of("a", "b")), catalog().fetch(AiProvider.OPENAI));
    }

    @Test
    void 配列が空なら取得できたものとして空の一覧を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        respond("/api/tags", 200, "{\"models\":[]}");

        assertEquals(Optional.of(List.of()), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void 一覧の配列が無い応答は失敗として空を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        respond("/api/tags", 200, "{\"unexpected\":true}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void 配列でない一覧の応答は失敗として空を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        respond("/api/tags", 200, "{\"models\":\"nope\"}");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void JSONでない応答は失敗として空を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        respond("/api/tags", 200, "<html>not json</html>");

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void 接続先に届かなければ空を返す() throws IOException {
        // 一度待ち受けたポートを閉じて、接続拒否を作る。
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget("http://127.0.0.1:" + port, null));

        assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
    }

    @Test
    void 応答が返らなければタイムアウトで空を返す() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        server.createContext("/api/tags", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();

        long started = System.nanoTime();
        Optional<List<String>> result = new ProviderModelCatalog(configProvider, Duration.ofMillis(300))
                .fetch(AiProvider.OLLAMA);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(Optional.empty(), result);
        assertTrue(elapsedMillis < 2_000, "タイムアウトが効いていない: " + elapsedMillis + "ms");
    }

    @Test
    void 既定のタイムアウトは応答時間予算の3秒以内である() {
        assertEquals(Duration.ofSeconds(2), ProviderModelCatalog.DEFAULT_TIMEOUT);
    }

    @Test
    void 呼び出しスレッドが中断されたら空を返し中断状態を保つ() {
        when(configProvider.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        server.createContext("/api/tags", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();

        Thread.currentThread().interrupt();
        try {
            assertEquals(Optional.empty(), catalog().fetch(AiProvider.OLLAMA));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
