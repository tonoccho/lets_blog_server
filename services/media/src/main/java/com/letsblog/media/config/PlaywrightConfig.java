package com.letsblog.media.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * [recharts]組み込みタグのサーバーサイドレンダリング({@link com.letsblog.media.render.RechartsRenderer})で
 * 使うヘッドレスChromiumを、実際に必要になったタイミングで1つ起動し、以後のリクエストで使い回す。
 * legacy-apiのPlaywrightConfig(#573でRechartsRendererと共にmedia-serviceへ移設)と同一の実装。
 * @Lazyにしているのは、Chromiumの実行バイナリが存在しない環境でもアプリ自体は起動・他機能の
 * テストができるようにするため。
 *
 * <p><b>ここの{@code @Lazy}だけでは足りない(issue #1020)。</b>Bean定義側の{@code @Lazy}は、
 * eagerな消費者が{@link Browser}を直接注入した時点で効かなくなる。実際、
 * {@link com.letsblog.media.render.RechartsRenderer}(eager singleton)がコンストラクタで
 * 素の{@code Browser}を受け取っていたため、Chromiumの無いホストでは
 * {@code @SpringBootTest}が118件中46件全滅していた。<b>新たに{@code Browser}/{@code Playwright}を
 * 使うBeanを足すときは、その注入点にも{@code @Lazy}を付けること。</b>
 * 付け忘れは{@code PlaywrightLazyBrowserTest}のラチェットが検知する。
 */
@Configuration
public class PlaywrightConfig {

    @Lazy
    @Bean(destroyMethod = "close")
    public Playwright playwright() {
        return Playwright.create();
    }

    @Lazy
    @Bean(destroyMethod = "close")
    public Browser browser(Playwright playwright) {
        return playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
    }
}
