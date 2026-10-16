package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.testsupport.AsyncProxies;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1599: アップロード画像の検証・変換・登録。
 *
 * <p>issue #1819: {@code ImageResizeService}・{@code GeneratedImageCreationService}・{@code UploadedImageTagService}・
 * {@code AiGenerationClient}は実物。ai-serviceをフェイクHTTPサーバー、{@code generated_images}をリポジトリのmockが演じ、
 * ファイルは一時ディレクトリへ実際に書く。
 */
@DisplayName("GeneratedImageUploadService(issue #1599)")
class GeneratedImageUploadServiceTest {

    private static final String VISION_PATH = "/api/internal/ai/generate-with-image";

    private final MediaWorld world = new MediaWorld();
    private final GeneratedImageUploadService service = world.uploadService;

    @AfterEach
    void close() {
        world.close();
    }

    private BufferedImage stored(GeneratedImage image) throws IOException {
        return ImageIO.read(world.storageDir.resolve(image.getFilePath()).toFile());
    }

    @Test
    @DisplayName("JPEGは元の画素数のJPEGとして、provider=UPLOAD・promptなしで登録される")
    void JPEGを登録する() throws IOException {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(4000, 3000, Color.RED, false));

        GeneratedImage result = service.upload(7L, src);

        assertEquals(result, world.images.get(result.getId()));
        assertEquals(7L, result.getProjectId());
        assertEquals("UPLOAD", result.getProvider());
        assertNull(result.getPrompt());
        assertEquals("image/jpeg", result.getMimeType());
        assertEquals(4000, result.getWidth());
        assertEquals(3000, result.getHeight());
        assertTrue(result.getFilePath().endsWith(".jpg"));
        BufferedImage stored = stored(result);
        assertEquals(4000, stored.getWidth());
        assertEquals(3000, stored.getHeight());
    }

    @Test
    @DisplayName("不透明PNGもPNGのまま、縦長でも元の画素数で登録される")
    void PNGの保存形式() {
        GeneratedImage result = service.upload(7L,
                UploadImageFixtures.png(UploadImageFixtures.solid(700, 1400, Color.RED, false)));

        assertEquals("image/png", result.getMimeType());
        assertEquals(700, result.getWidth());
        assertEquals(1400, result.getHeight());
    }

    @Test
    @DisplayName("Orientation=6のJPEGは、向きを反映した画素数がwidth/heightに記録される")
    void Orientationを反映した寸法を記録する() {
        byte[] src = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false)), 6);

        GeneratedImage result = service.upload(7L, src);

        assertEquals(600, result.getWidth());
        assertEquals(800, result.getHeight());
    }

    @Test
    @DisplayName("透過PNGはPNGで登録される")
    void 透過PNGはPNG() {
        GeneratedImage result = service.upload(7L,
                UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, new Color(0, 0, 0, 0), true)));

        assertEquals("image/png", result.getMimeType());
    }

    @Test
    @DisplayName("GIFは拒否され、何も登録されない")
    void GIFは拒否() {
        InvalidImageUploadException e = assertThrows(InvalidImageUploadException.class,
                () -> service.upload(7L, UploadImageFixtures.gif(10, 10)));

        assertEquals("対応していない画像形式です。JPEGまたはPNGを選択してください。", e.getMessage());
        assertTrue(world.savedImages.isEmpty());
    }

    @Test
    @DisplayName("テキストファイルは拒否される")
    void テキストは拒否() {
        assertThrows(InvalidImageUploadException.class,
                () -> service.upload(7L, "hello world".getBytes()));
        assertTrue(world.savedImages.isEmpty());
    }

    @Test
    @DisplayName("空のファイルは拒否される")
    void 空は拒否() {
        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, new byte[0]));
        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, null));
        assertTrue(world.savedImages.isEmpty());
    }

    @Test
    @DisplayName("20MBを超えるファイルは拒否される")
    void 大きすぎるものは拒否() {
        byte[] tooBig = new byte[GeneratedImageUploadService.MAX_UPLOAD_BYTES + 1];
        tooBig[0] = (byte) 0x89;

        InvalidImageUploadException e = assertThrows(InvalidImageUploadException.class,
                () -> service.upload(7L, tooBig));

        assertEquals("ファイルサイズが上限(20MB)を超えています。", e.getMessage());
        assertTrue(world.savedImages.isEmpty());
    }

    @Test
    @DisplayName("先頭がPNGでも中身がデコードできなければ拒否される")
    void 壊れたPNGは拒否() {
        byte[] broken = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, broken));
        assertTrue(world.savedImages.isEmpty());
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
        assertTrue(world.savedImages.isEmpty());
    }

    @Test
    @DisplayName("登録後に、メタ情報除去済みの保存画像をAIタグ付けへ渡す(元ファイルは渡さない)")
    void 保存した画像でタグ付けを依頼する() throws IOException {
        byte[] src = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false)), 1);

        GeneratedImage result = service.upload(7L, src);

        JsonNode sent = world.bodyOf(world.http.requests("POST", VISION_PATH).get(0));
        byte[] sentBytes = Base64.getDecoder().decode(sent.get("imageBase64").asText());
        assertEquals("image/jpeg", sent.get("mimeType").asText());
        assertEquals(7L, sent.get("projectId").asLong());
        assertArrayEquals(Files.readAllBytes(world.storageDir.resolve(result.getFilePath())), sentBytes);
        assertFalse(new String(sentBytes, StandardCharsets.ISO_8859_1).contains(UploadImageFixtures.GPS_MARKER));
        assertEquals("[\"猫\"]", world.images.get(result.getId()).getTagsJson(), "提案されたタグが画像の行に残る");
    }

    @Test
    @DisplayName("タグ付けの依頼(スレッドプール満杯など)が失敗してもアップロードは成功する")
    void タグ付け依頼の失敗はアップロードを失敗させない() {
        CountDownLatch release = new CountDownLatch(1);
        try {
            UploadedImageTagService rejected = AsyncProxies.async(
                    world.uploadedImageTagService, "imageTaggingExecutor", AsyncProxies.saturated(release));
            GeneratedImageUploadService saturated =
                    new GeneratedImageUploadService(world.imageResizeService, world.creationService, rejected);

            GeneratedImage result = saturated.upload(7L,
                    UploadImageFixtures.png(UploadImageFixtures.solid(100, 100, Color.RED, false)));

            assertEquals(result, world.images.get(result.getId()));
            assertTrue(world.http.requests("POST", VISION_PATH).isEmpty(), "満杯のExecutorはタグ付けを始めない");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("拒否された画像ではタグ付けを依頼しない")
    void 拒否ではタグ付けしない() {
        assertThrows(InvalidImageUploadException.class, () -> service.upload(7L, new byte[] {1, 2, 3}));

        assertTrue(world.http.requests("POST", VISION_PATH).isEmpty());
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

    /** アップロードして、保存画像とタグ付けへ渡された(mime, bytes)を返す。 */
    private record Tagged(GeneratedImage stored, String mime, byte[] sent) {
    }

    private Tagged uploadAndCaptureTagging(byte[] src) {
        GeneratedImage saved = service.upload(7L, src);
        JsonNode sent = world.bodyOf(world.http.requests("POST", VISION_PATH).get(0));
        return new Tagged(saved, sent.get("mimeType").asText(),
                Base64.getDecoder().decode(sent.get("imageBase64").asText()));
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

        assertEquals(2000, tagged.stored().getWidth());
        assertEquals(1500, tagged.stored().getHeight());
        assertEquals(2000, ImageIO.read(world.storageDir.resolve(tagged.stored().getFilePath()).toFile()).getWidth());
        assertWithinTaggingLimit(tagged);
        assertEquals("image/jpeg", tagged.mime());
    }

    @Test
    @DisplayName("長辺が1568px以下でもバイト数が閾値を超える画像は、タグ付け用コピーだけを縮める(issue #1657)")
    void 寸法が小さくてもバイト数が大きければ縮める() throws IOException {
        byte[] src = UploadImageFixtures.png(noisy(1560, 1250, false));
        assertTrue(src.length > FIVE_MB, "fixture must exceed 5MB: " + src.length);

        Tagged tagged = uploadAndCaptureTagging(src);

        assertEquals(1560, tagged.stored().getWidth());
        assertEquals(1250, tagged.stored().getHeight());
        assertEquals("image/png", tagged.stored().getMimeType());
        assertWithinTaggingLimit(tagged);
    }

    @Test
    @DisplayName("1568px超の透過PNGは、縮小してもPNGで閾値を超えるなら、タグ付け用コピーだけJPEGにする(issue #1657)")
    void 透過PNGが縮小後も大きければタグ付け用だけJPEG化する() throws IOException {
        byte[] src = UploadImageFixtures.png(noisy(1700, 1300, true));
        assertTrue(src.length > FIVE_MB, "fixture must exceed 5MB: " + src.length);

        Tagged tagged = uploadAndCaptureTagging(src);

        assertEquals(1700, tagged.stored().getWidth());
        assertEquals("image/png", tagged.stored().getMimeType());
        assertEquals("image/jpeg", tagged.mime());
        assertWithinTaggingLimit(tagged);
    }
}
