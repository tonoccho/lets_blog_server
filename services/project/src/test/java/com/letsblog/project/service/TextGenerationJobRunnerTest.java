package com.letsblog.project.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.domain.TagDesignSetting;
import com.letsblog.project.repository.StaticContentRepository;
import com.letsblog.project.repository.TagDesignSettingRepository;
import com.letsblog.project.testsupport.ProjectWorld;
import com.letsblog.project.testsupport.ProjectWorld.JobUpdate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 静的コンテンツ・タグデザイン生成ジョブの実行本体(issue #1409)。{@code @Async}はSpringプロキシ経由
 * でしか効かないため、メソッドを同期的に呼んで検証する。生成結果はジョブの結果へだけ載せ、
 * 保存先へは書かない(保存はユーザーの「保存」操作で既存の保存APIが行う)。
 *
 * <p>issue #1817: StaticContentGenerationService・TagDesignGenerationService・TagDesignSettingService・各クライアントは
 * 実物。差し替えるのはDB(リポジトリ)とHTTPの向こう(ai-service・provision-agent・publishing-service)だけ。
 */
@DisplayName("project-service: テキスト生成ジョブランナー(issue #1409)")
class TextGenerationJobRunnerTest {

    private final ProjectWorld world = new ProjectWorld();
    private final StaticContentRepository staticContentRepository = Mockito.mock(StaticContentRepository.class);
    private final TagDesignSettingRepository tagDesignSettingRepository = Mockito.mock(TagDesignSettingRepository.class);
    private TextGenerationJobRunner runner;

    @BeforeEach
    void setUp() {
        StaticContentGenerationService staticContent = new StaticContentGenerationService(
                world.siteRepository, world.siteService, world.wordPressAgentPluginsClient, world.cmsBridgeClient,
                world.aiGenerationClient, staticContentRepository);
        runner = new TextGenerationJobRunner(staticContent, new TagDesignGenerationService(world.aiGenerationClient),
                new TagDesignSettingService(tagDesignSettingRepository), world.generationJobClient, world.objectMapper);
        world.respond("POST", "/plugins", Map.of("plugins", List.of(Map.of("name", "Akismet", "status", "active"))));
    }

    @AfterEach
    void close() {
        BearerScope.call(null, () -> null);
        world.close();
    }

    private JsonNode terminalPayload(long jobId, String status) {
        List<JobUpdate> updates = world.jobUpdates(jobId, status);
        assertEquals(1, updates.size());
        return updates.get(0).payload();
    }

    private String sentPrompt() {
        return world.bodiesOf("POST", "/api/internal/ai/generate").get(0).get("prompt").asText();
    }

    @Test
    @DisplayName("静的コンテンツ: 生成した本文を done の結果へ載せる。取り置いたBearerの範囲で生成し、終われば外れる")
    void staticContentSuccess() {
        world.addSshSite(5L, "SSHサイト");
        world.respond("POST", "/api/internal/project/cms/list-active-plugins", List.of("Akismet"));
        world.aiReplies("```text\n生成された本文\n```");

        runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc");

        JsonNode result = terminalPayload(11L, "done");
        assertEquals(5L, result.get("siteId").asLong());
        assertEquals("PRIVACY_POLICY", result.get("contentType").asText());
        assertEquals("生成された本文", result.get("body").asText());
        assertNull(BearerScope.current());
        // 取り置いたBearerの範囲で、サービス間ブリッジが呼ばれた。
        assertEquals("Bearer abc", world.http.requests("POST", "/api/internal/project/cms/list-active-plugins")
                .get(0).header("Authorization"));
        // 保存先(static_content)を書くのは generate / save だけ。ランナーは本文の生成しかしない。
        verify(staticContentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("静的コンテンツ: サイトが無いなら site_not_found の failed")
    void staticContentSiteNotFound() {
        runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc");

        assertEquals("site_not_found", terminalPayload(11L, "failed").get("errorType").asText());
    }

    @Test
    @DisplayName("静的コンテンツ: LLM応答が空・プラグイン情報が取れないなら generation_failed の failed")
    void staticContentGenerationFailed() {
        world.addManagedSite(5L, "slug", "wp_slug");
        world.aiReplies("  ");

        runner.runStaticContent(11L, 5L, StaticContentType.OPERATOR_INFO, "Bearer abc");

        JsonNode result = terminalPayload(11L, "failed");
        assertEquals("generation_failed", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("LLMレスポンスが空でした"));
    }

    @Test
    @DisplayName("ai-service に届かないなら ai_unavailable の failed")
    void aiUnavailable() {
        world.addManagedSite(5L, "slug", "wp_slug");
        world.fail("POST", "/api/internal/ai/generate", 503);

        runner.runStaticContent(11L, 5L, StaticContentType.TERMS_OF_SERVICE, null);

        assertEquals("ai_unavailable", terminalPayload(11L, "failed").get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed で、メッセージが無くても理由が空にならない")
    void unexpected() {
        when(world.siteRepository.findById(5L)).thenThrow(new IllegalStateException());

        runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc");

        JsonNode result = terminalPayload(11L, "failed");
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("タグデザイン: 現在のHTMLを渡して生成し、CSS/HTMLを done の結果へ載せる(保存はしない)")
    void tagDesignSuccess() {
        TagDesignSetting setting = new TagDesignSetting();
        setting.setHtmlTemplate("{{toc}}");
        when(tagDesignSettingRepository.findByProjectIdAndTagType(3L, EmbedTagType.TOC)).thenReturn(Optional.of(setting));
        world.aiReplies("```css\n.lb-toc-list{}\n```\n```html\n<nav>{{toc}}</nav>\n```");

        runner.runTagDesign(12L, 3L, EmbedTagType.TOC, "淡いグレー");

        JsonNode result = terminalPayload(12L, "done");
        assertEquals(3L, result.get("projectId").asLong());
        assertEquals("TOC", result.get("tagType").asText());
        assertEquals("<nav>{{toc}}</nav>", result.get("htmlTemplate").asText());
        assertEquals(".lb-toc-list{}", result.get("cssContent").asText());
        assertTrue(sentPrompt().contains("淡いグレー"));
        assertTrue(sentPrompt().contains("{{toc}}"), "現在のHTMLテンプレートをプロンプトに載せる");
        verify(tagDesignSettingRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("タグデザイン(グローバル): projectId null のまま現在のHTMLを引き、結果の projectId は null")
    void tagDesignGlobal() {
        when(tagDesignSettingRepository.findByProjectIdIsNullAndTagType(EmbedTagType.AMAZON)).thenReturn(Optional.empty());
        world.aiReplies("```css\n.lb-amazon-card{}\n```");

        runner.runTagDesign(12L, null, EmbedTagType.AMAZON, "p");

        JsonNode result = terminalPayload(12L, "done");
        assertEquals(true, result.get("projectId").isNull());
        assertEquals("", result.get("htmlTemplate").asText());
        assertTrue(sentPrompt().contains("(未設定。標準テンプレートを使用中です)"));
    }

    @Test
    @DisplayName("タグデザイン: CSSを抽出できないなら invalid_content の failed")
    void tagDesignInvalidContent() {
        world.aiReplies("CSSブロックなしの応答");

        runner.runTagDesign(12L, 3L, EmbedTagType.TOC, "p");

        assertEquals("invalid_content", terminalPayload(12L, "failed").get("errorType").asText());
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(TextGenerationJobRunner.class);
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
        world.addManagedSite(5L, "slug", "wp_slug");
        world.aiReplies("本文");
        List<ILoggingEvent> lines = completionLines(
                () -> runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc"));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=text_generation job_id=11 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() {
        List<ILoggingEvent> lines = completionLines(
                () -> runner.runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc"));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=text_generation job_id=11 duration_ms=\\d+ outcome=SiteNotFoundException"),
                lines.get(0).getFormattedMessage());
    }
}
