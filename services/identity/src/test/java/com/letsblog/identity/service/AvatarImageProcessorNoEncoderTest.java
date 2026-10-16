package com.letsblog.identity.service;

import com.letsblog.identity.testsupport.ImageFixtures;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPEGのエンコーダが実行環境に無い場合({@code encodeJpeg}の防御的な分岐)。JDKのImageIOのレジストリから
 * JPEGの書き込み実装を一時的に外して再現し、必ず元へ戻す(プロダクションコードには触れない)。
 */
class AvatarImageProcessorNoEncoderTest {

    @Test
    void JPEGのエンコーダが無ければ画像のエンコードに失敗した例外になる() {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<ImageWriterSpi> removed = new ArrayList<>();
        Iterator<ImageWriterSpi> providers = registry.getServiceProviders(ImageWriterSpi.class, true);
        while (providers.hasNext()) {
            ImageWriterSpi spi = providers.next();
            for (String format : spi.getFormatNames()) {
                if (format.equalsIgnoreCase("jpg") || format.equalsIgnoreCase("jpeg")) {
                    removed.add(spi);
                    break;
                }
            }
        }
        assertTrue(!removed.isEmpty(), "JPEGの書き込み実装が見つからない(前提が崩れている)");
        byte[] png = ImageFixtures.png(64, 64);
        removed.forEach(spi -> registry.deregisterServiceProvider(spi, ImageWriterSpi.class));
        try {
            UnsupportedAvatarFormatException thrown = assertThrows(UnsupportedAvatarFormatException.class,
                    () -> new AvatarImageProcessor().process(png, "image/png"));

            assertTrue(thrown.getMessage().contains("エンコードに失敗"), thrown.getMessage());
        } finally {
            removed.forEach(spi -> registry.registerServiceProvider(spi, ImageWriterSpi.class));
        }
        // 元へ戻っていて、通常どおり変換できる。
        assertDoesNotThrow(() -> new AvatarImageProcessor().process(png, "image/png"));
        assertTrue(ImageIO.getImageWritersByFormatName("jpg").hasNext());
    }
}
