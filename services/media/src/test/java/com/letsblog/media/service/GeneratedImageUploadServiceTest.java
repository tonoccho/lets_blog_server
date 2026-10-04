package com.letsblog.media.service;

import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** issue #1599: アップロード画像の検証・変換・登録。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeneratedImageUploadService(issue #1599)")
class GeneratedImageUploadServiceTest {

    @Mock
    private GeneratedImageCreationService creationService;

    @Mock
    private UploadedImageTagService tagService;

    private GeneratedImageUploadService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedImageUploadService(new ImageResizeService(), creationService, tagService);
    }

    private CreateGeneratedImageRequest captureRequest() {
        ArgumentCaptor<CreateGeneratedImageRequest> captor = ArgumentCaptor.forClass(CreateGeneratedImageRequest.class);
        verify(creationService).create(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("JPEGは1920x1080のJPEGとして、provider=UPLOAD・promptなしで登録される")
    void JPEGを登録する() throws IOException {
        GeneratedImage saved = new GeneratedImage();
        when(creationService.create(any())).thenReturn(saved);
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(4000, 3000, Color.RED, false));

        GeneratedImage result = service.upload(7L, src);

        assertEquals(saved, result);
        CreateGeneratedImageRequest request = captureRequest();
        assertEquals(7L, request.projectId());
        assertEquals("UPLOAD", request.provider());
        assertNull(request.prompt());
        assertEquals("image/jpeg", request.mimeType());
        assertEquals(1920, request.width());
        assertEquals(1080, request.height());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(request.imageData()));
        assertEquals(1920, stored.getWidth());
        assertEquals(1080, stored.getHeight());
    }

    @Test
    @DisplayName("不透明PNGはJPEGに、透過PNGはPNGのまま登録される")
    void PNGの保存形式() {
        when(creationService.create(any())).thenReturn(new GeneratedImage());

        service.upload(7L, UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, Color.RED, false)));
        assertEquals("image/jpeg", captureRequest().mimeType());
    }

    @Test
    @DisplayName("透過PNGはPNGで登録される")
    void 透過PNGはPNG() {
        when(creationService.create(any())).thenReturn(new GeneratedImage());

        service.upload(7L, UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, new Color(0, 0, 0, 0), true)));

        assertEquals("image/png", captureRequest().mimeType());
    }

    @Test
    @DisplayName("GIFは拒否され、何も登録されない")
    void GIFは拒否() {
        InvalidImageUploadException e = assertThrows(InvalidImageUploadException.class,
                () -> service.upload(7L, UploadImageFixtures.gif(10, 10)));

        assertEquals("対応していない画像形式です。JPEGまたはPNGを選択してください。", e.getMessage());
        verifyNoInteractions(creationService);
    }

    @Test
    @DisplayName("テキストファイルは拒否される")
    void テキストは拒否() {
        assertThrows(InvalidImageUploadException.class,
                () -> service.upload(7L, "hello world".getBytes()));
        verify(creationService, never()).create(any());
    }

    @Test
    @DisplayName("空のファイルは拒否される")
    void 空は拒否() {
        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, new byte[0]));
        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, null));
        verifyNoInteractions(creationService);
    }

    @Test
    @DisplayName("20MBを超えるファイルは拒否される")
    void 大きすぎるものは拒否() {
        byte[] tooBig = new byte[GeneratedImageUploadService.MAX_UPLOAD_BYTES + 1];
        tooBig[0] = (byte) 0x89;

        InvalidImageUploadException e = assertThrows(InvalidImageUploadException.class,
                () -> service.upload(7L, tooBig));

        assertEquals("ファイルサイズが上限(20MB)を超えています。", e.getMessage());
        verifyNoInteractions(creationService);
    }

    @Test
    @DisplayName("先頭がPNGでも中身がデコードできなければ拒否される")
    void 壊れたPNGは拒否() {
        byte[] broken = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, broken));
        verifyNoInteractions(creationService);
    }

    static Stream<byte[]> 画像の先頭として不完全なバイト列() {
        return Stream.of(
                new byte[] {(byte) 0xFF},
                new byte[] {(byte) 0xFF, (byte) 0xD8},
                new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00},
                new byte[] {(byte) 0xFF, 0x00, (byte) 0xFF},
                new byte[] {0x00, (byte) 0xD8, (byte) 0xFF},
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A},
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0B},
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x00, 0x0A},
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x00, 0x1A, 0x0A},
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x00, 0x0A, 0x1A, 0x0A},
                new byte[] {(byte) 0x89, 'P', 'N', 'X', 0x0D, 0x0A, 0x1A, 0x0A},
                new byte[] {(byte) 0x89, 'P', 'X', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
                new byte[] {(byte) 0x89, 'X', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},
                new byte[] {0x00, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
    }

    @ParameterizedTest
    @MethodSource("画像の先頭として不完全なバイト列")
    @DisplayName("JPEG/PNGの先頭バイトと一致しないものは、形式の誤りとして拒否される")
    void 先頭が一致しないものは拒否(byte[] data) {
        InvalidImageUploadException e = assertThrows(InvalidImageUploadException.class,
                () -> service.upload(7L, data));

        assertEquals("対応していない画像形式です。JPEGまたはPNGを選択してください。", e.getMessage());
        verifyNoInteractions(creationService);
    }

    @Test
    @DisplayName("登録後に、変換・メタ情報除去済みの保存画像をAIタグ付けへ渡す(元ファイルは渡さない)")
    void 保存した画像でタグ付けを依頼する() {
        GeneratedImage saved = new GeneratedImage();
        saved.setId(42L);
        when(creationService.create(any())).thenReturn(saved);
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(4000, 3000, Color.RED, false));

        service.upload(7L, src);

        CreateGeneratedImageRequest request = captureRequest();
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(tagService).tagAsync(eq(42L), eq(7L), eq("image/jpeg"), sent.capture());
        org.junit.jupiter.api.Assertions.assertArrayEquals(request.imageData(), sent.getValue());
        org.junit.jupiter.api.Assertions.assertNotEquals(src.length, sent.getValue().length);
    }

    @Test
    @DisplayName("タグ付けの依頼(スレッドプール満杯など)が失敗してもアップロードは成功する")
    void タグ付け依頼の失敗はアップロードを失敗させない() {
        GeneratedImage saved = new GeneratedImage();
        saved.setId(42L);
        when(creationService.create(any())).thenReturn(saved);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(tagService).tagAsync(any(), any(), any(), any());

        GeneratedImage result = service.upload(7L, UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, Color.RED, false)));

        assertEquals(saved, result);
    }

    @Test
    @DisplayName("拒否された画像ではタグ付けを依頼しない")
    void 拒否ではタグ付けしない() {
        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, new byte[] {1, 2, 3}));

        verifyNoInteractions(tagService);
    }
}
