package com.letsblog.publishing.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageResizeServiceの分岐の補完(issue #1815)。{@code ImageResizeServiceTest}が通さない、EXIF Orientationの全8方向、
 * Exifセグメントの読み取りの境界(壊れたヘッダー・範囲外のオフセット・未知のタグ)、MIME未指定の入力、
 * 透過画像の縮小、そして画像の書き出し(JPEG/PNG)がJVMに無い環境へのフォールバックを検証する。
 * 実物の{@code ImageResizeService}に実際のバイト列を渡し、結果のバイト列(寸法・向き・メタ情報)で確かめる。
 */
class ImageResizeServiceBranchTest {

    private final ImageResizeService service = new ImageResizeService();

    // ---- 画像の組み立て ----

    private static final int RED = 0xFF0000;
    private static final int BLUE = 0x0000FF;
    private static final int GREEN = 0x00FF00;
    private static final int WHITE = 0xFFFFFF;

    /** 32x32。左上=赤、右上=青、左下=緑、右下=白(各16x16)。 */
    private static BufferedImage quadrants() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                int color = y < 16 ? (x < 16 ? RED : BLUE) : (x < 16 ? GREEN : WHITE);
                image.setRGB(x, y, color);
            }
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static char colorAt(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        boolean r = ((rgb >> 16) & 0xFF) > 150;
        boolean g = ((rgb >> 8) & 0xFF) > 150;
        boolean b = (rgb & 0xFF) > 150;
        if (r && g && b) {
            return 'W';
        }
        if (r && !g && !b) {
            return 'R';
        }
        if (!r && g && !b) {
            return 'G';
        }
        if (!r && !g && b) {
            return 'B';
        }
        return '?';
    }

    /** 左上・右上・左下・右下の順に4文字にする。 */
    private static String corners(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return "" + colorAt(image, w / 4, h / 4) + colorAt(image, w * 3 / 4, h / 4)
                + colorAt(image, w / 4, h * 3 / 4) + colorAt(image, w * 3 / 4, h * 3 / 4);
    }

    // ---- JPEGへのセグメント挿入 ----

    private static void be16(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void le16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void be32(ByteArrayOutputStream out, int value) {
        be16(out, (value >> 16) & 0xFFFF);
        be16(out, value & 0xFFFF);
    }

    private static void le32(ByteArrayOutputStream out, int value) {
        le16(out, value & 0xFFFF);
        le16(out, (value >> 16) & 0xFFFF);
    }

    /** TIFFヘッダー+IFD0。entriesは「タグ, 値」の並び(型はSHORT、個数1)。 */
    private static byte[] tiff(boolean little, int ifdOffset, int entryCount, int... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(little ? 'I' : 'M');
        out.write(little ? 'I' : 'M');
        if (little) {
            le16(out, 42);
            le32(out, ifdOffset);
            le16(out, entryCount);
        } else {
            be16(out, 42);
            be32(out, ifdOffset);
            be16(out, entryCount);
        }
        for (int i = 0; i + 1 < entries.length; i += 2) {
            if (little) {
                le16(out, entries[i]);
                le16(out, 3);
                le32(out, 1);
                le16(out, entries[i + 1]);
                le16(out, 0);
                le32(out, 0);
            } else {
                be16(out, entries[i]);
                be16(out, 3);
                be32(out, 1);
                be16(out, entries[i + 1]);
                be16(out, 0);
                be32(out, 0);
            }
        }
        return out.toByteArray();
    }

    private static byte[] exifPayload(byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        return out.toByteArray();
    }

    private static byte[] segment(int marker, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        be16(out, payload.length + 2);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** SOIの直後に、指定のバイト列(セグメント等)を挿入したJPEG。 */
    private static byte[] jpegWith(BufferedImage image, byte[]... inserted) throws Exception {
        byte[] jpeg = encode(image, "jpg");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        for (byte[] part : inserted) {
            out.writeBytes(part);
        }
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] exifApp1(boolean little, int orientation) {
        return segment(0xE1, exifPayload(tiff(little, 8, 1, 0x0112, orientation)));
    }

    private BufferedImage resizedImage(byte[] jpeg) throws Exception {
        byte[] result = service.resizeToLongEdge(jpeg, "image/jpeg", 1300);
        return ImageIO.read(new ByteArrayInputStream(result));
    }

    // ---- EXIF Orientation: 全8方向 ----

    @ParameterizedTest(name = "Orientation {0} → {1}")
    @CsvSource({
            "1, RBGW",
            "2, BRWG",
            "3, WGBR",
            "4, GWRB",
            "5, RGBW",
            "6, GRWB",
            "7, WBGR",
            "8, BWRG",
    })
    void resizeToLongEdge_Orientationに従い正しい向きへ正規化する(int orientation, String expectedCorners) throws Exception {
        BufferedImage output = resizedImage(jpegWith(quadrants(), exifApp1(false, orientation)));

        assertEquals(expectedCorners, corners(output));
    }

    @Test
    void resizeToLongEdge_リトルエンディアンのExifも読める() throws Exception {
        BufferedImage output = resizedImage(jpegWith(quadrants(), exifApp1(true, 3)));

        assertEquals("WGBR", corners(output));
    }

    // ---- Exifセグメントの読み取り(壊れた・想定外の入力は向きを変えずに処理する) ----

    private void assertOrientationIgnored(byte[]... inserted) throws Exception {
        BufferedImage output = resizedImage(jpegWith(quadrants(), inserted));
        assertEquals("RBGW", corners(output), "向きは変えない");
    }

    @Test
    void resizeToLongEdge_PNGのバイト列がjpegと名乗っても向きは読まず画像として処理する() throws Exception {
        byte[] png = encode(quadrants(), "png");

        byte[] result = service.resizeToLongEdge(png, "image/jpeg", 1300);

        assertEquals("RBGW", corners(ImageIO.read(new ByteArrayInputStream(result))));
    }

    @Test
    void resizeToLongEdge_Exifでない小さなAPP1は読み飛ばす() throws Exception {
        assertOrientationIgnored(segment(0xE1, new byte[] {1, 2, 3}));
    }

    @Test
    void resizeToLongEdge_XMPなどExifの印が無いAPP1は読み飛ばし_後続のExifを読む() throws Exception {
        byte[] xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\0<x/>".getBytes());
        BufferedImage output = resizedImage(jpegWith(quadrants(), xmp, exifApp1(false, 3)));

        assertEquals("WGBR", corners(output));
    }

    @ParameterizedTest(name = "Exifの印の{0}バイト目が違う")
    @CsvSource({"0", "1", "2", "3", "4", "5"})
    void resizeToLongEdge_Exifの印が1バイトでも違えば向きを読まない(int index) throws Exception {
        byte[] payload = exifPayload(tiff(false, 8, 1, 0x0112, 3));
        payload[index] = (byte) (payload[index] == 'X' ? 'Y' : 'X');

        assertOrientationIgnored(segment(0xE1, payload));
    }

    @Test
    void resizeToLongEdge_TIFFのバイト順の印が不正なら向きを読まない() throws Exception {
        byte[] tiff = tiff(false, 8, 1, 0x0112, 3);
        tiff[0] = 'X';
        tiff[1] = 'X';
        assertOrientationIgnored(segment(0xE1, exifPayload(tiff)));

        byte[] mixed = tiff(false, 8, 1, 0x0112, 3);
        mixed[0] = 'I';
        mixed[1] = 'M';
        assertOrientationIgnored(segment(0xE1, exifPayload(mixed)));

        byte[] mixed2 = tiff(false, 8, 1, 0x0112, 3);
        mixed2[0] = 'M';
        mixed2[1] = 'I';
        assertOrientationIgnored(segment(0xE1, exifPayload(mixed2)));
    }

    @Test
    void resizeToLongEdge_IFDのオフセットが範囲外なら向きを読まない() throws Exception {
        assertOrientationIgnored(segment(0xE1, exifPayload(tiff(false, 0x7FFFFFF0, 1, 0x0112, 3))));
        // 負のオフセット(tiffStartを引くと先頭より前になる)
        assertOrientationIgnored(segment(0xE1, exifPayload(tiff(false, -100000, 1, 0x0112, 3))));
    }

    @Test
    void resizeToLongEdge_Orientation以外のタグだけなら向きを読まない() throws Exception {
        assertOrientationIgnored(segment(0xE1, exifPayload(tiff(false, 8, 1, 0x0100, 3))));
    }

    @Test
    void resizeToLongEdge_エントリ数が実際より多くてもファイルの終わりで読み取りを止める() throws Exception {
        assertOrientationIgnored(segment(0xE1, exifPayload(tiff(false, 8, 0xFFFF, 0x0100, 3))));
    }

    @ParameterizedTest(name = "Orientationの値{0}は範囲外")
    @CsvSource({"0", "9"})
    void resizeToLongEdge_Orientationの値が1から8の範囲外なら向きを読まない(int value) throws Exception {
        assertOrientationIgnored(exifApp1(false, value));
    }

    @Test
    void resizeToLongEdge_Exifの前にRSTやTEMのマーカーがあっても読み飛ばして後続のExifを読む() throws Exception {
        byte[] rst = {(byte) 0xFF, (byte) 0xD0};
        byte[] tem = {(byte) 0xFF, 0x01};

        BufferedImage output = resizedImage(jpegWith(quadrants(), rst, tem, exifApp1(false, 3)));

        assertEquals("WGBR", corners(output));
    }

    @Test
    void resizeToLongEdge_セグメントの間に余分なバイトがあっても読み取りを止める() throws Exception {
        byte[] garbage = {0x00};

        BufferedImage output = resizedImage(jpegWith(quadrants(), garbage, exifApp1(false, 3)));

        assertEquals("RBGW", corners(output), "余分なバイトの先のExifは読まない(向きは変えない)");
    }

    @Test
    void resizeToLongEdge_セグメント長が2未満のAPP1があればそこで読み取りを止める() throws Exception {
        // 長さ1のAPP1(デコーダーは読み飛ばして画像を読める)。その後ろのExifは読まない。
        byte[] shortSegment = {(byte) 0xFF, (byte) 0xE1, 0, 1};

        BufferedImage output = resizedImage(jpegWith(quadrants(), shortSegment, exifApp1(false, 3)));

        assertEquals("RBGW", corners(output));
    }

    // ---- MIME・透過・書き出しの分岐 ----

    @Test
    void resizeToLongEdge_MIMEが未指定でも画像として処理しPNGで返す() throws Exception {
        ImageResizeService.ResizeResult result =
                service.resizeToLongEdge(encode(quadrants(), "png"), null, 16, false);

        assertEquals("image/png", result.mimeType());
        assertEquals(16, ImageIO.read(new ByteArrayInputStream(result.data())).getWidth());
    }

    @Test
    void resizeToLongEdge_image_jpgという別名もJPEGとして扱う() throws Exception {
        byte[] jpeg = jpegWith(quadrants(), exifApp1(false, 3));

        ImageResizeService.ResizeResult result = service.resizeToLongEdge(jpeg, "image/jpg", 1300, false);

        assertEquals("image/jpeg", result.mimeType());
        assertEquals("WGBR", corners(ImageIO.read(new ByteArrayInputStream(result.data()))));
    }

    @Test
    void resizeToLongEdge_透過のあるPNGを縮小しても透過を保つ() throws Exception {
        BufferedImage transparent = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);

        ImageResizeService.ResizeResult result =
                service.resizeToLongEdge(encode(transparent, "png"), "image/png", 100, true);

        assertEquals("image/png", result.mimeType());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertEquals(100, image.getWidth());
        assertTrue(image.getColorModel().hasAlpha());
    }

    @Test
    void resizeToLongEdge_JPEGでない画像も透過なしならJPEGへ変換して縮小できる() throws Exception {
        BufferedImage grayish = new BufferedImage(200, 100, BufferedImage.TYPE_BYTE_GRAY);

        ImageResizeService.ResizeResult result =
                service.resizeToLongEdge(encode(grayish, "png"), "image/png", 100, true);

        assertEquals("image/jpeg", result.mimeType());
        assertEquals(100, ImageIO.read(new ByteArrayInputStream(result.data())).getWidth());
    }

    /** JVMに、指定の形式の画像書き出し(ImageWriter)が無い環境を作って実行し、元に戻す。 */
    private <T> T withoutWriter(String formatName, Supplier<T> action) {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<ImageWriterSpi> removed = new ArrayList<>();
        Iterator<ImageWriterSpi> providers = registry.getServiceProviders(ImageWriterSpi.class, false);
        while (providers.hasNext()) {
            ImageWriterSpi spi = providers.next();
            if (Arrays.stream(spi.getFormatNames()).anyMatch(name -> name.equalsIgnoreCase(formatName))) {
                removed.add(spi);
            }
        }
        removed.forEach(spi -> registry.deregisterServiceProvider(spi, ImageWriterSpi.class));
        try {
            return action.get();
        } finally {
            removed.forEach(spi -> registry.registerServiceProvider(spi, ImageWriterSpi.class));
        }
    }

    @Test
    void resizeToLongEdge_JPEGを書き出せない環境では元のバイト列のまま返す() throws Exception {
        byte[] jpeg = encode(quadrants(), "jpg");

        ImageResizeService.ResizeResult result =
                withoutWriter("jpeg", () -> service.resizeToLongEdge(jpeg, "image/jpeg", 1300, false));

        assertArrayEquals(jpeg, result.data());
        assertEquals("image/jpeg", result.mimeType());
    }

    @Test
    void resizeToLongEdge_PNGを書き出せない環境では元のバイト列のまま返す() throws Exception {
        byte[] png = encode(quadrants(), "png");

        ImageResizeService.ResizeResult result =
                withoutWriter("png", () -> service.resizeToLongEdge(png, "image/png", 1300, false));

        assertArrayEquals(png, result.data());
        assertFalse(result.data().length == 0);
    }
}
