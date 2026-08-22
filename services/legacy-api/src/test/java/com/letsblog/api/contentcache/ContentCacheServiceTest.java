package com.letsblog.api.contentcache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.domain.ContentCache;
import com.letsblog.api.domain.ContentType;
import com.letsblog.api.dto.ContentCacheResponse;
import com.letsblog.api.repository.ContentCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentCacheServiceTest {

    private static final long TTL_HOURS = 24;

    @Mock
    private ContentCacheRepository contentCacheRepository;
    @Mock
    private PlaywrightPageFetcher pageFetcher;
    @Mock
    private OgpMetadataParser ogpMetadataParser;
    @Mock
    private AmazonProductParser amazonProductParser;

    private ContentCacheService service;

    @BeforeEach
    void setUp() {
        service = new ContentCacheService(
                contentCacheRepository, pageFetcher, ogpMetadataParser, amazonProductParser,
                new ObjectMapper(), TTL_HOURS);
    }

    private ContentCache existingCache(String url, String urlHash, ContentType type, Map<String, String> data,
                                        LocalDateTime lastCheckedAt) {
        String dataJson = toJson(data);
        ContentCache cache = new ContentCache();
        cache.setUrl(url);
        cache.setUrlHash(urlHash);
        cache.setContentType(type);
        cache.setDataJson(dataJson);
        // サービス実装(ContentCacheService.sha256Hex)と同じアルゴリズムでハッシュを計算する。
        // これによりテストの「スクレイピング結果が変化していない」ケースを正しく再現できる
        // (ここが実装と食い違う偽のハッシュだと、常に「変化あり」判定になってしまいテストの意味がなくなる)。
        cache.setContentHash(sha256Hex(dataJson));
        cache.setLastCheckedAt(lastCheckedAt);
        cache.setLastUpdatedAt(lastCheckedAt);
        return cache;
    }

    private String toJson(Map<String, String> data) {
        try {
            return new ObjectMapper().writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void resolve_新規URLはスクレイピングしてキャッシュに保存する() {
        String url = "https://example.com/posts/1";
        when(contentCacheRepository.findByUrlHash(any())).thenReturn(Optional.empty());
        when(pageFetcher.fetchHtml(url)).thenReturn("<html></html>");
        when(ogpMetadataParser.parse("<html></html>", url)).thenReturn(Map.of("title", "記事タイトル"));
        when(contentCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ContentCacheResponse response = service.resolve(url);

        assertEquals(ContentType.BLOGCARD, response.type());
        assertEquals("記事タイトル", response.data().get("title"));
        verify(contentCacheRepository).save(any());
    }

    @Test
    void resolve_TTL内のキャッシュはスクレイピングせずそのまま返す() {
        String url = "https://example.com/posts/1";
        ContentCache cached = existingCache(url, "hash", ContentType.BLOGCARD,
                Map.of("title", "キャッシュ済み"), LocalDateTime.now().minusHours(1));
        when(contentCacheRepository.findByUrlHash(any())).thenReturn(Optional.of(cached));

        ContentCacheResponse response = service.resolve(url);

        assertEquals("キャッシュ済み", response.data().get("title"));
        verify(pageFetcher, never()).fetchHtml(any());
        verify(contentCacheRepository, never()).save(any());
    }

    @Test
    void resolve_TTL超過かつ内容が変化した場合はキャッシュを更新する() {
        String url = "https://example.com/posts/1";
        ContentCache cached = existingCache(url, "hash", ContentType.BLOGCARD,
                Map.of("title", "古いタイトル"), LocalDateTime.now().minusHours(TTL_HOURS + 1));
        when(contentCacheRepository.findByUrlHash(any())).thenReturn(Optional.of(cached));
        when(pageFetcher.fetchHtml(url)).thenReturn("<html></html>");
        when(ogpMetadataParser.parse("<html></html>", url)).thenReturn(Map.of("title", "新しいタイトル"));
        when(contentCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ContentCacheResponse response = service.resolve(url);

        assertEquals("新しいタイトル", response.data().get("title"));
        ArgumentCaptor<ContentCache> captor = ArgumentCaptor.forClass(ContentCache.class);
        verify(contentCacheRepository).save(captor.capture());
        assertEquals("新しいタイトル", readTitle(captor.getValue().getDataJson()));
    }

    @Test
    void resolve_TTL超過でも内容が変化していなければlastUpdatedAtは更新せずlastCheckedAtのみ更新する() {
        String url = "https://example.com/posts/1";
        Map<String, String> sameData = Map.of("title", "変わらないタイトル");
        LocalDateTime staleCheckedAt = LocalDateTime.now().minusHours(TTL_HOURS + 1);
        ContentCache cached = existingCache(url, "hash", ContentType.BLOGCARD, sameData, staleCheckedAt);
        when(contentCacheRepository.findByUrlHash(any())).thenReturn(Optional.of(cached));
        when(pageFetcher.fetchHtml(url)).thenReturn("<html></html>");
        when(ogpMetadataParser.parse("<html></html>", url)).thenReturn(sameData);
        when(contentCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.resolve(url);

        ArgumentCaptor<ContentCache> captor = ArgumentCaptor.forClass(ContentCache.class);
        verify(contentCacheRepository).save(captor.capture());
        ContentCache saved = captor.getValue();
        assertEquals(staleCheckedAt, saved.getLastUpdatedAt(), "内容が変化していないのでlastUpdatedAtは更新されないこと");
        assertEquals(true, saved.getLastCheckedAt().isAfter(staleCheckedAt), "lastCheckedAtは再チェック時刻に更新されること");
    }

    @Test
    void resolve_スクレイピング失敗時に既存キャッシュがあればそれを返す() {
        String url = "https://example.com/posts/1";
        ContentCache cached = existingCache(url, "hash", ContentType.BLOGCARD,
                Map.of("title", "以前のキャッシュ"), LocalDateTime.now().minusHours(TTL_HOURS + 1));
        when(contentCacheRepository.findByUrlHash(any())).thenReturn(Optional.of(cached));
        when(pageFetcher.fetchHtml(url)).thenThrow(new ContentScrapingException("取得失敗", new RuntimeException()));

        ContentCacheResponse response = service.resolve(url);

        assertEquals("以前のキャッシュ", response.data().get("title"));
        verify(contentCacheRepository, never()).save(any());
    }

    @Test
    void resolve_スクレイピング失敗時に既存キャッシュがなければ例外を伝播する() {
        String url = "https://example.com/posts/1";
        when(contentCacheRepository.findByUrlHash(any())).thenReturn(Optional.empty());
        when(pageFetcher.fetchHtml(url)).thenThrow(new ContentScrapingException("取得失敗", new RuntimeException()));

        assertThrows(ContentScrapingException.class, () -> service.resolve(url));
    }

    @Test
    void resolve_不正なURLはIllegalArgumentExceptionを投げる() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve("not-a-url"));
        assertThrows(IllegalArgumentException.class, () -> service.resolve(""));
        assertThrows(IllegalArgumentException.class, () -> service.resolve("ftp://example.com/file"));

        verify(pageFetcher, never()).fetchHtml(any());
    }

    @Test
    void resolve_amazonドメインのURLはAmazonパーサーを使う() {
        String url = "https://www.amazon.co.jp/dp/B000000000";
        when(contentCacheRepository.findByUrlHash(any())).thenReturn(Optional.empty());
        when(pageFetcher.fetchHtml(url)).thenReturn("<html></html>");
        when(amazonProductParser.parse("<html></html>", url)).thenReturn(Map.of("productName", "商品"));
        when(contentCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ContentCacheResponse response = service.resolve(url);

        assertEquals(ContentType.AMAZON, response.type());
        assertEquals("商品", response.data().get("productName"));
        verify(ogpMetadataParser, never()).parse(any(), any());
    }

    private String readTitle(String dataJson) {
        try {
            return new ObjectMapper().readTree(dataJson).get("title").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
