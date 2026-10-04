package com.letsblog.media.testsupport;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 画像アップロード(issue #1599)のテストが使う、実際にデコードできる画像のバイト列を作る道具。
 * 外部ファイルを持たず、テスト実行時に組み立てる。
 */
public final class UploadImageFixtures {

    private UploadImageFixtures() {
    }

    /** GPS位置情報が入っているとみなす目印の文字列。出力にこれが残っていてはならない。 */
    public static final String GPS_MARKER = "GPS-35.6586N-139.7454E";

    public static BufferedImage solid(int width, int height, Color color, boolean alpha) {
        BufferedImage image = new BufferedImage(
                width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 縦に3等分した帯(上=赤・中=緑・下=青)の不透明画像。 */
    public static BufferedImage verticalBands(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, width, height / 3);
            g.setColor(Color.GREEN);
            g.fillRect(0, height / 3, width, height / 3);
            g.setColor(Color.BLUE);
            g.fillRect(0, 2 * height / 3, width, height - 2 * height / 3);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 左半分が赤・右半分が青の不透明画像。 */
    public static BufferedImage leftRedRightBlue(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, width / 2, height);
            g.setColor(Color.BLUE);
            g.fillRect(width / 2, 0, width - width / 2, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    public static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] jpeg(BufferedImage image) {
        try {
            BufferedImage rgb = image;
            if (image.getType() != BufferedImage.TYPE_INT_RGB) {
                rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.95f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgb, null, null), param);
            }
            writer.dispose();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * JPEGのSOIの直後に、Orientationタグと{@link #GPS_MARKER}を持つAPP1(Exif)セグメントを差し込む。
     * {@code orientation}が0のときOrientationタグは入れない(GPS相当の目印だけ入る)。
     */
    public static byte[] withExif(byte[] jpeg, int orientation) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        // TIFFヘッダ(リトルエンディアン)、IFD0オフセット8
        tiff.writeBytes(new byte[] {'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00});
        int entries = orientation == 0 ? 0 : 1;
        tiff.writeBytes(new byte[] {(byte) entries, 0x00});
        if (orientation != 0) {
            tiff.writeBytes(new byte[] {
                    0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, (byte) orientation, 0x00, 0x00, 0x00});
        }
        tiff.writeBytes(new byte[] {0x00, 0x00, 0x00, 0x00});
        tiff.writeBytes(GPS_MARKER.getBytes(StandardCharsets.US_ASCII));

        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        app1.writeBytes(tiff.toByteArray());
        int length = app1.size() + 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1,
                (byte) (length >> 8), (byte) length});
        out.writeBytes(app1.toByteArray());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** IHDRだけが宣言する寸法の、デコードされない(IDATを持たない)PNG。寸法上限の検査用。 */
    public static byte[] pngHeaderOnly(int width, int height) {
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        ihdr.writeBytes(new byte[] {
                (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width,
                (byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height,
                8, 2, 0, 0, 0});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(new byte[] {0, 0, 0, 13});
        out.writeBytes(type);
        out.writeBytes(ihdr.toByteArray());
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr.toByteArray());
        long value = crc.getValue();
        out.writeBytes(new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value});
        return out.toByteArray();
    }

    public static byte[] gif(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(solid(width, height, Color.RED, false), "gif", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
