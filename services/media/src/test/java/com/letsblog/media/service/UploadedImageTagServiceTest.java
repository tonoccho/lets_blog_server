package com.letsblog.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.repository.GeneratedImageRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1600: アップロード画像のAIタグ付け。補助機能なので、どの失敗でも例外を外へ出さず、
 * タグなしのまま終える。タグは既存のタグ編集と同じ{@code tags_json}(JSON配列)へ保存する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UploadedImageTagService(issue #1600)")
class UploadedImageTagServiceTest {

    private static final byte[] DATA = {9, 8, 7};

    @Mock
    private AiGenerationClient aiGenerationClient;
    @Mock
    private GeneratedImageRepository repository;

    private UploadedImageTagService service;

    @BeforeEach
    void setUp() {
        service = new UploadedImageTagService(aiGenerationClient, repository, new ObjectMapper());
    }

    private GeneratedImage image(String tagsJson) {
        GeneratedImage image = new GeneratedImage();
        image.setTagsJson(tagsJson);
        return image;
    }

    @Test
    @DisplayName("AIが返したタグをtags_jsonへ保存する。画像はそのまま(変換済みのバイト列)渡す")
    void タグを保存する() {
        when(aiGenerationClient.generateWithImage(eq(7L), anyString(), eq("image/jpeg"), eq(DATA)))
                .thenReturn("前置き {\"tags\": [\"猫\", \"屋外\"]} 後書き");
        GeneratedImage image = image(null);
        when(repository.findById(1L)).thenReturn(Optional.of(image));

        service.tagAsync(1L, 7L, "image/jpeg", DATA);

        ArgumentCaptor<GeneratedImage> captor = ArgumentCaptor.forClass(GeneratedImage.class);
        verify(repository).save(captor.capture());
        assertEquals("[\"猫\",\"屋外\"]", captor.getValue().getTagsJson());
    }

    @Test
    @DisplayName("プロンプトは#281と同じ日本語タグ3〜5個・JSON形式の指示")
    void プロンプトは281の提案テンプレートに揃う() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any())).thenReturn(null);

        service.tagAsync(1L, 7L, "image/png", DATA);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiGenerationClient).generateWithImage(eq(7L), prompt.capture(), eq("image/png"), eq(DATA));
        assertTrue(prompt.getValue().contains("短い日本語タグを3〜5個程度"), prompt.getValue());
        assertTrue(prompt.getValue().contains("{\"tags\": [\"タグ1\", \"タグ2\", \"タグ3\"]}"), prompt.getValue());
    }

    @Test
    @DisplayName("vision非対応(ai-serviceがnullを返す)なら何も保存しない")
    void 非対応なら保存しない() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any())).thenReturn(null);

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("AIの失敗は例外にせず何も保存しない")
    void AI失敗は握りつぶす() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any()))
                .thenThrow(new AiServiceException("timeout", null));

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("JSONとして読めない応答・タグが空の応答は保存しない")
    void 不正な応答は保存しない() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any()))
                .thenReturn("これはJSONではありません", "{\"tags\": []}", "{\"other\": 1}",
                        "{\"tags\": \"猫\"}", "} 閉じ括弧が先 {");

        for (int i = 0; i < 5; i++) {
            service.tagAsync(1L, 7L, "image/png", DATA);
        }

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("応答の間に画像が削除されていたら保存しない")
    void 画像が消えていたら保存しない() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any()))
                .thenReturn("{\"tags\": [\"猫\"]}");
        when(repository.findById(1L)).thenReturn(Optional.empty());

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("応答の間に利用者がタグを付けていたら上書きしない")
    void 利用者のタグを上書きしない() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any()))
                .thenReturn("{\"tags\": [\"猫\"]}");
        when(repository.findById(1L)).thenReturn(Optional.of(image("[\"手動\"]")));

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("tags_jsonが空文字でも未設定として扱い保存する")
    void 空文字のタグは未設定扱い() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any()))
                .thenReturn("{\"tags\": [\"猫\"]}");
        when(repository.findById(1L)).thenReturn(Optional.of(image("  ")));

        service.tagAsync(1L, 7L, "image/png", DATA);

        verify(repository).save(any());
    }

    @Test
    @DisplayName("保存の失敗も例外にしない")
    void 保存失敗も握りつぶす() {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any()))
                .thenReturn("{\"tags\": [\"猫\"]}");
        when(repository.findById(1L)).thenReturn(Optional.of(image(null)));
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

        service.tagAsync(1L, 7L, "image/png", DATA);
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

    @org.junit.jupiter.api.Test
    void 完了行_成功はINFOでkey_valueの1行を出す() throws Exception {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any())).thenReturn(null);
        List<ILoggingEvent> lines = completionLines(() -> { service.tagAsync(1L, 7L, "image/png", DATA); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertEquals(Level.INFO, lines.get(0).getLevel());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_tagging duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @org.junit.jupiter.api.Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() throws Exception {
        when(aiGenerationClient.generateWithImage(any(), anyString(), any(), any()))
                .thenThrow(new AiServiceException("timeout", null));
        List<ILoggingEvent> lines = completionLines(() -> { service.tagAsync(1L, 7L, "image/png", DATA); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=image_tagging duration_ms=\\d+ outcome=AiServiceException"),
                lines.get(0).getFormattedMessage());
    }
}
