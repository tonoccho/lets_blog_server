package com.letsblog.content.service;

import com.letsblog.content.client.LegacyApiBridgeClient;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.domain.ContentType;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.dto.TagDesignColors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * issue #641: BlogCardTagRenderServiceは非信頼なOGPスクレイピング結果(タイトル・説明等)を
 * HTMLへ埋め込む。AmazonTagRenderServiceと同様のXSS対策を検証する。
 */
@ExtendWith(MockitoExtension.class)
class BlogCardTagRenderServiceTest {

    @Mock
    private ContentCacheService contentCacheService;

    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private BlogCardTagRenderService service() {
        return new BlogCardTagRenderService(contentCacheService, legacyApiBridgeClient, currentActorService);
    }

    private void stubTagDesign() {
        LegacyApiBridgeClient.TagDesignResponse response =
                new LegacyApiBridgeClient.TagDesignResponse("#fff", "#000", "#f00", null, null);
        lenient().when(legacyApiBridgeClient.resolveTagDesign(any(), anyString(), any())).thenReturn(response);
        lenient().when(legacyApiBridgeClient.toColors(any()))
                .thenReturn(new TagDesignColors("#fff", "#000", "#f00", null));
    }

    @Test
    void render_タイトルにHTMLタグを含むレスポンスはエスケープされて出力される() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "title", "<img src=x onerror=alert(1)>",
                "url", "https://example.com/article",
                "description", "説明文");
        when(contentCacheService.resolve("https://example.com/article"))
                .thenReturn(new ContentCacheResponse(
                        "https://example.com/article", ContentType.BLOGCARD, data,
                        LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertFalse(result.contains("<img src=x onerror=alert(1)>"));
        assertTrue(result.contains("&lt;img"));
    }

    @Test
    void render_javascriptプロトコルのURLはリンク化されない() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "title", "タイトル",
                "url", "javascript:alert(1)",
                "description", "説明文");
        when(contentCacheService.resolve("https://example.com/article"))
                .thenReturn(new ContentCacheResponse(
                        "https://example.com/article", ContentType.BLOGCARD, data,
                        LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertFalse(result.contains("href=\"javascript:"));
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        when(contentCacheService.resolve(anyString()))
                .thenThrow(new ContentScrapingException("取得失敗", null));

        String result = service().render("[blogcard https://example.com/article]", 1L);

        assertTrue(result.contains("<a href=\"https://example.com/article\""));
        assertFalse(result.contains("lb-blogcard"));
    }

    @Test
    void render_非httpスキームかつ取得失敗時はプレーンテキストにフォールバックする() {
        when(contentCacheService.resolve(anyString()))
                .thenThrow(new ContentScrapingException("不正なURL", null));

        String result = service().render("[blogcard javascript:alert(1)]", 1L);

        assertFalse(result.contains("<a "));
        assertTrue(result.contains("javascript:alert(1)"));
    }

    @Test
    void render_タグが無ければそのまま返す() {
        String markdown = "普通の本文です。";
        assertEquals(markdown, service().render(markdown, 1L));
    }

    /**
     * issue #760: プロジェクトに紐付いていないサイトへの公開ではprojectId=nullで呼ばれる。
     * projectIdはそのままタグデザイン解決へ渡し(受け側が固定のデフォルト値を返す暫定対応、issue #763)、
     * レンダリング自体は成功する。
     */
    @Test
    void render_projectIdがnullでもタグデザインを解決してカードを描画する() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "title", "タイトル",
                "url", "https://example.com/article",
                "description", "説明文");
        when(contentCacheService.resolve("https://example.com/article"))
                .thenReturn(new ContentCacheResponse(
                        "https://example.com/article", ContentType.BLOGCARD, data,
                        LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[blogcard https://example.com/article]", null);

        assertTrue(result.contains("lb-blogcard"));
        org.mockito.Mockito.verify(legacyApiBridgeClient, org.mockito.Mockito.atLeastOnce())
                .resolveTagDesign(org.mockito.ArgumentMatchers.isNull(), anyString(), any());
    }
}
