package com.letsblog.content.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.testsupport.BrokenObjectMapper;
import com.letsblog.content.testsupport.ContentWorld;
import com.letsblog.content.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * カスタムタグ生成ジョブの実行本体(issue #1409)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する。生成結果はジョブの結果へだけ載せ、保存先へは書かない
 * (ランナーは保存先のリポジトリを持たない)ことを、結果の形で固定する。
 *
 * <p>issue #1821: CustomTagGenerationService・GenerationJobClient・AiGenerationClientは実物。ai-service
 * (LLMとジョブの更新)はHTTPフェイクで、ジョブの終端通知として実際に送られた内容を見る。
 */
@DisplayName("content-service: カスタムタグ生成ジョブランナー(issue #1409)")
class CustomTagGenerationJobRunnerTest {

    private static final String GENERATE_PATH = "/api/internal/ai/generate";
    private static final String JOB_PATH = "/api/internal/ai/generation-jobs/31";
    private static final GenerateCustomTagRequest REQUEST =
            new GenerateCustomTagRequest("青いボタン", "blue-button", "説明", 4L);

    private ContentWorld world;
    private CustomTagGenerationJobRunner runner;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        runner = world.customTagGenerationJobRunner;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void llmReturns(String text) {
        world.http.json("POST", GENERATE_PATH, world.toJson(Map.of("result", text)));
    }

    /** ジョブの更新としてai-serviceに届いた最後のPATCH。 */
    private FakeHttpServer.Request lastUpdate() {
        List<FakeHttpServer.Request> patches = world.http.requests("PATCH", JOB_PATH);
        assertFalse(patches.isEmpty(), "ジョブの更新が届いていない");
        return patches.get(patches.size() - 1);
    }

    private JsonNode terminalPayload(String status) throws Exception {
        JsonNode body = world.bodyOf(lastUpdate());
        assertEquals(status, body.get("status").asText());
        return world.objectMapper.readTree(body.get("resultPayload").asText());
    }

    @Test
    @DisplayName("成功すると、生成したHTML/CSSとタグ名などを done の結果へ載せる")
    void success() throws Exception {
        llmReturns("```html\n<b>{{content}}</b>\n```\n```css\n.b{}\n```");

        runner.run(31L, REQUEST);

        JsonNode result = terminalPayload("done");
        assertEquals("<b>{{content}}</b>", result.get("htmlTemplate").asText());
        assertEquals(".b{}", result.get("cssContent").asText());
        assertEquals("blue-button", result.get("tagName").asText());
        assertEquals("説明", result.get("description").asText());
        assertEquals(4L, result.get("projectId").asLong());
        // 保存先への書き込みはしない。ai-serviceへの認証は、リクエストの無いスレッドなのでサービス自身のトークン
        assertTrue(world.savedTags.isEmpty());
        assertEquals("Bearer svc-token", lastUpdate().header("Authorization"));
    }

    @Test
    @DisplayName("説明とプロジェクトが無いときは null で載せる(グローバルタグ)")
    void successWithoutOptionalFields() throws Exception {
        llmReturns("```html\n<b/>\n```");

        runner.run(31L, new GenerateCustomTagRequest("p", "t", null, null));

        JsonNode result = terminalPayload("done");
        assertTrue(result.get("description").isNull());
        assertTrue(result.get("projectId").isNull());
    }

    @Test
    @DisplayName("HTMLを抽出できない・検証に落ちたときは invalid_content の failed")
    void invalidContent() throws Exception {
        llmReturns("説明だけ");

        runner.run(31L, REQUEST);

        JsonNode result = terminalPayload("failed");
        assertEquals("invalid_content", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("HTMLを抽出できませんでした"));
    }

    @Test
    @DisplayName("ai-service に届かないときは ai_unavailable の failed")
    void aiUnavailable() throws Exception {
        world.http.status("POST", GENERATE_PATH, 400, "{\"error\":\"down\"}");

        runner.run(31L, REQUEST);

        assertEquals("ai_unavailable", terminalPayload("failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は generation_failed の failed で、理由が空にならない")
    void unexpected() throws Exception {
        // LLMの結果が null だと、生成結果の抽出で NullPointerException になる(想定外の失敗)
        world.http.json("POST", GENERATE_PATH, "{\"result\":null}");

        runner.run(31L, REQUEST);

        JsonNode result = terminalPayload("failed");
        assertEquals("generation_failed", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("理由のメッセージが無い例外は、例外のクラス名を理由にする")
    void failurePayloadUsesClassNameWithoutMessage() {
        Map<String, Object> payload = CustomTagGenerationJobRunner.failurePayload(new IllegalStateException());

        assertEquals("IllegalStateException", payload.get("error"));
        assertEquals("generation_failed", payload.get("errorType"));
    }

    @Test
    @DisplayName("結果をJSONにできなくても、空のJSONで終端通知を送る")
    void jsonFailureFallsBackToEmptyObject() throws Exception {
        llmReturns("```html\n<b/>\n```");
        CustomTagGenerationJobRunner broken = new CustomTagGenerationJobRunner(
                world.customTagGenerationService, world.generationJobClient, BrokenObjectMapper.create());

        broken.run(31L, REQUEST);

        assertEquals("{}", world.bodyOf(lastUpdate()).get("resultPayload").asText());
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(CustomTagGenerationJobRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("job completed:"))
                .collect(Collectors.toList());
    }

    @Test
    void 完了行_成功はINFOでkey_valueの1行を出す() {
        llmReturns("```html\n<b/>\n```");
        List<ILoggingEvent> lines = completionLines(() -> runner.run(31L, REQUEST));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=custom_tag_generation job_id=31 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() {
        llmReturns("抽出できない");
        List<ILoggingEvent> lines = completionLines(() -> runner.run(31L, REQUEST));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=custom_tag_generation job_id=31 duration_ms=\\d+ outcome=InvalidCustomTagContentException"),
                lines.get(0).getFormattedMessage());
    }
}
