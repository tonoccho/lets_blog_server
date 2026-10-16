package com.letsblog.identity.testsupport;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** アバターのテストが使う、実際にデコードできる画像のバイト列(issue #1826)。 */
public final class ImageFixtures {

    private ImageFixtures() {
    }

    public static byte[] png(int width, int height) {
        return encode(solid(width, height), "png");
    }

    public static byte[] jpeg(int width, int height) {
        return encode(solid(width, height), "jpg");
    }

    private static BufferedImage solid(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(new Color(30, 120, 200));
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
