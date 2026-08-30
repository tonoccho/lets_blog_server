package com.letsblog.publishing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 記事投稿時に画像を長編基準でリサイズし(issue #291)、あわせてEXIF等の埋め込みメタ情報
 * (GPS位置情報等)を削除する(issue #432)。長編が指定px以下でリサイズが不要な場合も、
 * デコード・再エンコードを行うことでメタ情報を削除する。JPEGのEXIF Orientationタグに
 * よる回転指定は、メタ情報削除で見た目が変わらないよう、削除前にピクセルデータへ焼き込む。
 * GIFはアニメーションを壊しうる(ImageIOは最初のフレームしか読めない)ため対象外とする
 * (メタ情報も削除されない)。ImageIOがデコードできない形式(SVG/WebP等)や失敗時は、
 * 元のバイト列をそのまま返す(処理はベストエフォートであり、投稿自体を失敗させない。
 * その場合メタ情報も削除されない)。
 */
@Service
public class ImageResizeService {

    private static final Logger log = LoggerFactory.getLogger(ImageResizeService.class);
    private static final int EXIF_ORIENTATION_TAG = 0x0112;
    private static final float JPEG_QUALITY = 0.85f;

    /** リサイズ/エンコード結果。convertOpaquePngToJpeg指定時はmimeTypeが元と変わりうる。 */
    public record ResizeResult(byte[] data, String mimeType) {
    }

    public byte[] resizeToLongEdge(byte[] originalBytes, String mimeType, int maxLongEdgePx) {
        return resizeToLongEdge(originalBytes, mimeType, maxLongEdgePx, false).data();
    }

    /**
     * convertOpaquePngToJpegがtrueの場合、透過を持たない非JPEG画像(PNG等)をJPEGへ変換して
     * ファイルサイズを削減する(issue #468。ComfyUI生成画像はPNGで容量が大きいため、
     * アイキャッチ/アセットとして追加する際にJPEG化する)。透過を持つ画像はJPEGが透過を
     * 表現できないためPNGのまま維持する。
     */
    public ResizeResult resizeToLongEdge(
            byte[] originalBytes, String mimeType, int maxLongEdgePx, boolean convertOpaquePngToJpeg) {
        if (mimeType != null && mimeType.equalsIgnoreCase("image/gif")) {
            return new ResizeResult(originalBytes, mimeType);
        }
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (decoded == null) {
                return new ResizeResult(originalBytes, mimeType);
            }

            int orientation = isJpeg(mimeType) ? readJpegOrientation(originalBytes) : 1;
            BufferedImage normalized = applyOrientation(decoded, orientation);

            int width = normalized.getWidth();
            int height = normalized.getHeight();
            int longEdge = Math.max(width, height);

            BufferedImage output;
            if (longEdge <= maxLongEdgePx) {
                output = normalized;
            } else {
                double scale = (double) maxLongEdgePx / longEdge;
                int targetWidth = Math.max(1, (int) Math.round(width * scale));
                int targetHeight = Math.max(1, (int) Math.round(height * scale));
                output = scale(normalized, targetWidth, targetHeight);
                log.info("画像をリサイズしました: {}x{} -> {}x{}", width, height, targetWidth, targetHeight);
            }

            ResizeResult encoded = encode(output, mimeType, convertOpaquePngToJpeg);
            return encoded != null ? encoded : new ResizeResult(originalBytes, mimeType);
        } catch (IOException | RuntimeException e) {
            log.warn("画像のリサイズ/メタ情報削除に失敗したため、元のバイト列のままアップロードします: {}", e.getMessage());
            return new ResizeResult(originalBytes, mimeType);
        }
    }

    /**
     * 画像をリサイズせず、EXIF等の埋め込みメタ情報のみを削除する({@code /api/media/upload}用)。
     */
    public byte[] stripMetadata(byte[] originalBytes, String mimeType) {
        return resizeToLongEdge(originalBytes, mimeType, Integer.MAX_VALUE);
    }

    private boolean isJpeg(String mimeType) {
        return mimeType != null && (mimeType.equalsIgnoreCase("image/jpeg") || mimeType.equalsIgnoreCase("image/jpg"));
    }

    private BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, imageType(source));
        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return resized;
    }

    private ResizeResult encode(BufferedImage image, String mimeType, boolean convertOpaquePngToJpeg) throws IOException {
        boolean useJpeg = isJpeg(mimeType) || (convertOpaquePngToJpeg && !image.getColorModel().hasAlpha());
        if (useJpeg) {
            byte[] data = encodeJpeg(image);
            return data != null ? new ResizeResult(data, "image/jpeg") : null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", out);
        return written ? new ResizeResult(out.toByteArray(), "image/png") : null;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(ensureOpaqueRgb(image), null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * JPEGエンコーダはインデックスカラー等の色モデルを受け付けない場合があるため、
     * 常にTYPE_INT_RGBへ描画し直してから書き出す(呼び出し元でhasAlpha=falseの
     * 画像のみに限定して呼ばれるため、透過情報の損失は発生しない)。
     */
    private BufferedImage ensureOpaqueRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private int imageType(BufferedImage image) {
        return image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    /**
     * EXIF Orientationタグ(1-8)に従い、画像を正しい向きに焼き込んだ新しい{@link BufferedImage}を返す。
     * タグが存在しない/normal(1)の場合は引数をそのまま返す。定義は
     * http://sylvana.net/jpegcrop/exif_orientation.html 等で広く知られる標準的なものに従う。
     */
    private BufferedImage applyOrientation(BufferedImage image, int orientation) {
        return switch (orientation) {
            case 2 -> flipHorizontal(image);
            case 3 -> rotate180(image);
            case 4 -> flipVertical(image);
            case 5 -> rotate90Ccw(flipHorizontal(image));
            case 6 -> rotate90Cw(image);
            case 7 -> rotate90Cw(flipHorizontal(image));
            case 8 -> rotate90Ccw(image);
            default -> image;
        };
    }

    private BufferedImage flipHorizontal(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[pixels.length];
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            for (int x = 0; x < w; x++) {
                out[rowBase + (w - 1 - x)] = pixels[rowBase + x];
            }
        }
        BufferedImage dst = new BufferedImage(w, h, imageType(src));
        dst.setRGB(0, 0, w, h, out, 0, w);
        return dst;
    }

    private BufferedImage flipVertical(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[pixels.length];
        for (int y = 0; y < h; y++) {
            System.arraycopy(pixels, y * w, out, (h - 1 - y) * w, w);
        }
        BufferedImage dst = new BufferedImage(w, h, imageType(src));
        dst.setRGB(0, 0, w, h, out, 0, w);
        return dst;
    }

    private BufferedImage rotate180(BufferedImage src) {
        return flipVertical(flipHorizontal(src));
    }

    private BufferedImage rotate90Cw(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[pixels.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx = h - 1 - y;
                int ny = x;
                out[ny * h + nx] = pixels[y * w + x];
            }
        }
        BufferedImage dst = new BufferedImage(h, w, imageType(src));
        dst.setRGB(0, 0, h, w, out, 0, h);
        return dst;
    }

    private BufferedImage rotate90Ccw(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[pixels.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx = y;
                int ny = w - 1 - x;
                out[ny * h + nx] = pixels[y * w + x];
            }
        }
        BufferedImage dst = new BufferedImage(h, w, imageType(src));
        dst.setRGB(0, 0, h, w, out, 0, h);
        return dst;
    }

    /**
     * JPEGバイト列のAPP1(Exif)セグメントからOrientationタグ(0x0112)の値を読み取る。
     * 存在しない/解析できない場合は1(normal)を返す。
     */
    private int readJpegOrientation(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            return 1;
        }
        int offset = 2;
        while (offset + 4 <= bytes.length) {
            if ((bytes[offset] & 0xFF) != 0xFF) {
                break;
            }
            int marker = bytes[offset + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                offset += 2;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                // EOI、またはSOS(以降はスキャンデータでExifセグメントは現れない)
                break;
            }
            int segmentLength = ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
            if (segmentLength < 2 || offset + 2 + segmentLength > bytes.length) {
                break;
            }
            if (marker == 0xE1) {
                Integer orientation = parseExifOrientation(bytes, offset + 4, segmentLength - 2);
                if (orientation != null) {
                    return orientation;
                }
            }
            offset += 2 + segmentLength;
        }
        return 1;
    }

    private Integer parseExifOrientation(byte[] bytes, int start, int length) {
        if (length < 8 || start + 6 > bytes.length) {
            return null;
        }
        if (bytes[start] != 'E' || bytes[start + 1] != 'x' || bytes[start + 2] != 'i' || bytes[start + 3] != 'f'
                || bytes[start + 4] != 0 || bytes[start + 5] != 0) {
            return null;
        }
        int tiffStart = start + 6;
        if (tiffStart + 8 > bytes.length) {
            return null;
        }
        boolean littleEndian;
        if (bytes[tiffStart] == 'I' && bytes[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (bytes[tiffStart] == 'M' && bytes[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return null;
        }
        int ifdOffset = readInt32(bytes, tiffStart + 4, littleEndian);
        int ifd0 = tiffStart + ifdOffset;
        if (ifd0 < 0 || ifd0 + 2 > bytes.length) {
            return null;
        }
        int entryCount = readInt16(bytes, ifd0, littleEndian);
        for (int i = 0; i < entryCount; i++) {
            int entryOffset = ifd0 + 2 + i * 12;
            if (entryOffset + 12 > bytes.length) {
                break;
            }
            int tag = readInt16(bytes, entryOffset, littleEndian);
            if (tag == EXIF_ORIENTATION_TAG) {
                int value = readInt16(bytes, entryOffset + 8, littleEndian);
                return (value >= 1 && value <= 8) ? value : null;
            }
        }
        return null;
    }

    private int readInt16(byte[] bytes, int offset, boolean littleEndian) {
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        return littleEndian ? (b1 << 8 | b0) : (b0 << 8 | b1);
    }

    private int readInt32(byte[] bytes, int offset, boolean littleEndian) {
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        int b2 = bytes[offset + 2] & 0xFF;
        int b3 = bytes[offset + 3] & 0xFF;
        return littleEndian
                ? (b3 << 24 | b2 << 16 | b1 << 8 | b0)
                : (b0 << 24 | b1 << 16 | b2 << 8 | b3);
    }
}
