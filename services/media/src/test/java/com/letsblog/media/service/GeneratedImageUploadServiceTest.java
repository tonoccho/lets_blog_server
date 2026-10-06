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
import org.springframework.core.task.TaskRejectedException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
    @DisplayName("JPEGは元の画素数のJPEGとして、provider=UPLOAD・promptなしで登録される")
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
        assertEquals(4000, request.width());
        assertEquals(3000, request.height());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(request.imageData()));
        assertEquals(4000, stored.getWidth());
        assertEquals(3000, stored.getHeight());
    }

    @Test
    @DisplayName("不透明PNGもPNGのまま、縦長でも元の画素数で登録される")
    void PNGの保存形式() {
        when(creationService.create(any())).thenReturn(new GeneratedImage());

        service.upload(7L, UploadImageFixtures.png(UploadImageFixtures.solid(700, 1400, Color.RED, false)));

        CreateGeneratedImageRequest request = captureRequest();
        assertEquals("image/png", request.mimeType());
        assertEquals(700, request.width());
        assertEquals(1400, request.height());
    }

    @Test
    @DisplayName("Orientation=6のJPEGは、向きを反映した画素数がwidth/heightに記録される")
    void Orientationを反映した寸法を記録する() {
        when(creationService.create(any())).thenReturn(new GeneratedImage());
        byte[] src = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false)), 6);

        service.upload(7L, src);

        CreateGeneratedImageRequest request = captureRequest();
        assertEquals(600, request.width());
        assertEquals(800, request.height());
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
    @DisplayName("登録後に、メタ情報除去済みの保存画像をAIタグ付けへ渡す(元ファイルは渡さない)")
    void 保存した画像でタグ付けを依頼する() {
        GeneratedImage saved = new GeneratedImage();
        saved.setId(42L);
        when(creationService.create(any())).thenReturn(saved);
        byte[] src = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false)), 1);

        service.upload(7L, src);

        CreateGeneratedImageRequest request = captureRequest();
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(tagService).tagAsync(eq(42L), eq(7L), eq("image/jpeg"), sent.capture());
        assertArrayEquals(request.imageData(), sent.getValue());
        assertFalse(
                new String(sent.getValue(), StandardCharsets.ISO_8859_1)
                        .contains(UploadImageFixtures.GPS_MARKER));
    }

    @Test
    @DisplayName("タグ付けの依頼(スレッドプール満杯など)が失敗してもアップロードは成功する")
    void タグ付け依頼の失敗はアップロードを失敗させない() {
        GeneratedImage saved = new GeneratedImage();
        saved.setId(42L);
        when(creationService.create(any())).thenReturn(saved);
        doThrow(new TaskRejectedException("full"))
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

    private static final int FIVE_MB = 5 * 1024 * 1024;
    private static final int FOUR_MB = 4 * 1024 * 1024;

    /** 圧縮がほぼ効かないノイズ画像。alpha=trueなら画素ごとに乱数のアルファを持つ(透過PNGになる)。 */
    private static BufferedImage noisy(int width, int height, boolean alpha) {
        BufferedImage image = new BufferedImage(
                width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = random.nextInt();
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    /** アップロードして、保存画像のリクエストとタグ付けへ渡された(mime, bytes)を返す。 */
    private record Tagged(CreateGeneratedImageRequest stored, String mime, byte[] sent) {
    }

    private Tagged uploadAndCaptureTagging(byte[] src) {
        GeneratedImage saved = new GeneratedImage();
        saved.setId(42L);
        when(creationService.create(any())).thenReturn(saved);
        service.upload(7L, src);
        ArgumentCaptor<byte[]> sentBytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> sentMime = ArgumentCaptor.forClass(String.class);
        verify(tagService).tagAsync(eq(42L), eq(7L), sentMime.capture(), sentBytes.capture());
        return new Tagged(captureRequest(), sentMime.getValue(), sentBytes.getValue());
    }

    private static void assertWithinTaggingLimit(Tagged tagged) throws IOException {
        assertTrue(tagged.sent().length <= FOUR_MB,
                "tagging copy must stay under the byte threshold: " + tagged.sent().length);
        BufferedImage sent = ImageIO.read(new ByteArrayInputStream(tagged.sent()));
        assertTrue(Math.max(sent.getWidth(), sent.getHeight()) <= 1568);
    }

    @Test
    @DisplayName("5MBを超える画像は、縮小したコピーだけをタグ付けへ渡し、保存画像は元の解像度のまま(issue #1657)")
    void 大きな画像はタグ付けにだけ縮小コピーを渡す() throws IOException {
        byte[] src = UploadImageFixtures.png(noisy(2000, 1500, false));
        assertTrue(src.length > FIVE_MB, "fixture must exceed 5MB");

        Tagged tagged = uploadAndCaptureTagging(src);

        assertEquals(2000, tagged.stored().width());
        assertEquals(1500, tagged.stored().height());
        assertEquals(2000, ImageIO.read(new ByteArrayInputStream(tagged.stored().imageData())).getWidth());
        assertWithinTaggingLimit(tagged);
        assertEquals("image/jpeg", tagged.mime());
    }

    @Test
    @DisplayName("長辺が1568px以下でもバイト数が閾値を超える画像は、タグ付け用コピーだけを縮める(issue #1657)")
    void 寸法が小さくてもバイト数が大きければ縮める() throws IOException {
        byte[] src = UploadImageFixtures.png(noisy(1560, 1250, false));
        assertTrue(src.length > FIVE_MB, "fixture must exceed 5MB: " + src.length);

        Tagged tagged = uploadAndCaptureTagging(src);

        assertEquals(1560, tagged.stored().width());
        assertEquals(1250, tagged.stored().height());
        assertEquals("image/png", tagged.stored().mimeType());
        assertWithinTaggingLimit(tagged);
    }

    @Test
    @DisplayName("1568px超の透過PNGは、縮小してもPNGで閾値を超えるなら、タグ付け用コピーだけJPEGにする(issue #1657)")
    void 透過PNGが縮小後も大きければタグ付け用だけJPEG化する() throws IOException {
        byte[] src = UploadImageFixtures.png(noisy(1700, 1300, true));
        assertTrue(src.length > FIVE_MB, "fixture must exceed 5MB: " + src.length);

        Tagged tagged = uploadAndCaptureTagging(src);

        assertEquals(1700, tagged.stored().width());
        assertEquals("image/png", tagged.stored().mimeType());
        assertEquals("image/jpeg", tagged.mime());
        assertWithinTaggingLimit(tagged);
    }

    @Test
    @DisplayName("どの縮小を試しても閾値を超える場合は、最後の(最も小さい)コピーを渡す(issue #1657)")
    void 縮小しても大きければ最後の結果を渡す() {
        ImageResizeService resize = mock(ImageResizeService.class);
        service = new GeneratedImageUploadService(resize, creationService, tagService);
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, Color.RED, false));
        when(resize.reencodeKeepingResolution(any(), any()))
                .thenReturn(new ImageResizeService.ReencodedImage(new byte[FOUR_MB + 1], "image/png", 100, 100));
        byte[] last = new byte[FOUR_MB + 3];
        when(resize.resizeToJpeg(any(), any(), eq(1568)))
                .thenReturn(new ImageResizeService.ResizeResult(new byte[FOUR_MB + 2], "image/jpeg"));
        when(resize.resizeToJpeg(any(), any(), eq(1024)))
                .thenReturn(new ImageResizeService.ResizeResult(new byte[FOUR_MB + 2], "image/jpeg"));
        when(resize.resizeToJpeg(any(), any(), eq(640)))
                .thenReturn(new ImageResizeService.ResizeResult(last, "image/jpeg"));

        Tagged tagged = uploadAndCaptureTagging(src);

        assertArrayEquals(last, tagged.sent());
    }
}
