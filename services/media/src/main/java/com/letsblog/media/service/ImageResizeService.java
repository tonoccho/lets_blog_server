package com.letsblog.media.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

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
    /** アップロード画像(issue #1654)はサイズを変えないので、画質の劣化を抑えるため高めにする。 */
    private static final float UPLOAD_JPEG_QUALITY = 0.95f;
    /**
     * {@link #reencodeKeepingResolution}がデコードする画像の画素数の上限(6,400万画素)。20MBのPNGは数億画素にも
     * なりうる(伸長すると数GB)ため、デコードの前にヘッダーの寸法で断る(issue #1599)。
     */
    private static final long MAX_DECODE_SOURCE_PIXELS = 64_000_000L;

    /** {@link #reencodeKeepingResolution}の結果。width/heightは出力画像の実際の画素数。 */
    public record ReencodedImage(byte[] data, String mimeType, int width, int height) {
    }

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
     * 画像を長辺maxLongEdgePx以下に収め、透過の有無によらずJPEGへ書き出す(issue #1657。AIタグ付け用コピーを
     * バイト数の上限に収めるため)。透過部分は白で塗りつぶす。元より大きくはしない。デコードや書き出しに
     * 失敗した場合は元のバイト列をそのまま返す(他のリサイズと同じくベストエフォート)。
     */
    public ResizeResult resizeToJpeg(byte[] originalBytes, String mimeType, int maxLongEdgePx) {
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (decoded == null) {
                return new ResizeResult(originalBytes, mimeType);
            }
            BufferedImage output = decoded;
            int longEdge = Math.max(decoded.getWidth(), decoded.getHeight());
            if (longEdge > maxLongEdgePx) {
                double scale = (double) maxLongEdgePx / longEdge;
                output = scale(decoded, Math.max(1, (int) Math.round(decoded.getWidth() * scale)),
                        Math.max(1, (int) Math.round(decoded.getHeight() * scale)));
            }
            byte[] jpeg = encodeJpeg(flattenOnWhite(output), JPEG_QUALITY);
            return jpeg != null ? new ResizeResult(jpeg, "image/jpeg") : new ResizeResult(originalBytes, mimeType);
        } catch (IOException | RuntimeException e) {
            log.warn("画像のJPEG化に失敗したため、元のバイト列のまま扱います: {}", e.getMessage());
            return new ResizeResult(originalBytes, mimeType);
        }
    }

    private BufferedImage flattenOnWhite(BufferedImage image) {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /**
     * 画像を切り抜き・拡大・縮小せず、元の解像度のまま再エンコードする(issue #1654)。EXIF Orientationを
     * 反映したうえで書き出すため、画素数は反映後の向きで数える。出力にはEXIF/GPS等のメタ情報が残らない
     * (デコードして新しく書き出すため)。形式は維持する: JPEG入力は品質0.95のJPEG、PNG入力は(不透明でも)PNG。
     *
     * <p>{@link #resizeToLongEdge}と違い、失敗時に元のバイト列を返すことはしない。メタ情報を残したまま
     * 保存してしまうため、デコードできない・画素数が多すぎる画像は{@link InvalidImageUploadException}で断る。
     */
    public ReencodedImage reencodeKeepingResolution(byte[] originalBytes, String mimeType) {
        BufferedImage decoded = decodeWithinPixelLimit(originalBytes);
        int orientation = isJpeg(mimeType) ? readJpegOrientation(originalBytes) : 1;
        return encodeKeepingFormat(applyOrientation(decoded, orientation), mimeType);
    }

    /**
     * 画像を回転・反転・切り抜きし、明るさ・コントラストを調整する(issue #1655, #1656)。操作は並べた順に適用し、切り抜きは操作後の画像の座標で
     * 指定する。明るさ・コントラストは切り抜きのあとに適用する({@link ImageAdjustment}。画素単位の処理なので順序は結果に影響しない)。拡大・縮小はせず、出力の画素数は操作の結果どおり。メタ情報は残さず、形式は元のまま
     * ({@link #reencodeKeepingResolution}と同じ書き出し)。操作も切り抜きも無い・切り抜きが範囲外・
     * 読めない/大きすぎる画像は{@link InvalidImageUploadException}。
     */
    public ReencodedImage applyEdits(
            byte[] originalBytes, String mimeType, List<ImageEditOperation> operations, ImageCropRegion crop,
            ImageAdjustment adjustment) {
        boolean hasOperations = operations != null && !operations.isEmpty();
        if (adjustment != null && !adjustment.isInRange()) {
            throw new InvalidImageUploadException("明るさ・コントラストは-100〜100で指定してください。");
        }
        boolean hasAdjustment = adjustment != null && !adjustment.isIdentity();
        if (!hasOperations && crop == null && !hasAdjustment) {
            throw new InvalidImageUploadException("編集内容がありません。");
        }
        BufferedImage current = applyOrientation(
                decodeWithinPixelLimit(originalBytes), isJpeg(mimeType) ? readJpegOrientation(originalBytes) : 1);
        if (hasOperations) {
            for (ImageEditOperation operation : operations) {
                current = switch (operation) {
                    case ROTATE_CW -> rotate90Cw(current);
                    case ROTATE_CCW -> rotate90Ccw(current);
                    case FLIP_HORIZONTAL -> flipHorizontal(current);
                    case FLIP_VERTICAL -> flipVertical(current);
                };
            }
        }
        if (crop != null) {
            current = crop(current, crop);
        }
        if (hasAdjustment) {
            current = adjust(current, adjustment);
        }
        return encodeKeepingFormat(current, mimeType);
    }

    /** 明るさ・コントラスト(issue #1656)を画素ごとに適用する。透明度は変えない。 */
    private BufferedImage adjust(BufferedImage src, ImageAdjustment adjustment) {
        int[] table = adjustment.lookupTable();
        int width = src.getWidth();
        int height = src.getHeight();
        int[] pixels = src.getRGB(0, 0, width, height, null, 0, width);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            pixels[i] = (argb & 0xff000000)
                    | (table[(argb >> 16) & 0xff] << 16)
                    | (table[(argb >> 8) & 0xff] << 8)
                    | table[argb & 0xff];
        }
        BufferedImage dst = new BufferedImage(width, height, imageType(src));
        dst.setRGB(0, 0, width, height, pixels, 0, width);
        return dst;
    }

    private BufferedImage crop(BufferedImage src, ImageCropRegion region) {
        if (region.x() < 0 || region.y() < 0 || region.width() < 1 || region.height() < 1
                || (long) region.x() + region.width() > src.getWidth()
                || (long) region.y() + region.height() > src.getHeight()) {
            throw new InvalidImageUploadException("切り抜き範囲が画像の外にあります。");
        }
        BufferedImage dst = new BufferedImage(region.width(), region.height(), imageType(src));
        dst.setRGB(0, 0, region.width(), region.height(),
                src.getRGB(region.x(), region.y(), region.width(), region.height(), null, 0, region.width()),
                0, region.width());
        return dst;
    }

    /** 形式を維持して書き出す(JPEGは品質0.95のJPEG、PNGはPNG)。 */
    private ReencodedImage encodeKeepingFormat(BufferedImage output, String mimeType) {
        try {
            ResizeResult encoded = encode(output, mimeType, false, UPLOAD_JPEG_QUALITY);
            if (encoded == null) {
                throw new InvalidImageUploadException("画像を変換できませんでした。");
            }
            return new ReencodedImage(encoded.data(), encoded.mimeType(), output.getWidth(), output.getHeight());
        } catch (IOException e) {
            throw new InvalidImageUploadException("画像を変換できませんでした。");
        }
    }

    /** ヘッダーの寸法で画素数を確かめてからデコードする。読めない画像は{@link InvalidImageUploadException}。 */
    private BufferedImage decodeWithinPixelLimit(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new InvalidImageUploadException("画像として読み込めませんでした。");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_DECODE_SOURCE_PIXELS) {
                    throw new InvalidImageUploadException("画像の画素数が大きすぎます(上限6,400万画素)。");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidImageUploadException invalid) {
                throw invalid;
            }
            throw new InvalidImageUploadException("画像として読み込めませんでした。");
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
        return encode(image, mimeType, convertOpaquePngToJpeg, JPEG_QUALITY);
    }

    private ResizeResult encode(
            BufferedImage image, String mimeType, boolean convertOpaquePngToJpeg, float jpegQuality)
            throws IOException {
        boolean useJpeg = isJpeg(mimeType) || (convertOpaquePngToJpeg && !image.getColorModel().hasAlpha());
        if (useJpeg) {
            byte[] data = encodeJpeg(image, jpegQuality);
            return data != null ? new ResizeResult(data, "image/jpeg") : null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", out);
        return written ? new ResizeResult(out.toByteArray(), "image/png") : null;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
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
