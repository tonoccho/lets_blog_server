package com.letsblog.media.service;

import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参照画像付き(img2img)生成のオーケストレーション(issue #1601)。
 *
 * <p>参照画像は、ジョブを作る前に検証する(削除済み・他プロジェクトは拒否、ChatGPTも同じ検証で受理)。
 * 受理された要求では、参照画像のバイト列とdenoiseがComfyUIへ渡り、生成した各画像の行に
 * 参照元の画像IDが残る。参照画像が無い要求は従来と同じ(参照・denoiseとも空)。
 *
 * <p>Gherkin側(media/image-reference-generation.feature)が画面とAPIから同じ受け入れ基準を見る。
 * ここは分岐(プロバイダ・同期/非同期・リピート)を網羅する。
 *
 * <p>issue #1819: {@code ImageGenerationService}・{@code ReferenceImageService}・{@code GeneratedImageStorageService}と
 * その協力者はすべて実物。参照画像のファイルは一時ディレクトリに実際に置き、ComfyUI・ChatGPT画像APIはフェイクHTTPサーバーが演じる。
 * ComfyUIへ渡った参照画像は{@code /upload/image}の要求本文(マルチパート)とワークフロー(LoadImageノード・denoise)から確かめる。
 */
@DisplayName("media-service: 参照画像付き生成(issue #1601)")
class ImageGenerationServiceReferenceTest {

    private static final byte[] REFERENCE_BYTES = {4, 5, 6, 7, 8, 9};

    private final MediaWorld world = new MediaWorld();
    private final ImageGenerationService service = world.imageGenerationService;

    @BeforeEach
    void setUp() {
        world.existingImage(5L, 1L, REFERENCE_BYTES, "image/png");
    }

    @AfterEach
    void close() {
        world.close();
    }

    private static AiImageRequest withReference(Long projectId, Long referenceImageId, Double denoise) {
        return new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, null, null, null, null,
                projectId, referenceImageId, denoise);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private FakeHttpServer.Request upload() {
        return world.http.requests("POST", "/upload/image").get(0);
    }

    private void assertReferenceUploaded() {
        assertTrue(contains(upload().body(), REFERENCE_BYTES), "参照画像のバイト列がComfyUIへ送られる");
    }

    @Test
    void 同期生成は参照画像のバイト列とdenoiseをComfyUIへ渡し参照元IDを保存する() {
        service.generateImage(withReference(1L, 5L, 0.3));

        assertReferenceUploaded();
        MediaWorld.ComfyParams params = world.lastComfyParams();
        assertTrue(params.img2img());
        assertEquals(0.3, params.denoise());
        assertEquals(5L, world.savedImages.get(world.savedImages.size() - 1).getSourceImageId());
        assertEquals("uploaded.png", world.lastComfyWorkflow().path("11").path("inputs").path("image").asText());
    }

    @Test
    void denoise未指定ならComfyUIClientの既定に任せる() {
        service.generateImage(withReference(1L, 5L, null));

        assertEquals(0.6, world.lastComfyParams().denoise());
    }

    @Test
    void 参照画像が無い要求は従来どおり参照もdenoiseも空で保存にも参照元は無い() {
        service.generateImage(withReference(1L, null, null));

        MediaWorld.ComfyParams params = world.lastComfyParams();
        assertFalse(params.img2img());
        assertEquals(1.0, params.denoise());
        assertTrue(world.http.requests("POST", "/upload/image").isEmpty());
        assertNull(world.savedImages.get(world.savedImages.size() - 1).getSourceImageId());
    }

    @Test
    void 参照画像が使えない要求はジョブを作らず生成もしない_同期() {
        assertThrows(InvalidReferenceImageException.class,
                () -> service.generateImage(withReference(1L, 99L, null)));

        assertTrue(world.jobCreates().isEmpty());
        assertTrue(world.comfyWorkflows().isEmpty());
    }

    @Test
    void ChatGPTでの参照画像付き要求は拒否せず参照画像のバイト列をChatGPTへ渡し参照元IDを保存する_同期() {
        world.providerIs(1L, "CHATGPT");

        service.generateImage(withReference(1L, 5L, null));

        var edit = world.http.requests("POST", "/images/edits").get(0);
        assertTrue(contains(edit.body(), REFERENCE_BYTES), "参照画像のバイト列がimages/editsへ送られる");
        assertTrue(world.http.requests("POST", "/images/generations").isEmpty());
        assertEquals(5L, world.savedImages.get(world.savedImages.size() - 1).getSourceImageId());
        assertTrue(world.comfyWorkflows().isEmpty());
    }

    @Test
    void ChatGPTでも使えない参照画像はジョブを作らず拒否する_同期() {
        world.providerIs(1L, "CHATGPT");

        assertThrows(InvalidReferenceImageException.class,
                () -> service.generateImage(withReference(1L, 99L, null)));

        assertTrue(world.jobCreates().isEmpty());
        assertTrue(world.http.requests("POST", "/images/.*").isEmpty());
    }

    @Test
    void ChatGPTで参照画像が無い要求は従来どおり参照画像を読み込まない() {
        world.providerIs(1L, "CHATGPT");

        service.generateImage(withReference(1L, null, null));

        assertEquals(1, world.http.requests("POST", "/images/generations").size());
        assertTrue(world.http.requests("POST", "/images/edits").isEmpty());
    }

    @Test
    void 非同期の受理前検証でも参照画像を確かめる() {
        ImageProvider provider = service.requireAcceptable(withReference(1L, 5L, null));

        assertEquals(ImageProvider.COMFYUI, provider);
    }

    @Test
    void 非同期の受理前検証で他プロジェクトの参照画像は拒否する() {
        world.existingImage(6L, 2L, REFERENCE_BYTES, "image/png");

        assertThrows(InvalidReferenceImageException.class,
                () -> service.requireAcceptable(withReference(1L, 6L, null)));
    }

    @Test
    void 非同期の受理前検証でChatGPTの参照画像付き要求も参照画像を確かめて受理する() {
        world.providerIs(1L, "CHATGPT");

        ImageProvider provider = service.requireAcceptable(withReference(1L, 5L, null));

        assertEquals(ImageProvider.CHATGPT, provider);
        assertThrows(InvalidReferenceImageException.class,
                () -> service.requireAcceptable(withReference(1L, 99L, null)));
    }

    @Test
    void 非同期の受理前検証は参照画像が無ければ何も確かめない() {
        // 参照画像IDが無ければ、存在しないプロジェクト扱いの要求でも参照画像の検証では落ちない。
        assertEquals(ImageProvider.COMFYUI, service.requireAcceptable(withReference(1L, null, null)));
    }

    @Test
    void 非同期のgenerateBatchでも参照画像とdenoiseを渡し参照元IDを残す() {
        service.generateBatch(withReference(1L, 5L, 0.9), false, (done, total) -> { });

        assertReferenceUploaded();
        assertEquals(0.9, world.lastComfyParams().denoise());
        assertEquals(5L, world.savedImages.get(world.savedImages.size() - 1).getSourceImageId());
    }

    @Test
    void 参照画像はリピートの数によらず1回だけ読み込み全リピートへ渡す() throws IOException {
        // 最初のアップロードを受けた時点で保存先のファイルを消す。リピートごとに読み込み直すと、
        // 2回目以降は読み込みに失敗する。1回だけ読み込んで使い回していれば3回とも同じバイト列が送られる。
        AtomicBoolean deleted = new AtomicBoolean();
        world.http.on("POST", "/upload/image", req -> {
            if (deleted.compareAndSet(false, true)) {
                try {
                    Files.delete(world.storageDir.resolve(world.images.get(5L).getFilePath()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return null;
        });
        AiImageRequest repeated = new AiImageRequest(
                "a cat", null, null, null, null, null, 1L, null, null, null, 3, null, null, null,
                1L, 5L, 0.4);

        service.generateBatch(repeated, false, (done, total) -> { });

        var uploads = world.http.requests("POST", "/upload/image");
        assertEquals(3, uploads.size());
        for (var upload : uploads) {
            assertTrue(contains(upload.body(), REFERENCE_BYTES));
        }
        assertEquals(3, world.comfyWorkflows().size());
        for (MediaWorld.ComfyParams params : world.allComfyParams()) {
            assertEquals(0.4, params.denoise());
        }
    }
}
