package com.letsblog.identity.service;

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
 * issue #1241 AC3: アバター画像を512x512の正方形JPEGへ変換し、EXIF等の埋め込みメタ情報を削除する。
 *
 * <p>media-serviceの{@code ImageResizeService}(issue #291/#432)と同じ考え方
 * (JPEGのEXIF Orientationをピクセルへ焼き込んでから再エンコードすることで、見た目を保ったまま
 * メタ情報を削除する)をidentity-service側に移植したもの。identity-serviceはmedia-serviceの
 * クラスを直接参照できない(サービス境界。issue本文の実装メモ参照)ため、必要な部分のみを
 * 複製している。差分は次の2点:
 *
 * <ul>
 *   <li>アバターは常に512x512の正方形に固定する(記事画像のような長編基準の可変リサイズではない)。
 *       クライアント側(Canvas)で概ね正方形に切り抜かれた画像が届く想定だが、サーバー側でも
 *       非正方形の入力を中央基準で正方形に切り抜いてから縮小する(防御的な二重チェック)。</li>
 *   <li>出力形式は常にJPEGに統一する(透過を持つPNG入力も含む)。アバターは不透明表示
 *       ({@code rounded-full})が前提であり、形式を1つに統一した方が保存パスの管理
 *       (issue #1241 AC5、ユーザーID起点の決定的なファイル名)が単純になる。</li>
 * </ul>
 */
@Service
public class AvatarImageProcessor {

    private static final int EXIF_ORIENTATION_TAG = 0x0112;
    private static final float JPEG_QUALITY = 0.85f;
    static final int AVATAR_SIZE_PX = 512;

    public byte[] process(byte[] originalBytes, String mimeType) {
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(originalBytes));
        } catch (IOException e) {
            throw new UnsupportedAvatarFormatException("画像として読み込めませんでした: " + e.getMessage());
        }
        if (decoded == null) {
            throw new UnsupportedAvatarFormatException("画像として読み込めませんでした");
        }

        int orientation = isJpeg(mimeType) ? readJpegOrientation(originalBytes) : 1;
        BufferedImage normalized = applyOrientation(decoded, orientation);
        BufferedImage square = centerCropToSquare(normalized);
        BufferedImage scaled = scale(square, AVATAR_SIZE_PX, AVATAR_SIZE_PX);

        try {
            return encodeJpeg(ensureOpaqueRgb(scaled));
        } catch (IOException e) {
            throw new UnsupportedAvatarFormatException("画像のエンコードに失敗しました: " + e.getMessage());
        }
    }

    private boolean isJpeg(String mimeType) {
        return mimeType != null && (mimeType.equalsIgnoreCase("image/jpeg") || mimeType.equalsIgnoreCase("image/jpg"));
    }

    private BufferedImage centerCropToSquare(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width == height) {
            return source;
        }
        int size = Math.min(width, height);
        int x = (width - size) / 2;
        int y = (height - size) / 2;
        return source.getSubimage(x, y, size, size);
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

    private int imageType(BufferedImage image) {
        return image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("JPEGエンコーダが見つかりません");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * JPEGエンコーダはインデックスカラー・アルファ付きの色モデルを受け付けない場合があるため、
     * 常にTYPE_INT_RGBへ描画し直してから書き出す(透過は黒背景に潰れる。アバターは
     * 不透明表示前提のため許容する)。
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

    /**
     * EXIF Orientationタグ(1-8)に従い、画像を正しい向きに焼き込んだ新しい{@link BufferedImage}を返す。
     * タグが存在しない/normal(1)の場合は引数をそのまま返す。
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
