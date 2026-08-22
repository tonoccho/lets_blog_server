package com.letsblog.api.contentcache;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

/**
 * ヘッドレスブラウザ(Playwright)でURLを開き、JS実行後のレンダリング済みHTMLを取得する。
 * OGPメタタグはJS実行なしの生HTMLに含まれることが多いが、Amazon商品ページ等は
 * クライアントサイドで内容が補完されるケースがあるため、両タグ共通でブラウザ経由の取得に統一する。
 */
@Component
public class PlaywrightPageFetcher {

    private static final double NAVIGATION_TIMEOUT_MS = 15_000;

    private final Browser browser;

    public PlaywrightPageFetcher(Browser browser) {
        this.browser = browser;
    }

    public String fetchHtml(String url) {
        try (Page page = browser.newPage()) {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(NAVIGATION_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            return page.content();
        } catch (PlaywrightException e) {
            throw new ContentScrapingException("URLの取得に失敗しました: " + url, e);
        }
    }
}
