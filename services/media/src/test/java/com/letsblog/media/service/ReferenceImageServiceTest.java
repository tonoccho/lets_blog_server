package com.letsblog.media.service;

import com.letsblog.media.ai.GeneratedImageStorageService;
import com.letsblog.media.ai.ReferenceImage;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.repository.GeneratedImageRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 参照画像(issue #1601)の検証。参照できるのは「同じプロジェクトに今も存在する生成画像」だけ。
 * 削除済み・他プロジェクト・プロジェクト未指定はいずれも同じ{@link InvalidReferenceImageException}に畳む
 * (他プロジェクトの画像IDの存在を応答の差から探れないようにするため)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: 参照画像の検証と読み込み(issue #1601)")
class ReferenceImageServiceTest {

    @Mock
    private GeneratedImageRepository repository;
    @Mock
    private GeneratedImageStorageService storage;

    private ReferenceImageService service;

    @BeforeEach
    void setUp() {
        service = new ReferenceImageService(repository, storage);
    }

    private static GeneratedImage image(long id, Long projectId, String mimeType) {
        GeneratedImage image = new GeneratedImage();
        image.setId(id);
        image.setProjectId(projectId);
        image.setFilePath("p/" + id + ".png");
        image.setMimeType(mimeType);
        return image;
    }

    @Test
    void 同じプロジェクトの画像は参照できる() {
        GeneratedImage found = image(5, 1L, "image/png");
        when(repository.findById(5L)).thenReturn(Optional.of(found));

        assertSame(found, service.requireUsable(1L, 5L));
    }

    @Test
    void 削除済みで存在しない画像は拒否する() {
        when(repository.findById(5L)).thenReturn(Optional.empty());

        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(1L, 5L));
    }

    @Test
    void 他プロジェクトの画像は拒否する() {
        when(repository.findById(5L)).thenReturn(Optional.of(image(5, 2L, "image/png")));

        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(1L, 5L));
    }

    @Test
    void プロジェクト未指定の要求は参照画像を使えない() {
        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(null, 5L));

        verify(repository, never()).findById(5L);
    }

    @Test
    void 画像のどのプロジェクトにも属さないものは拒否する() {
        when(repository.findById(5L)).thenReturn(Optional.of(image(5, null, "image/png")));

        assertThrows(InvalidReferenceImageException.class, () -> service.requireUsable(1L, 5L));
    }

    @Test
    void 読み込むと保存済みのバイト列とMIMEを返す() {
        when(repository.findById(5L)).thenReturn(Optional.of(image(5, 1L, "image/jpeg")));
        when(storage.load("p/5.png")).thenReturn(new byte[] {1, 2, 3});

        ReferenceImage reference = service.load(1L, 5L);

        assertArrayEquals(new byte[] {1, 2, 3}, reference.data());
        assertEquals("image/jpeg", reference.mimeType());
    }

    @Test
    void 読み込み時にも他プロジェクトの画像は拒否してファイルを読まない() {
        when(repository.findById(5L)).thenReturn(Optional.of(image(5, 2L, "image/png")));

        assertThrows(InvalidReferenceImageException.class, () -> service.load(1L, 5L));

        verify(storage, never()).load("p/5.png");
    }
}
