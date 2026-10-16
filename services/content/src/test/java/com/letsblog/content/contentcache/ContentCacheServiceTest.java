package com.letsblog.content.contentcache;

import com.letsblog.content.domain.ContentCache;
import com.letsblog.content.domain.ContentType;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.testsupport.BrokenObjectMapper;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContentCacheService}。サービス・パーサー・フェッチャー・ガード・{@code ObjectMapper}は実物で、
 * 差し替えるのは外部との通信(DBのリポジトリ、ヘッドレスブラウザ、名前解決)だけ({@link ContentWorld})。
 */
class ContentCacheServiceTest {

    private static final String BLOG_URL = "https://blog.example.com/post/1";
    private static final String AMAZON_URL = "https://www.amazon.co.jp/dp/B000";

    private ContentWorld world;
    private ContentCacheService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld(24);
        service = world.contentCacheService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private static String blogHtml(String title) {
        return ContentWorld.ogHtml(title, "説明", "サイト", BLOG_URL, null);
    }

    @Test
    void キャッシュが無ければ取得して保存し_BLOGCARDの内容を返す() {
        world.page(BLOG_URL, blogHtml("題1"));

        ContentCacheResponse response = service.resolve(BLOG_URL);

        assertEquals(BLOG_URL, response.url());
        assertEquals(ContentType.BLOGCARD, response.type());
        assertEquals("題1", response.data().get("title"));
        assertEquals(1, world.contentCache.size());
        assertEquals(ContentType.BLOGCARD, world.contentCache.values().iterator().next().getContentType());
        assertEquals(1, world.navigatedUrls.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.amazon.co.jp/dp/B000", "https://www.amazon.com/dp/B000",
            "https://amzn.to/abc", "https://amzn.asia/abc"})
    void Amazonのホストは商品ページとして取得する(String url) {
        world.page(url, "<html><body><span id=\"productTitle\">商品</span></body></html>");

        ContentCacheResponse response = service.resolve(url);

        assertEquals(ContentType.AMAZON, response.type());
        assertEquals("商品", response.data().get("productName"));
    }

    @Test
    void 期限内のキャッシュはそのまま返し_ブラウザを使わない() {
        world.cached(BLOG_URL, ContentType.BLOGCARD, Map.of("title", "保存済み"), LocalDateTime.now());

        ContentCacheResponse response = service.resolve(BLOG_URL);

        assertEquals("保存済み", response.data().get("title"));
        assertTrue(world.navigatedUrls.isEmpty());
    }

    @Test
    void 期限切れで内容が変わっていれば更新する() {
        ContentCache old = world.cached(BLOG_URL, ContentType.BLOGCARD, Map.of("title", "古い"),
                LocalDateTime.now().minusHours(48));
        LocalDateTime oldUpdatedAt = old.getLastUpdatedAt();
        world.page(BLOG_URL, blogHtml("新しい"));

        ContentCacheResponse response = service.resolve(BLOG_URL);

        assertEquals("新しい", response.data().get("title"));
        assertEquals(1, world.contentCache.size());
        assertTrue(old.getDataJson().contains("新しい"));
        assertTrue(old.getLastUpdatedAt().isAfter(oldUpdatedAt));
        assertTrue(old.getLastCheckedAt().isAfter(LocalDateTime.now().minusMinutes(1)));
    }

    @Test
    void 期限切れでも内容が同じなら確認時刻だけ更新する() {
        String html = blogHtml("同じ");
        Map<String, String> scraped = new OgpMetadataParser().parse(html, BLOG_URL);
        ContentCache old = world.cached(BLOG_URL, ContentType.BLOGCARD, scraped, LocalDateTime.now().minusHours(48));
        LocalDateTime oldUpdatedAt = old.getLastUpdatedAt();
        String oldHash = old.getContentHash();
        world.page(BLOG_URL, html);

        service.resolve(BLOG_URL);

        assertEquals(oldHash, old.getContentHash());
        assertEquals(oldUpdatedAt, old.getLastUpdatedAt());
        assertNotEquals(oldUpdatedAt, old.getLastCheckedAt());
    }

    @Test
    void 期限切れで再取得に失敗したら既存のキャッシュを返す() {
        world.cached(BLOG_URL, ContentType.BLOGCARD, Map.of("title", "残っている"), LocalDateTime.now().minusHours(48));
        // ページを登録しない: ブラウザが開けず取得に失敗する。

        ContentCacheResponse response = service.resolve(BLOG_URL);

        assertEquals("残っている", response.data().get("title"));
    }

    @Test
    void キャッシュも無く取得にも失敗したら例外() {
        ContentScrapingException e = assertThrows(ContentScrapingException.class, () -> service.resolve(BLOG_URL));

        assertTrue(e.getMessage().contains(BLOG_URL));
        assertTrue(world.contentCache.isEmpty());
    }

    @Test
    void 前後の空白を除いたURLで扱う() {
        world.page(BLOG_URL, blogHtml("題"));

        assertEquals(BLOG_URL, service.resolve("  " + BLOG_URL + "  ").url());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ftp://example.com/x", "example.com/x", "http:relative", "http://exa mple.com/"})
    void 不正なURLはIllegalArgumentException(String raw) {
        assertThrows(IllegalArgumentException.class, () -> service.resolve(raw));
    }

    @Test
    void urlがnullならIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve(null));
    }

    @Test
    void 内部アドレスへ解決されるホストは取得しない() {
        assertThrows(ContentScrapingException.class, () -> service.resolve("https://internal.example.com/x"));

        assertTrue(world.navigatedUrls.isEmpty());
    }

    @Test
    void スクレイピング結果のJSON化に失敗したらIllegalStateException() {
        ContentCacheService broken = new ContentCacheService(
                world.contentCacheRepository,
                new PlaywrightPageFetcher(world.browser, TestOutboundUrlGuards.resolving(
                        Map.of("blog.example.com", "203.0.113.11")), new ReentrantLock()),
                new OgpMetadataParser(), new AmazonProductParser(), BrokenObjectMapper.create(),
                TestOutboundUrlGuards.resolving(Map.of("blog.example.com", "203.0.113.11")), 24);
        world.page(BLOG_URL, blogHtml("題"));

        assertThrows(IllegalStateException.class, () -> broken.resolve(BLOG_URL));
    }

    @Test
    void 保存済みのデータがJSONとして読めなければIllegalStateException() {
        ContentCache row = world.cached(BLOG_URL, ContentType.BLOGCARD, Map.of("title", "x"), LocalDateTime.now());
        row.setDataJson("{not json");

        assertThrows(IllegalStateException.class, () -> service.resolve(BLOG_URL));
    }
}
