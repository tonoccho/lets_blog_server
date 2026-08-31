package com.letsblog.content.contentcache;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

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

    public PlaywrightPageFetcher(Browser browser, OutboundUrlGuard outboundUrlGuard) {
        this.browser = browser;
        this.outboundUrlGuard = outboundUrlGuard;
    }

    public String fetchHtml(String url) {
        // 最初のURLはブラウザを起こす前に弾く(明確なエラーを返すため)。
        outboundUrlGuard.requireAllowed(url);
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
        }
    }
}
