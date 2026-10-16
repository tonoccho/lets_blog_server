package com.letsblog.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * アップロード画像のAIタグ付け(issue #1600)。
 *
 * <p>vision対応のLLMへ画像を渡してタグを提案させ、既存の{@code tags_json}へ保存する。
 * 補助機能なので、AIが使えない・応答が読めない・応答の間に画像が消えた/タグが付いた場合は例外にせず、
 * タグなしのまま終える。タグは既存のタグ編集と同じ{@code tags_json}(JSON配列)へ保存する。
 *
 * <p>issue #1819: {@code AiGenerationClient}・{@code ObjectMapper}は実物で、ai-serviceをフェイクHTTPサーバー、
 * {@code generated_images}をリポジトリのmockが演じる。
 */
@DisplayName("UploadedImageTagService(issue #1600)")
class UploadedImageTagServiceTest {

    private static final byte[] DATA = {9, 8, 7};
    private static final String VISION_PATH = "/api/internal/ai/generate-with-image";

    private final MediaWorld world = new MediaWorld();
    private final UploadedImageTagService service = world.uploadedImageTagService;

    @AfterEach
    void close() {
        world.close();
    }

    private void aiReturns(String result) {
        world.http.on("POST", VISION_PATH, FakeHttpServer.Response.ok(world.toJson(java.util.Collections.singletonMap("result", result))));
    }

    private GeneratedImage image(Long id, String tagsJson) {
        GeneratedImage image = new GeneratedImage();
        image.setId(id);
        image.setTagsJson(tagsJson);
        world.images.put(id, image);
        return image;
    }

    @Test
    @DisplayName("AIが返したタグをtags_jsonへ保存する。画像はそのまま(変換済みのバイト列)渡す")
    void タグを保存する() {
        aiReturns("前置き {\"tags\": [\"猫\", \"屋外\"]} 後書き");
        GeneratedImage image = image(1L, null);

        service.tagAsync(1L, 7L, "image/jpeg", DATA);

        assertEquals("[\"猫\",\"屋外\"]", image.getTagsJson());
        assertTrue(world.savedImages.contains(image));
        JsonNode sent = world.bodyOf(world.http.requests("POST", VISION_PATH).get(0));
        assertEquals(7L, sent.get("projectId").asLong());
        assertEquals("image/jpeg", sent.get("mimeType").asText());
        assertArrayEquals(DATA, Base64.getDecoder().decode(sent.get("imageBase64").asText()));
    }

    @Test
    @DisplayName("プロンプトは#281と同じ日本語タグ3〜5個・JSON形式の指示")
    void プロンプトは281の提案テンプレートに揃う() {
        aiReturns(null);

        service.tagAsync(1L, 7L, "image/png", DATA);

        String prompt = world.bodyOf(world.http.requests("POST", VISION_PATH).get(0)).get("prompt").asText();
        assertTrue(prompt.contains("短い日本語タグを3〜5個程度"), prompt);
        assertTrue(prompt.contains("{\"tags\": [\"タグ1\", \"タグ2\", \"タグ3\"]}"), prompt);
    }

    @Test
    @DisplayName("vision非対応(ai-serviceがnullを返す)なら何も保存しない")
    void 非対応なら保存しない() {
        aiReturns(null);
        image(1L, null);

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(world.generatedImageRepository, never()).save(any());
    }

    @Test
    @DisplayName("AIの失敗は例外にせず何も保存しない")
    void AI失敗は握りつぶす() {
        world.http.status("POST", VISION_PATH, 400, "{\"error\":\"vision unsupported\"}");
        image(1L, null);

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(world.generatedImageRepository, never()).save(any());
    }

    @Test
    @DisplayName("ai-serviceが空の応答(本文なし)を返しても例外にせず何も保存しない")
    void 空の応答は握りつぶす() {
        world.http.on("POST", VISION_PATH, FakeHttpServer.Response.empty(200));
        image(1L, null);

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(world.generatedImageRepository, never()).save(any());
    }

    @Test
    @DisplayName("JSONとして読めない応答・タグが空の応答は保存しない")
    void 不正な応答は保存しない() {
        image(1L, null);
        for (String reply : List.of("これはJSONではありません", "{\"tags\": []}", "{\"other\": 1}",
                "{\"tags\": \"猫\"}", "} 閉じ括弧が先 {")) {
            aiReturns(reply);
            service.tagAsync(1L, 7L, "image/png", DATA);
        }

        verify(world.generatedImageRepository, never()).save(any());
    }

    @Test
    @DisplayName("応答の間に画像が削除されていたら保存しない")
    void 画像が消えていたら保存しない() {
        aiReturns("{\"tags\": [\"猫\"]}");

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(world.generatedImageRepository, never()).save(any());
    }

    @Test
    @DisplayName("応答の間に利用者がタグを付けていたら上書きしない")
    void 利用者のタグを上書きしない() {
        aiReturns("{\"tags\": [\"猫\"]}");
        GeneratedImage image = image(1L, "[\"手動\"]");

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(world.generatedImageRepository, never()).save(any());
        assertEquals("[\"手動\"]", image.getTagsJson());
    }

    @Test
    @DisplayName("tags_jsonが空文字でも未設定として扱い保存する")
    void 空文字のタグは未設定扱い() {
        aiReturns("{\"tags\": [\"猫\"]}");
        GeneratedImage image = image(1L, "  ");

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(world.generatedImageRepository, times(1)).save(image);
        assertEquals("[\"猫\"]", image.getTagsJson());
    }

    @Test
    @DisplayName("保存の失敗も例外にしない")
    void 保存失敗も握りつぶす() {
        aiReturns("{\"tags\": [\"猫\"]}");
        image(1L, null);
        when(world.generatedImageRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(world.generatedImageRepository, times(1)).save(any());
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(UploadedImageTagService.class);
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
        aiReturns(null);
        List<ILoggingEvent> lines = completionLines(() -> service.tagAsync(1L, 7L, "image/png", DATA));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_tagging duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() {
        world.http.status("POST", VISION_PATH, 400, "{\"error\":\"vision unsupported\"}");
        List<ILoggingEvent> lines = completionLines(() -> service.tagAsync(1L, 7L, "image/png", DATA));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_tagging duration_ms=\\d+ outcome=AiServiceException"),
                lines.get(0).getFormattedMessage());
    }
}
