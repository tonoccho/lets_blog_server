package com.letsblog.media.service;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.testsupport.MediaWorld;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1655: 編集結果を、元の画像のタグ・フォルダ・種別を引き継ぐ新しい画像として登録する。
 *
 * <p>issue #1819: {@code GeneratedImageStorageService}・{@code GeneratedImageCreationService}・
 * {@code ImageResizeService}は実物で、元画像のファイルは一時ディレクトリに実際に置く。
 * {@code generated_images}の行はリポジトリのmockが演じる。
 */
@DisplayName("GeneratedImageEditService(issue #1655)")
class GeneratedImageEditServiceTest {

    private final MediaWorld world = new MediaWorld();
    private final GeneratedImageEditService service = world.editService;

    @AfterEach
    void close() {
        world.close();
    }

    /** 元の画像の行とファイルを用意する。 */
    private GeneratedImage source(String provider, byte[] bytes, String mime, Long folderId, String tags) {
        GeneratedImage image = world.existingImage(11L, 7L, bytes, mime);
        image.setProvider(provider);
        image.setFolderId(folderId);
        image.setTagsJson(tags);
        image.setSourceImageId(3L);
        image.setPrompt("a cat");
        return image;
    }

    private BufferedImage storedImage(GeneratedImage image) throws IOException {
        return ImageIO.read(world.storageDir.resolve(image.getFilePath()).toFile());
    }

    @Test
    @DisplayName("タグ・フォルダ・providerを引き継ぎ、sourceImageIdは持たない新しい画像を登録する")
    void 引き継いで登録する() throws IOException {
        GeneratedImage src = source("COMFYUI",
                UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, Color.RED, false)), "image/png", 5L, "[\"cat\"]");

        GeneratedImage result = service.edit(src, List.of(ImageEditOperation.ROTATE_CW), null, null);

        assertTrue(world.images.containsKey(result.getId()));
        assertEquals(7L, result.getProjectId());
        assertEquals("COMFYUI", result.getProvider());
        assertEquals("[\"cat\"]", result.getTagsJson());
        assertEquals("image/png", result.getMimeType());
        assertEquals(20, result.getWidth());
        assertEquals(40, result.getHeight());
        assertNull(result.getSourceImageId());
        // promptは引き継ぐ(AI生成の画像が、一覧で「アップロード画像」と表示されないようにする)
        assertEquals("a cat", result.getPrompt());
        assertEquals(20, storedImage(result).getWidth());
        assertEquals(5L, result.getFolderId());
        assertEquals(5L, world.images.get(result.getId()).getFolderId(), "フォルダは保存し直して残す");
    }

    @Test
    @DisplayName("元が未分類なら新しい画像も未分類のまま、保存し直さない")
    void 未分類は未分類のまま() {
        GeneratedImage src = source("UPLOAD",
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(40, 20, Color.RED, false)), "image/jpeg", null, null);

        GeneratedImage result = service.edit(src, List.of(), new ImageCropRegion(0, 0, 10, 10), null);

        assertEquals("UPLOAD", result.getProvider());
        assertEquals("image/jpeg", result.getMimeType());
        assertNull(result.getTagsJson());
        assertNull(result.getFolderId());
        assertEquals(1, world.savedImages.size(), "作成時の1回だけ保存する");
    }

    @Test
    @DisplayName("編集に失敗したら何も登録しない")
    void 失敗したら登録しない() {
        GeneratedImage src = source("UPLOAD",
                UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, Color.RED, false)), "image/png", 5L, null);
        long filesBefore = world.storedFileCount();

        assertThrows(InvalidImageUploadException.class,
                () -> service.edit(src, List.of(), new ImageCropRegion(30, 0, 20, 10), null));

        assertTrue(world.savedImages.isEmpty());
        assertEquals(filesBefore, world.storedFileCount());
    }

    @Test
    @DisplayName("明るさ・コントラストの調整を適用して登録する")
    void 調整を適用して登録する() throws IOException {
        GeneratedImage src = source("UPLOAD",
                UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, new Color(100, 100, 100), false)),
                "image/png", null, null);

        GeneratedImage result = service.edit(src, List.of(), null, new ImageAdjustment(50, 0));

        BufferedImage stored = storedImage(result);
        assertEquals(150, new Color(stored.getRGB(3, 3)).getRed(), 1);
        assertEquals(40, result.getWidth());
    }

    @Test
    @DisplayName("元のファイルが失われていれば編集は保存層の例外になり、何も登録しない")
    void 元ファイルが無ければ登録しない() throws IOException {
        GeneratedImage src = source("UPLOAD",
                UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, Color.RED, false)), "image/png", null, null);
        Files.delete(world.storageDir.resolve(src.getFilePath()));

        assertThrows(com.letsblog.media.ai.AiServiceException.class,
                () -> service.edit(src, List.of(), null, null));

        assertTrue(world.savedImages.isEmpty());
    }
}
