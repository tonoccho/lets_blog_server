package com.letsblog.content.contentcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.repository.ContentCacheRepository;
import com.microsoft.playwright.Browser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * コンテンツキャッシュAPIが内部アドレスへリクエストを送らないこと(issue #902)。
 *
 * <p>{@link OutboundUrlGuardTest} がアドレス判定そのものを、こちらが
 * 「判定が呼び出し経路に組み込まれていること」を見る。
 */
@ExtendWith(MockitoExtension.class)
class ContentCacheSsrfGuardTest {

    @Mock
    private ContentCacheRepository contentCacheRepository;
    @Mock
    private PlaywrightPageFetcher pageFetcher;
    @Mock
    private OgpMetadataParser ogpMetadataParser;
    @Mock
    private AmazonProductParser amazonProductParser;
    @Mock
    private OutboundUrlGuard outboundUrlGuard;
    @Mock
    private Browser browser;

    private ContentCacheService service() {
        return new ContentCacheService(
                contentCacheRepository, pageFetcher, ogpMetadataParser, amazonProductParser,
                new ObjectMapper(), outboundUrlGuard, 24L);
    }

    @Test
    @DisplayName("宛先が拒否されたら、キャッシュも引かず取得もしない")
    void 宛先が拒否されたら何もしない() {
        doThrow(new ContentScrapingException("このURLは取得できません", null))
                .when(outboundUrlGuard).requireAllowed(anyString());

        assertThrows(ContentScrapingException.class, () -> service().resolve("http://169.254.169.254/latest/meta-data/"));

        // キャッシュ照会より前に弾く。過去に許可されていた宛先が内部へ向け直された場合に、
        // 古い結果を返し続けないため。
        verifyNoInteractions(contentCacheRepository);
        verifyNoInteractions(pageFetcher);
    }

    @Test
    @DisplayName("PageFetcher はブラウザを起こす前に宛先を検査する")
    void 取得前に宛先を検査する() {
        PlaywrightPageFetcher fetcher = new PlaywrightPageFetcher(browser, outboundUrlGuard);
        doThrow(new ContentScrapingException("このURLは取得できません", null))
                .when(outboundUrlGuard).requireAllowed("http://mysql:3306/");

        assertThrows(ContentScrapingException.class, () -> fetcher.fetchHtml("http://mysql:3306/"));

        verify(outboundUrlGuard).requireAllowed("http://mysql:3306/");
        // ブラウザのページ生成まで到達しない。
        verify(browser, never()).newPage();
    }
}
