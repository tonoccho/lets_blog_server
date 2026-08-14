package com.letsblog.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 記事投稿時に画像を長編基準でリサイズする(issue #291)。長編が指定px以下なら何もしない
 * (拡大はしない)。GIFはアニメーションを壊しうる(ImageIOは最初のフレームしか読めない)ため
 * 対象外とする。ImageIOがデコードできない形式(SVG/WebP等)や失敗時は、元のバイト列を
 * そのまま返す(リサイズはベストエフォートであり、投稿自体を失敗させない)。
 */
@Service
public class ImageResizeService {

    private static final Logger log = LoggerFactory.getLogger(ImageResizeService.class);

    public byte[] resizeToLongEdge(byte[] originalBytes, String mimeType, int maxLongEdgePx) {
        if (mimeType != null && mimeType.equalsIgnoreCase("image/gif")) {
            return originalBytes;
        }
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (original == null) {
                return originalBytes;
            }
            int width = original.getWidth();
            int height = original.getHeight();
            int longEdge = Math.max(width, height);
            if (longEdge <= maxLongEdgePx) {
                return originalBytes;
            }

            double scale = (double) maxLongEdgePx / longEdge;
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));

            BufferedImage resized = new BufferedImage(
                    targetWidth, targetHeight,
                    original.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
            } finally {
                g.dispose();
            }

            byte[] encoded = encode(resized, mimeType);
            if (encoded == null) {
                return originalBytes;
            }
            log.info("画像をリサイズしました: {}x{} -> {}x{}", width, height, targetWidth, targetHeight);
            return encoded;
        } catch (IOException | RuntimeException e) {
            log.warn("画像のリサイズに失敗したため、元のサイズのままアップロードします: {}", e.getMessage());
            return originalBytes;
        }
    }

    private byte[] encode(BufferedImage image, String mimeType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, formatNameFor(mimeType), out);
        return written ? out.toByteArray() : null;
    }

    private String formatNameFor(String mimeType) {
        if (mimeType != null && (mimeType.equalsIgnoreCase("image/jpeg") || mimeType.equalsIgnoreCase("image/jpg"))) {
            return "jpg";
        }
        return "png";
    }
}
