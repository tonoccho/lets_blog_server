package com.letsblog.content.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * [blogcard]/[amazon] 組み込みタグ向けスクレイピング(PlaywrightPageFetcher)、記事プレビューの
 * テーマ骨格取得(PreviewSkeletonFetcher)で使うヘッドレスChromiumを、実際に必要になったタイミングで
 * 1つ起動し、以後のリクエストで使い回す(Browser起動はプロセス生成を伴い数百ms〜数秒かかるため)。
 * legacy-apiのPlaywrightConfigと同一の実装(#576でPlaywrightを持つのはcontent-serviceになった)。
 *
 * @Lazyにしているのは、Chromiumの実行バイナリが存在しない環境(ブラウザインストールをまだ
 * 行っていないローカル開発機やCI等)でもアプリ自体は起動・他機能のテストができるようにするため。
 * PlaywrightもBrowserもプロセス/ネイティブリソースを保持するため、アプリ終了時にcloseする。
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
                // コンテナはrootユーザーで実行されるため、Chromiumのサンドボックスがrootでの起動を拒否する。
                // --disable-dev-shm-usageは/dev/shmが小さいDocker環境でのクラッシュを避けるための定番設定。
                .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
    }
}
