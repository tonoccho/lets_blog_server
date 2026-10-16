package com.letsblog.media.service;

import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPEGのExif Orientationの読み取り(issue #1819で分岐を通すために追加)。
 *
 * <p>{@code ImageResizeService}は公開メソッド(再エンコード・編集・リサイズ)の中で、JPEGのAPP1(Exif)セグメントから
 * Orientationタグを自前で読む。8方向すべての変換と、Exifが壊れている・Orientationが無い・値が範囲外のときに
 * 回転せず元の向きのまま扱うことを、実際のJPEGのバイト列(SOIの直後に手組みのセグメントを差し込んだもの)で確かめる。
 * 画像のデコードは本物のImageIOが行うので、デコードできないバイト列はここへ来る前に拒否される。
 */
@DisplayName("ImageResizeService: JPEGのOrientation(Exif)の読み取り")
class ImageResizeServiceOrientationTest {

    private static final int W = 80;
    private static final int H = 40;

    private final ImageResizeService service = new ImageResizeService();

    /** 左上=赤・右上=緑・左下=青・右下=黄の4象限の画像。 */
    private static byte[] quadrantJpeg() {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, W / 2, H / 2);
            g.setColor(Color.GREEN);
            g.fillRect(W / 2, 0, W / 2, H / 2);
            g.setColor(Color.BLUE);
            g.fillRect(0, H / 2, W / 2, H / 2);
            g.setColor(Color.YELLOW);
            g.fillRect(W / 2, H / 2, W / 2, H / 2);
        } finally {
            g.dispose();
        }
        return UploadImageFixtures.jpeg(image);
    }

    /** SOIの直後に{@code segments}を差し込んだJPEG。 */
    private static byte[] insertAfterSoi(byte[] jpeg, byte[]... segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        for (byte[] segment : segments) {
            out.writeBytes(segment);
        }
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] u16(int value, boolean littleEndian) {
        return littleEndian
                ? new byte[] {(byte) value, (byte) (value >> 8)}
                : new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] u32(int value, boolean littleEndian) {
        return littleEndian
                ? new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)}
                : new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] app1(byte[] payload) {
        return segment(0xE1, payload);
    }

    private static byte[] segment(int marker, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(marker);
        out.writeBytes(new byte[] {(byte) ((payload.length + 2) >> 8), (byte) (payload.length + 2)});
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** "Exif\0\0" + TIFFヘッダ(byteOrder・IFD0のオフセット)+ IFD0(宣言する件数と、実際に置く(タグ, 値)の組)。 */
    private static byte[] exif(String byteOrder, int ifdOffset, int declaredCount, int[][] entries) {
        boolean le = !byteOrder.equals("MM");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(byteOrder.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(u16(0x2A, le));
        out.writeBytes(u32(ifdOffset, le));
        out.writeBytes(u16(declaredCount, le));
        for (int[] entry : entries) {
            out.writeBytes(u16(entry[0], le));
            out.writeBytes(u16(3, le));
            out.writeBytes(u32(1, le));
            out.writeBytes(u16(entry[1], le));
            out.writeBytes(new byte[] {0, 0});
        }
        out.writeBytes(new byte[] {0, 0, 0, 0});
        return out.toByteArray();
    }

    private static byte[] orientationExif(String byteOrder, int orientation) {
        return exif(byteOrder, 8, 1, new int[][] {{0x0112, orientation}});
    }

    private BufferedImage reencode(byte[] jpeg) throws IOException {
        ImageResizeService.ReencodedImage result = service.reencodeKeepingResolution(jpeg, "image/jpeg");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertNotNull(decoded);
        assertEquals(result.width(), decoded.getWidth());
        assertEquals(result.height(), decoded.getHeight());
        return decoded;
    }

    private static String colorAt(BufferedImage image, int x, int y) {
        Color c = new Color(image.getRGB(x, y));
        if (c.getRed() > 180 && c.getGreen() < 90 && c.getBlue() < 90) {
            return "R";
        }
        if (c.getGreen() > 180 && c.getRed() < 90 && c.getBlue() < 90) {
            return "G";
        }
        if (c.getBlue() > 180 && c.getRed() < 90 && c.getGreen() < 90) {
            return "B";
        }
        if (c.getRed() > 180 && c.getGreen() > 180 && c.getBlue() < 90) {
            return "Y";
        }
        return "?" + c;
    }

    /** 出力の4象限の中心(左上・右上・左下・右下)の色。 */
    private static String quadrants(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        return colorAt(image, w / 4, h / 4) + colorAt(image, 3 * w / 4, h / 4)
                + colorAt(image, w / 4, 3 * h / 4) + colorAt(image, 3 * w / 4, 3 * h / 4);
    }

    // ------------------------------------------------------------------
    // 8方向(EXIF仕様どおり)
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "Orientation={0} -> {1}x{2}, 左上/右上/左下/右下={3}")
    @CsvSource({
            "1, 80, 40, RGBY",
            "2, 80, 40, GRYB",
            "3, 80, 40, YBGR",
            "4, 80, 40, BYRG",
            "5, 40, 80, RBGY",
            "6, 40, 80, BRYG",
            "7, 40, 80, YGBR",
            "8, 40, 80, GYRB"})
    @DisplayName("8方向のOrientationを、表示される向きへ回転・反転して保存する")
    void 八方向の変換(int orientation, int width, int height, String expectedQuadrants) throws IOException {
        BufferedImage out = reencode(UploadImageFixtures.withExif(quadrantJpeg(), orientation));

        assertEquals(width, out.getWidth());
        assertEquals(height, out.getHeight());
        assertEquals(expectedQuadrants, quadrants(out));
    }

    @Test
    @DisplayName("ビッグエンディアン(MM)のExifも読める")
    void ビッグエンディアン() throws IOException {
        BufferedImage out = reencode(insertAfterSoi(quadrantJpeg(), app1(orientationExif("MM", 6))));

        assertEquals(40, out.getWidth());
        assertEquals("BRYG", quadrants(out));
    }

    @Test
    @DisplayName("Orientationが他のタグの後ろにあっても見つける")
    void 他のタグの後ろのOrientation() throws IOException {
        byte[] exif = exif("II", 8, 2, new int[][] {{0x010F, 1}, {0x0112, 8}});

        BufferedImage out = reencode(insertAfterSoi(quadrantJpeg(), app1(exif)));

        assertEquals(40, out.getWidth());
        assertEquals("GYRB", quadrants(out));
    }

    // ------------------------------------------------------------------
    // 回転しない(Orientation 1 と同じ扱い)になる入力
    // ------------------------------------------------------------------

    private void assertNotRotated(byte[] jpeg) throws IOException {
        BufferedImage out = reencode(jpeg);
        assertEquals(W, out.getWidth());
        assertEquals(H, out.getHeight());
        assertEquals("RGBY", quadrants(out));
    }

    @Test
    @DisplayName("JPEGの形式でないバイト列(JPEGと申告されたPNG)は回転しない")
    void JPEGでないバイト列() throws IOException {
        byte[] png = UploadImageFixtures.png(ImageIO.read(new ByteArrayInputStream(quadrantJpeg())));

        assertNotRotated(png);
    }

    @Test
    @DisplayName("Orientationが1〜8の範囲外(0・9)なら回転しない")
    void 範囲外のOrientation() throws IOException {
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(orientationExif("II", 0))));
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(orientationExif("II", 9))));
    }

    @Test
    @DisplayName("範囲外のOrientationを持つExifの後ろに、有効なOrientationを持つExifがあればそれを使う")
    void 範囲外の後ろの有効なExif() throws IOException {
        BufferedImage out = reencode(insertAfterSoi(
                quadrantJpeg(), app1(orientationExif("II", 9)), app1(orientationExif("II", 6))));

        assertEquals(40, out.getWidth());
        assertEquals("BRYG", quadrants(out));
    }

    @Test
    @DisplayName("Orientationタグを持たないExif(0件・他のタグだけ・件数の宣言だけが多い)は回転しない")
    void Orientationタグが無い() throws IOException {
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(exif("II", 8, 0, new int[][] {}))));
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(exif("II", 8, 1, new int[][] {{0x010F, 1}}))));
        // 件数を65535と宣言しても、ファイルの末尾を越える手前で読むのをやめる。
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(exif("II", 8, 0xFFFF, new int[][] {{0x010F, 1}}))));
    }

    @Test
    @DisplayName("TIFFヘッダのバイトオーダーがII・MMのどちらでもなければ回転しない")
    void バイトオーダーが不正() throws IOException {
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(orientationExif("IM", 6))));
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(orientationExif("MI", 6))));
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(orientationExif("ZZ", 6))));
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(orientationExif("Mx", 6))));
    }

    @Test
    @DisplayName("IFD0のオフセットが負・ファイルの外なら回転しない")
    void IFDのオフセットが不正() throws IOException {
        assertNotRotated(insertAfterSoi(quadrantJpeg(),
                app1(exif("II", Integer.MIN_VALUE, 1, new int[][] {{0x0112, 6}}))));
        assertNotRotated(insertAfterSoi(quadrantJpeg(),
                app1(exif("II", 0x00100000, 1, new int[][] {{0x0112, 6}}))));
    }

    @Test
    @DisplayName("APP1が短すぎる・Exifの見出しが違う(各バイトが違う)なら回転しない")
    void Exifの見出しが不正() throws IOException {
        byte[] orientation = orientationExif("II", 6);
        // 短い(XMPなどの小さなAPP1)。
        assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(new byte[] {'E', 'x', 'i', 'f', 0, 0, 0})));
        // 見出しの6バイトのうち、1バイトずつ別の値にする。
        for (int i = 0; i < 6; i++) {
            byte[] broken = orientation.clone();
            broken[i] = (byte) (broken[i] == 'z' ? 'y' : 'z');
            assertNotRotated(insertAfterSoi(quadrantJpeg(), app1(broken)));
        }
    }

    @Test
    @DisplayName("Exif以外のAPP1(XMPなど)を読み飛ばして、後ろのExifのOrientationを使う")
    void XMPの後ろのExif() throws IOException {
        byte[] xmp = "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes(StandardCharsets.ISO_8859_1);

        BufferedImage out = reencode(insertAfterSoi(quadrantJpeg(), app1(xmp), app1(orientationExif("II", 6))));

        assertEquals(40, out.getWidth());
        assertEquals("BRYG", quadrants(out));
    }

    // ------------------------------------------------------------------
    // セグメントの読み飛ばし
    // ------------------------------------------------------------------

    @Test
    @DisplayName("パラメータを持たないマーカー(TEM・RSTn)は2バイトだけ読み飛ばして先へ進む")
    void パラメータを持たないマーカー() throws IOException {
        byte[] tem = {(byte) 0xFF, 0x01};
        byte[] rst = {(byte) 0xFF, (byte) 0xD0};

        BufferedImage out = reencode(insertAfterSoi(quadrantJpeg(), tem, rst, app1(orientationExif("II", 6))));

        assertEquals(40, out.getWidth());
        assertEquals("BRYG", quadrants(out));
    }

    @Test
    @DisplayName("セグメントの間にマーカーでないバイトがあると、そこで読むのをやめて回転しない")
    void セグメントの間のごみ() throws IOException {
        // デコーダは次のマーカーまで読み飛ばして画像を読めるが、Orientationの読み取りはそこで打ち切る。
        assertNotRotated(insertAfterSoi(quadrantJpeg(), new byte[] {0x00}, app1(orientationExif("II", 6))));
    }

    @Test
    @DisplayName("マーカーの前の詰め物(0xFF)があると、長さを読み違えるので読むのをやめて回転しない")
    void マーカーの前の詰め物() throws IOException {
        assertNotRotated(insertAfterSoi(
                quadrantJpeg(), new byte[] {(byte) 0xFF}, app1(orientationExif("II", 6))));
    }

    @Test
    @DisplayName("長さが2未満のセグメントがあると、読むのをやめて回転しない")
    void 長さが短すぎるセグメント() throws IOException {
        byte[] zeroLength = {(byte) 0xFF, (byte) 0xE3, 0x00, 0x00};

        assertNotRotated(insertAfterSoi(quadrantJpeg(), zeroLength, app1(orientationExif("II", 6))));
    }

    @Test
    @DisplayName("Orientationを持つExifが無いJPEGは回転しない")
    void Exifが無い() throws IOException {
        assertNotRotated(quadrantJpeg());
        assertTrue(quadrantJpeg().length > 4);
    }

    // ------------------------------------------------------------------
    // デコードの時点で拒否される入力(docs/coverage/media-service.md の「届かない分岐」の根拠)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SOIが2つあるJPEG・SOSより前にEOIがあるJPEG・長さがファイルの外へはみ出すセグメントを持つJPEGは、"
            + "Orientationを読む前のデコードで拒否される")
    void デコードで拒否される入力() {
        byte[] secondSoi = {(byte) 0xFF, (byte) 0xD8};
        byte[] eoiBeforeScan = {(byte) 0xFF, (byte) 0xD9};
        byte[] segmentPastEnd = {(byte) 0xFF, (byte) 0xE3, (byte) 0xFF, (byte) 0xFF, 1, 2, 3};

        for (byte[] extra : new byte[][] {secondSoi, eoiBeforeScan, segmentPastEnd}) {
            assertThrows(InvalidImageUploadException.class,
                    () -> service.reencodeKeepingResolution(insertAfterSoi(quadrantJpeg(), extra), "image/jpeg"));
        }
    }

    @Test
    @DisplayName("4バイト未満のバイト列は、画像として読み込めず拒否される")
    void 短すぎるバイト列() {
        assertThrows(InvalidImageUploadException.class,
                () -> service.reencodeKeepingResolution(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "image/jpeg"));
        assertThrows(InvalidImageUploadException.class,
                () -> service.reencodeKeepingResolution(new byte[] {(byte) 0xFF, 0x00, 0x00, 0x00, 0x00}, "image/jpeg"));
    }
}
