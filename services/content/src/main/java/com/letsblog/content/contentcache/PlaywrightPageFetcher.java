package com.letsblog.content.contentcache;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * ヘッドレスブラウザ(Playwright)でURLを開き、JS実行後のレンダリング済みHTMLを取得する。
 * OGPメタタグはJS実行なしの生HTMLに含まれることが多いが、Amazon商品ページ等は
 * クライアントサイドで内容が補完されるケースがあるため、両タグ共通でブラウザ経由の取得に統一する。
 *
 * <p>宛先は{@link OutboundUrlGuard}で制限する(issue #902)。<b>最初のURLだけでなく、
 * ブラウザが出す各リクエスト(リダイレクト先・サブリソースを含む)を遮断する</b>。
 * 初回だけ検査しても、外部ホストから内部アドレスへ302されれば意味がないため。
 */
@Component
public class PlaywrightPageFetcher {

    private static final double NAVIGATION_TIMEOUT_MS = 15_000;

    private final Browser browser;
    private final OutboundUrlGuard outboundUrlGuard;
    /**
     * {@link Browser}(= 共有Connection)への排他アクセス用ロック(issue #1047)。
     * {@link com.letsblog.content.config.PlaywrightConfig#browserAccessLock()}のJavadoc参照
     * (なぜ{@code synchronized(this)}ではなく共有Beanなのか、なぜ{@code synchronized(browser)}
を採らないのか)。Browserを使うクラスが増えたときも、<b>同じインスタンス</b>を共有すること。
     */
    private final ReentrantLock browserAccessLock;

    /**
     * {@code @Lazy}は<b>注入点に必要</b>である(issue #1046。media-serviceの
     * {@code RechartsRenderer}で先に判明した同型の欠陥 #1020)。
     *
     * <p>{@link com.letsblog.content.config.PlaywrightConfig}の{@code browser}Bean定義には
     * 元から{@code @Lazy}が付いていたが、それだけでは効かない。{@code @Component}である本クラスは
     * eager singletonであり、さらに{@code ContentCacheService}が本クラスをeagerに注入するため、
     * コンテキスト起動時に{@code browser}が実体化され、Chromiumの実行バイナリが無いホストでは
     * ApplicationContextごと落ちていた(実測: {@code chrome-headless-shell:
     * libatk-1.0.so.0 が無い} → {@code TargetClosedError})。巻き添えで認可マトリクス(#772)・
     * AdminAuthorization(#644)・Flyway契約(#914)・内部ブリッジの計53件が常に赤になり、
     * 本物の退行が紛れても気づけない状態だった。
     *
     * <p>ここに{@code @Lazy}を置くとSpringは{@link Browser}のプロキシを注入し、実体は
     * {@code browser.newPage()}が最初に呼ばれるまで作られない。<b>実行時経路は変わらない</b>
     * ため、Chromiumを持つコンテナでの{@code [blogcard]}/{@code [amazon]}のスクレイピングは
     * 従来どおり動く。
     *
     * <p><b>eagerな消費者が1つでも残れば症状は残る。</b>(issue #1046の時点では、記事プレビューの
     * 骨格取得という2つ目の消費者があった。issue #1564で削除した。)採らなかった案(クラス自体を{@code @Lazy}にする / プロファイル分離 /
     * ホストへ依存パッケージを導入する)は{@code docs/TEST_DOCUMENTATION.md}に記録がある。
     *
     * <p>この不変条件は{@code PlaywrightLazyBrowserTest}のラチェットが検査する。
     */
    public PlaywrightPageFetcher(@Lazy Browser browser, OutboundUrlGuard outboundUrlGuard,
                                  ReentrantLock browserAccessLock) {
        this.browser = browser;
        this.outboundUrlGuard = outboundUrlGuard;
        this.browserAccessLock = browserAccessLock;
    }

    public String fetchHtml(String url) {
        // 最初のURLはブラウザを起こす前に弾く(明確なエラーを返すため)。
        outboundUrlGuard.requireAllowed(url);
        // issue #1047: newPage()からclose()までを丸ごとロックで直列化する(理由は
        // PlaywrightConfig#browserAccessLock()のJavadoc参照)。
        browserAccessLock.lock();
        try (Page page = browser.newPage()) {
            // リダイレクト・サブリソースも含め、ブラウザが実際に接続する直前に毎回検査する。
            page.route("**/*", route -> {
                if (outboundUrlGuard.isAllowed(route.request().url())) {
                    route.resume();
                } else {
                    route.abort();
                }
            });
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(NAVIGATION_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            return page.content();
        } catch (PlaywrightException e) {
            throw new ContentScrapingException("URLの取得に失敗しました: " + url, e);
        } finally {
            browserAccessLock.unlock();
        }
    }
}
