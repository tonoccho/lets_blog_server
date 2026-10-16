package com.letsblog.media.service;

import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.ai.ReferenceImage;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 参照画像(img2img、issue #1601)の検証と読み込み。
 *
 * <p>削除済み・他プロジェクト・プロジェクト未指定はいずれも同じ{@link InvalidReferenceImageException}に畳む
 * (他プロジェクトの画像IDの存在を応答の差から探れないようにするため)。
 *
 * <p>issue #1819: {@code GeneratedImageStorageService}は実物で、一時ディレクトリへ実際にファイルを書き・読む。
 * {@code generated_images}の行はリポジトリのmock(メモリ上の行)が演じる。
 */
@DisplayName("media-service: 参照画像の検証と読み込み(issue #1601)")
class ReferenceImageServiceTest {

    private final MediaWorld world = new MediaWorld();
    private final ReferenceImageService service = world.referenceImageService;

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void 同じプロジェクトの画像は参照できる() {
        GeneratedImage found = world.existingImage(5, 1L, new byte[] {1}, "image/png");

        assertSame(found, service.requireUsable(1L, 5L));
    }

    @Test
    void 削除済みで存在しない画像は拒否する() {
        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(1L, 5L));
    }

    @Test
    void 他プロジェクトの画像は拒否する() {
        world.existingImage(5, 2L, new byte[] {1}, "image/png");

        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(1L, 5L));
    }

    @Test
    void プロジェクト未指定の要求は参照画像を使えない() {
        world.existingImage(5, 1L, new byte[] {1}, "image/png");
        clearInvocations(world.generatedImageRepository);

        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(null, 5L));

        verify(world.generatedImageRepository, never()).findById(5L);
    }

    @Test
    void 画像のどのプロジェクトにも属さないものは拒否する() {
        world.existingImage(5, null, new byte[] {1}, "image/png");

        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(1L, 5L));
    }

    @Test
    void 読み込むと保存済みのバイト列とMIMEを返す() {
        world.existingImage(5, 1L, new byte[] {1, 2, 3}, "image/jpeg");

        ReferenceImage reference = service.load(1L, 5L);

        assertArrayEquals(new byte[] {1, 2, 3}, reference.data());
        assertEquals("image/jpeg", reference.mimeType());
    }

    @Test
    void 読み込み時にも他プロジェクトの画像は拒否してファイルを読まない() {
        GeneratedImage other = world.existingImage(5, 2L, new byte[] {1, 2, 3}, "image/png");
        // ファイルを読みに行けば保存層の例外(AiServiceException)になる形にしておく。
        // InvalidReferenceImageExceptionで止まれば、ファイルには触れていない。
        other.setFilePath("2/missing.png");

        assertThrows(InvalidReferenceImageException.class, () -> service.load(1L, 5L));
    }

    @Test
    void ファイルが失われていれば読み込みは保存層の例外になる() {
        GeneratedImage image = world.existingImage(5, 1L, new byte[] {1}, "image/png");
        image.setFilePath("1/missing.png");

        assertThrows(AiServiceException.class, () -> service.load(1L, 5L));
    }
}
