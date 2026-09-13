package com.letsblog.identity.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1241 AC3: 保存するアバター画像が512x512の正方形で、EXIF等のメタ情報を含まないことの検証。
 *
 * <p>media-serviceの{@code ImageResizeService}(EXIF Orientation焼き込み・メタ情報削除)と同じ考え方を
 * identity-service側に移植したもの(モジュール境界のため直接共有はできない。issue本文の実装メモ参照)。
 */
class AvatarImageProcessorTest {

    private final AvatarImageProcessor processor = new AvatarImageProcessor();

    private byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private BufferedImage solidImage(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * APP1(Exif)セグメント(0xFFE1)を含むJPEGを組み立てる。
     *
     * <p>{@code IIOMetadata}のツリー編集APIはJPEGプラグインの実装差でエンコーダごとに
     * 期待するノード形状が異なり壊れやすいため、素のJPEGをエンコードしたうえで
     * SOI(0xFFD8)の直後にAPP1セグメントを手でスプライスする。JPEGのデコーダは未知の
     * マーカーセグメントを読み飛ばすため、この位置に挿入しても画像としては正しく読める。
     */
    private byte[] encodeJpegWithExif(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        byte[] plain = out.toByteArray();

        byte[] exifPayload = buildMinimalExifPayload();
        int segmentLength = exifPayload.length + 2; // セグメント長自身(2バイト)を含む
        ByteArrayOutputStream spliced = new ByteArrayOutputStream();
        spliced.write(plain, 0, 2); // SOI
        spliced.write(0xFF);
        spliced.write(0xE1); // APP1
        spliced.write((segmentLength >> 8) & 0xFF);
        spliced.write(segmentLength & 0xFF);
        spliced.write(exifPayload);
        spliced.write(plain, 2, plain.length - 2);
        return spliced.toByteArray();
    }

    /** "Exif\0\0" + 最小限のTIFFヘッダ(IFDエントリ無し)。 */
    private byte[] buildMinimalExifPayload() {
        return new byte[] {
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0,
                0, 0
        };
    }

    /**
     * SOI直後にAPP1(Exif)セグメントを差し込んだJPEGを組み立てる。Orientationタグ(0x0112)を
     * 含むTIFF IFDを{@code exifPayload}としてそのまま使う版({@link #encodeJpegWithExif}の
     * 汎用化)。
     */
    private byte[] encodeJpegWithExifPayload(BufferedImage image, byte[] exifPayload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        byte[] plain = out.toByteArray();

        int segmentLength = exifPayload.length + 2;
        ByteArrayOutputStream spliced = new ByteArrayOutputStream();
        spliced.write(plain, 0, 2);
        spliced.write(0xFF);
        spliced.write(0xE1);
        spliced.write((segmentLength >> 8) & 0xFF);
        spliced.write(segmentLength & 0xFF);
        spliced.write(exifPayload);
        spliced.write(plain, 2, plain.length - 2);
        return spliced.toByteArray();
    }

    /**
     * Orientationタグ(0x0112、SHORT、値{@code orientation})を1件だけ持つTIFF IFDの
     * "Exif\0\0"付きペイロードを組み立てる。{@code littleEndian}で"II"/"MM"を切り替える。
     */
    private byte[] buildExifPayloadWithOrientation(int orientation, boolean littleEndian) {
        return buildExifPayloadWithEntries(littleEndian, new int[][] {{0x0112, orientation}});
    }

    /**
     * 複数のIFDエントリを持つTIFF IFDペイロードを組み立てる。各エントリは{tag, shortValue}。
     * Orientationタグ探索ループが「一致しないタグをスキップする」経路を検証するのに使う。
     */
    private byte[] buildExifPayloadWithEntries(boolean littleEndian, int[][] entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        if (littleEndian) {
            out.writeBytes(new byte[] {'I', 'I'});
        } else {
            out.writeBytes(new byte[] {'M', 'M'});
        }
        writeInt16(out, 42, littleEndian);
        writeInt32(out, 8, littleEndian); // IFDオフセット(TIFFヘッダ直後)
        writeInt16(out, entries.length, littleEndian);
        for (int[] entry : entries) {
            writeInt16(out, entry[0], littleEndian); // tag
            writeInt16(out, 3, littleEndian); // type = SHORT
            writeInt32(out, 1, littleEndian); // count
            writeInt16(out, entry[1], littleEndian); // value(SHORTなので先頭2バイト)
            writeInt16(out, 0, littleEndian); // valueフィールドの残り2バイト(パディング)
        }
        writeInt32(out, 0, littleEndian); // 次のIFDオフセット(無し)
        return out.toByteArray();
    }

    private void writeInt16(ByteArrayOutputStream out, int value, boolean littleEndian) {
        int b0 = value & 0xFF;
        int b1 = (value >> 8) & 0xFF;
        if (littleEndian) {
            out.write(b0);
            out.write(b1);
        } else {
            out.write(b1);
            out.write(b0);
        }
    }

    private void writeInt32(ByteArrayOutputStream out, int value, boolean littleEndian) {
        int b0 = value & 0xFF;
        int b1 = (value >> 8) & 0xFF;
        int b2 = (value >> 16) & 0xFF;
        int b3 = (value >> 24) & 0xFF;
        if (littleEndian) {
            out.write(b0);
            out.write(b1);
            out.write(b2);
            out.write(b3);
        } else {
            out.write(b3);
            out.write(b2);
            out.write(b1);
            out.write(b0);
        }
    }

    @Test
    void JPEG入力を512x512のJPEGへ変換する() throws IOException {
        byte[] source = encodeJpegWithExif(solidImage(1024, 768, Color.RED));

        byte[] result = processor.process(source, "image/jpeg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test
    void PNG入力を512x512のJPEGへ変換する() throws IOException {
        byte[] source = encodePng(solidImage(400, 900, Color.BLUE));

        byte[] result = processor.process(source, "image/png");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test
    void 正方形でない入力は中央で正方形に切り抜いてから縮小する() throws IOException {
        // 幅400 x 高さ200。中央の200x200が切り抜かれ、左右の帯(別色)は結果に残らないはず。
        BufferedImage source = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 400, 200);
            g.setColor(Color.BLACK);
            g.fillRect(100, 0, 200, 200); // 中央200x200だけ黒
        } finally {
            g.dispose();
        }

        byte[] result = processor.process(encodePng(source), "image/png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));

        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
        // 中心画素は黒(中央の正方形領域)のはず
        int centerRgb = decoded.getRGB(256, 256) & 0xFFFFFF;
        assertEquals(0x000000, centerRgb);
    }

    @Test
    void 結果のJPEGバイト列にEXIF_APP1セグメントが含まれない() throws IOException {
        byte[] source = encodeJpegWithExif(solidImage(600, 600, Color.GREEN));

        byte[] result = processor.process(source, "image/jpeg");

        assertFalse(containsApp1Segment(result), "再エンコード後もAPP1(Exif)セグメントが残っている");
    }

    /** JPEGバイト列にAPP1(0xFFE1)マーカーが含まれるか(先頭のSOI直後を走査)。 */
    private boolean containsApp1Segment(byte[] bytes) {
        int offset = 2; // SOIをスキップ
        while (offset + 4 <= bytes.length) {
            if ((bytes[offset] & 0xFF) != 0xFF) {
                break;
            }
            int marker = bytes[offset + 1] & 0xFF;
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                offset += 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            if (marker == 0xE1) {
                return true;
            }
            int segmentLength = ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
            if (segmentLength < 2) {
                break;
            }
            offset += 2 + segmentLength;
        }
        return false;
    }

    @Test
    void 画像として読み込めないバイト列は例外を送出する() {
        byte[] notAnImage = "not an image".getBytes();

        assertThrows(UnsupportedAvatarFormatException.class,
                () -> processor.process(notAnImage, "image/jpeg"));
    }

    @Test
    void 変換結果は透過を持たないRGB画像になる() throws IOException {
        BufferedImage transparent = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = transparent.createGraphics();
        try {
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, 300, 300);
        } finally {
            g.dispose();
        }

        byte[] result = processor.process(encodePng(transparent), "image/png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));

        assertTrue(decoded.getColorModel().getNumComponents() <= 3, "JPEG化されアルファチャンネルが無いはず");
    }

    /**
     * EXIF Orientation(1-8)全パターンで例外なく512x512に変換できること。
     * 各値がapplyOrientationのswitch分岐(flip/rotate各メソッド)を一通り経由する。
     */
    @Test
    void EXIF_Orientationの全パターンで512x512へ変換できる() throws IOException {
        BufferedImage source = solidImage(300, 200, Color.MAGENTA);
        for (int orientation = 1; orientation <= 8; orientation++) {
            byte[] exif = buildExifPayloadWithOrientation(orientation, true);
            byte[] jpeg = encodeJpegWithExifPayload(source, exif);

            byte[] result = processor.process(jpeg, "image/jpeg");

            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
            assertEquals(512, decoded.getWidth(), "orientation=" + orientation);
            assertEquals(512, decoded.getHeight(), "orientation=" + orientation);
        }
    }

    @Test
    void ビッグエンディアン形式のEXIFヘッダも解釈できる() throws IOException {
        byte[] exif = buildExifPayloadWithOrientation(6, false);
        byte[] jpeg = encodeJpegWithExifPayload(solidImage(300, 200, Color.CYAN), exif);

        byte[] result = processor.process(jpeg, "image/jpeg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test
    void Orientationタグが1件目に無くても後続のエントリから見つけられる() throws IOException {
        byte[] exif = buildExifPayloadWithEntries(true, new int[][] {
                {0x0110, 5}, // 無関係なタグ(Model)。Orientationより先に置きスキップさせる
                {0x0112, 6},
        });
        byte[] jpeg = encodeJpegWithExifPayload(solidImage(300, 200, Color.YELLOW), exif);

        byte[] result = processor.process(jpeg, "image/jpeg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test
    void 範囲外のOrientation値は無視して通常どおり処理する() throws IOException {
        byte[] exif = buildExifPayloadWithOrientation(9, true); // 1-8の範囲外
        byte[] jpeg = encodeJpegWithExifPayload(solidImage(300, 200, Color.PINK), exif);

        byte[] result = processor.process(jpeg, "image/jpeg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test
    void mimeTypeがnullでも例外にならずJPEGとして再エンコードされる() throws IOException {
        byte[] source = encodePng(solidImage(300, 300, Color.ORANGE));

        byte[] result = processor.process(source, null);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
    }

    @Test
    void mimeTypeがJPEGだが実体はPNGの場合はOrientation解析をスキップしてそのまま処理する() throws IOException {
        // JPEGのSOIマーカー(0xFFD8)を持たないバイト列(PNGシグネチャ)にimage/jpegを指定した場合、
        // readJpegOrientationの先頭ガード(マジックナンバー不一致)がnormal(1)を返すだけで、
        // 例外にはならず通常どおり処理が続くことを確認する(不整合な入力への防御)。
        byte[] source = encodePng(solidImage(300, 300, Color.LIGHT_GRAY));

        byte[] result = processor.process(source, "image/jpeg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
    }

    @Test
    void mimeTypeがimageJpgでもJPEGとして扱いOrientationを読む() throws IOException {
        byte[] exif = buildExifPayloadWithOrientation(3, true);
        byte[] jpeg = encodeJpegWithExifPayload(solidImage(300, 200, Color.GRAY), exif);

        byte[] result = processor.process(jpeg, "image/jpg");

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(512, decoded.getWidth());
    }
}
