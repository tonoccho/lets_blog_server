package com.letsblog.media.service;

import com.letsblog.common.messaging.ImageGeneratedEvent;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成画像の保存(ファイル保存 + {@code generated_images} 行作成 + イベント発行)の検証。
 *
 * <p>issue #573 stage4 で {@code POST /api/generated-images} として追加された処理で、
 * 当時は {@code GeneratedImageController} に直接書かれていたため
 * {@code GeneratedImageControllerTest} が検証していた。issue #583 で画像生成本体が
 * media-service へ移り、{@link ImageGenerationService} からも同じ処理を呼ぶ必要が生じたので
 * サービスへ切り出した。検証もこちらへ移している(コントローラ側は委譲と認可の確認に絞った)。
 *
 * <p>issue #1819: {@code GeneratedImageStorageService}・{@code DomainEventPublisher}・{@code ReferenceImageService}は
 * 実物で、ファイルは一時ディレクトリへ実際に書く。{@code generated_images}の行はリポジトリのmock、
 * ドメインイベントのMQ送信は{@code RabbitTemplate}のmockが演じる。
 */
@DisplayName("media-service: 生成画像の保存(issue #573 stage4 / #583で切り出し)")
class GeneratedImageCreationServiceTest {

    private final MediaWorld world = new MediaWorld();
    private final GeneratedImageCreationService service = world.creationService;

    @AfterEach
    void close() {
        world.close();
    }

    private Path stored(String relativePath) {
        return world.storageDir.resolve(relativePath);
    }

    @Test
    void ファイルを保存しパラメータ込みで画像行を作成する() throws Exception {
        byte[] imageData = new byte[]{1, 2, 3};

        CreateGeneratedImageRequest request = new CreateGeneratedImageRequest(
                1L, "a cat", "blurry", 20, 7.0, "euler", "normal", 123L, 512, 512, 2, 1,
                "checkpoint.safetensors", null, null, "image/png", "COMFYUI", "[\"猫\"]", imageData);

        GeneratedImage saved = service.create(request);

        assertEquals("a cat", saved.getPrompt());
        assertEquals("COMFYUI", saved.getProvider());
        assertEquals("[\"猫\"]", saved.getTagsJson());
        assertEquals(world.images.get(saved.getId()), saved, "DBへ保存された行がそのまま返る");

        GeneratedImage captured = world.savedImages.get(0);
        assertEquals("1/0001.png", captured.getFilePath());
        assertArrayEquals(imageData, Files.readAllBytes(stored("1/0001.png")), "画像のバイト列が実際にファイルへ書かれる");
        assertEquals(1L, captured.getProjectId());
        assertEquals("blurry", captured.getNegativePrompt());
        assertEquals(20, captured.getSteps());
        assertEquals(0, BigDecimal.valueOf(7.0).compareTo(captured.getCfgScale()));
        assertEquals("euler", captured.getSamplerName());
        assertEquals("normal", captured.getScheduler());
        assertEquals(123L, captured.getSeed());
        assertEquals(512, captured.getWidth());
        assertEquals(512, captured.getHeight());
        assertEquals(2, captured.getBatchSize());
        // issue #1101: バッチ内のどの位置の画像かを行に残す。
        assertEquals(1, captured.getBatchIndex());
        assertEquals("checkpoint.safetensors", captured.getCheckpoint());
        assertEquals("image/png", captured.getMimeType());

        ImageGeneratedEvent event = (ImageGeneratedEvent) world.publishedMessages().get(0);
        assertEquals(saved.getId(), event.generatedImageId());
        assertEquals(1L, event.projectId());
    }

    /** LoRAを使った生成は {@code loraName}/{@code loraWeight} も行へ残す。 */
    @Test
    void LoRAの適用強度も保存する() {
        CreateGeneratedImageRequest request = new CreateGeneratedImageRequest(
                1L, "a cat", null, 20, 7.0, "euler", "normal", 1L, 512, 512, 1, 0,
                "checkpoint.safetensors", "anime.safetensors", 0.75, "image/png", "COMFYUI", null, new byte[]{4});

        GeneratedImage saved = service.create(request);

        assertEquals("anime.safetensors", saved.getLoraName());
        assertEquals(0, BigDecimal.valueOf(0.75).compareTo(saved.getLoraWeight()));
    }

    /** {@code loraWeight}/{@code cfgScale} は null 可。BigDecimal 変換で NPE にならないこと。 */
    @Test
    void 数値の任意項目がnullでも保存できる() {
        CreateGeneratedImageRequest request = new CreateGeneratedImageRequest(
                null, "a dog", null, null, null, null, null, null, null, null, null, null,
                null, null, null, "image/png", "CHATGPT", null, new byte[]{9});

        GeneratedImage saved = service.create(request);

        // issue #1101: batchIndex も null 可(既存行・単発保存)。
        assertNull(saved.getBatchIndex());
        assertNull(saved.getSeed());
        assertNull(saved.getCfgScale());
        assertNull(saved.getLoraWeight());
        assertEquals("global/0001.png", saved.getFilePath(), "プロジェクト未指定の画像はglobal配下に保存される");
        ImageGeneratedEvent event = (ImageGeneratedEvent) world.publishedMessages().get(0);
        assertNull(event.projectId());
    }

    // ---- issue #1599: MIME別の拡張子 ----

    private CreateGeneratedImageRequest uploaded(String mimeType) {
        return new CreateGeneratedImageRequest(7L, null, null, null, null, null, null, null,
                1920, 1080, null, null, null, null, null, mimeType, "UPLOAD", null, new byte[] {1});
    }

    @Test
    @DisplayName("image/jpegは拡張子jpgで保存され、promptはnullのまま行が作られる(issue #1599)")
    void jpegはjpgで保存() {
        GeneratedImage saved = service.create(uploaded("image/jpeg"));

        assertEquals("7/0001.jpg", saved.getFilePath());
        assertTrue(Files.exists(stored("7/0001.jpg")));
        assertEquals("image/jpeg", saved.getMimeType());
        assertEquals("UPLOAD", saved.getProvider());
        assertNull(saved.getPrompt());
    }

    @Test
    @DisplayName("image/pngは従来どおりの保存経路(.png)を通る")
    void pngは従来どおり() {
        GeneratedImage saved = service.create(uploaded("image/png"));

        assertEquals("7/0001.png", saved.getFilePath());
    }

    // ---- issue #1601: 参照元の画像ID ----

    private static CreateGeneratedImageRequest withSource(Long sourceImageId) {
        return new CreateGeneratedImageRequest(
                1L, "a cat", null, 20, 7.0, "euler", "normal", 1L, 512, 512, 1, 0, "c", null, null,
                "image/png", "COMFYUI", null, new byte[] {1}, sourceImageId);
    }

    @Test
    @DisplayName("参照元の画像IDを行に保存する(issue #1601)")
    void 参照元の画像IDを行に保存する() {
        world.existingImage(5, 1L, new byte[] {7}, "image/png");

        GeneratedImage saved = service.create(withSource(5L));

        assertEquals(5L, saved.getSourceImageId());
    }

    @Test
    void 参照元が無ければnullのまま() {
        GeneratedImage saved = service.create(withSource(null));

        assertNull(saved.getSourceImageId());
    }

    @Test
    void 別プロジェクト_削除済み_存在しない参照元は拒否し保存もイベントもしない() {
        world.existingImage(5, 2L, new byte[] {7}, "image/png");
        long filesBefore = world.storedFileCount();

        assertThrows(InvalidReferenceImageException.class, () -> service.create(withSource(5L)));
        assertThrows(InvalidReferenceImageException.class, () -> service.create(withSource(9L)));

        assertTrue(world.savedImages.isEmpty(), "行を作らない");
        assertEquals(filesBefore, world.storedFileCount(), "ファイルも書かない");
        assertTrue(world.publishedMessages().isEmpty(), "イベントも送らない");
    }

    @Test
    void 参照元を持たない従来の19引数コンストラクタでも保存できる() {
        CreateGeneratedImageRequest legacy = new CreateGeneratedImageRequest(
                1L, "a cat", null, 20, 7.0, "euler", "normal", 1L, 512, 512, 1, 0, "c", null, null,
                "image/png", "COMFYUI", null, new byte[] {1});

        assertNull(legacy.sourceImageId());
        assertEquals("1/0001.png", service.create(legacy).getFilePath());
    }
}
