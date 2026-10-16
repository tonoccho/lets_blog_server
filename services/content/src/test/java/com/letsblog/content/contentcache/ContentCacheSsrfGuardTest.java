package com.letsblog.content.contentcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.repository.ContentCacheRepository;
import com.microsoft.playwright.Browser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * コンテンツキャッシュAPIが内部アドレスへリクエストを送らないこと(issue #902)。
 *
 * <p>{@link OutboundUrlGuardTest} がアドレス判定そのものを、こちらが
 * 「判定が呼び出し経路に組み込まれていること」を見る。{@link OutboundUrlGuard}・{@link PlaywrightPageFetcher}・
 * 各パーサーは実物で、差し替えるのは外部との通信だけ(DBのリポジトリ、ヘッドレスブラウザ、名前解決)。
 */
@ExtendWith(MockitoExtension.class)
class ContentCacheSsrfGuardTest {

    @Mock
    private ContentCacheRepository contentCacheRepository;
    @Mock
    private Browser browser;

    /** 名前解決(DNS)だけを差し替えた実物のガード。mysql はdocker network内の内部アドレスへ解決される。 */
    private final OutboundUrlGuard outboundUrlGuard = TestOutboundUrlGuards.resolving(Map.of(
            "mysql", "172.18.0.5", "example.com", "203.0.113.10"));

    private PlaywrightPageFetcher fetcher() {
        return new PlaywrightPageFetcher(browser, outboundUrlGuard, new ReentrantLock());
    }

    private ContentCacheService service() {
        return new ContentCacheService(
                contentCacheRepository, fetcher(), new OgpMetadataParser(), new AmazonProductParser(),
                new ObjectMapper(), outboundUrlGuard, 24L);
    }

    @Test
    @DisplayName("宛先が拒否されたら、キャッシュも引かず取得もしない")
    void 宛先が拒否されたら何もしない() {
        assertThrows(ContentScrapingException.class, () -> service().resolve("http://169.254.169.254/latest/meta-data/"));

        // キャッシュ照会より前に弾く。過去に許可されていた宛先が内部へ向け直された場合に、
        // 古い結果を返し続けないため。
        verifyNoInteractions(contentCacheRepository);
        verifyNoInteractions(browser);
    }

    @Test
    @DisplayName("PageFetcher はブラウザを起こす前に宛先を検査する")
    void 取得前に宛先を検査する() {
        PlaywrightPageFetcher fetcher = fetcher();

        assertThrows(ContentScrapingException.class, () -> fetcher.fetchHtml("http://mysql:3306/"));

        // ブラウザのページ生成まで到達しない。
        verify(browser, never()).newPage();
    }
}
