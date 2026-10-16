package com.letsblog.media.testsupport;

import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * JDKの画像書き出し器(ImageIOのプラグイン)が使えない実行環境を、テストの間だけ作る部品(issue #1819)。
 *
 * <p>{@code ImageResizeService}は、JPEG・PNGの書き出し器が見つからない場合に元のバイト列へ戻す分岐を持つ。
 * 通常のJDKには両方が同梱されているので、この分岐は環境を変えないと通らない。ImageIOのレジストリから
 * 指定の形式の書き出し器を外し、処理が終わったら必ず元に戻す。自前のクラスを差し替えるものではなく、
 * 外部との通信でもない(JDKのコーデックの有無という実行環境の条件を作るだけ)。
 */
public final class ImageWriters {

    private ImageWriters() {
    }

    /** {@code formatName}(例: jpeg・png)の書き出し器が1つも無い状態で{@code action}を実行する。 */
    public static void without(String formatName, Runnable action) {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<ImageWriterSpi> removed = new ArrayList<>();
        Iterator<ImageWriterSpi> providers = registry.getServiceProviders(ImageWriterSpi.class, false);
        while (providers.hasNext()) {
            ImageWriterSpi spi = providers.next();
            if (Arrays.stream(spi.getFormatNames())
                    .anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(formatName))) {
                removed.add(spi);
            }
        }
        removed.forEach(registry::deregisterServiceProvider);
        try {
            action.run();
        } finally {
            removed.forEach(spi -> registry.registerServiceProvider(spi));
        }
    }
}
