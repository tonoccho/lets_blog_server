package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.dto.ContentCacheResponse;
import com.letsblog.content.dto.TagDesignColors;
import com.letsblog.content.domain.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #641: AmazonTagRenderServiceは非信頼なスクレイピング結果(商品名・価格等)をHTMLへ
 * 埋め込む。XSS対策のエスケープと、http/https以外のURL(javascript:等)をリンク化しないことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AmazonTagRenderServiceTest {

    @Mock
    private ContentCacheService contentCacheService;

    @Mock
    private ProjectBridgeClient projectBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private AmazonTagRenderService service() {
        return new AmazonTagRenderService(contentCacheService, projectBridgeClient, currentActorService);
    }

    private void stubTagDesign() {
        ProjectBridgeClient.TagDesignResponse response =
                new ProjectBridgeClient.TagDesignResponse("#fff", "#000", "#f00", null, null);
        lenient().when(projectBridgeClient.resolveTagDesign(any(), anyString(), any())).thenReturn(response);
        lenient().when(projectBridgeClient.toColors(any()))
                .thenReturn(new TagDesignColors("#fff", "#000", "#f00", null));
    }

    @Test
    void render_商品名にHTMLタグを含むレスポンスはエスケープされて出力される() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "<script>alert(1)</script>",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertFalse(result.contains("<script>"));
        assertTrue(result.contains("&lt;script&gt;"));
    }

    @Test
    void render_javascriptプロトコルの商品URLはリンク化されない() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "javascript:alert(1)",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        // productUrlがhttp/https以外の場合、フォールバックとして呼び出し元URL(rawUrl)を使う実装のため、
        // href属性値にjavascript:が出現しないことを確認する。
        assertFalse(result.contains("href=\"javascript:"));
    }

    @Test
    void render_価格に円マークが無ければ全角円マークを補う() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertTrue(result.contains("￥1000"));
    }

    @Test
    void render_非本番サイトでは商品リンクを非活性化する() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data, LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, false);

        assertFalse(result.contains("<a "));
        assertTrue(result.contains("<div class=\"lb-amazon-card\">"));
    }

    @Test
    void render_スクレイピング失敗時は通常のリンクにフォールバックする() {
        when(contentCacheService.resolve(anyString()))
                .thenThrow(new ContentScrapingException("取得失敗", null));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", 1L, true);

        assertTrue(result.contains("<a href=\"https://amazon.co.jp/dp/xxx\""));
        assertFalse(result.contains("lb-amazon-card"));
    }

    /**
     * issue #760: プロジェクトに紐付いていないサイトへの公開ではprojectId=nullで呼ばれる。
     * projectIdはそのままタグデザイン解決へ渡し(受け側がprojectId=nullなら固定のデフォルト値を返す)、
     * 400/502にならずレンダリング自体は成功する。
     */
    @Test
    void render_projectIdがnullでもタグデザインを解決してカードを描画する() {
        stubTagDesign();
        Map<String, String> data = Map.of(
                "productName", "商品名",
                "productUrl", "https://amazon.co.jp/dp/xxx",
                "price", "1000");
        when(contentCacheService.resolve("https://amazon.co.jp/dp/xxx"))
                .thenReturn(new ContentCacheResponse(
                        "https://amazon.co.jp/dp/xxx", ContentType.AMAZON, data,
                        LocalDateTime.now(), LocalDateTime.now()));

        String result = service().render("[amazon https://amazon.co.jp/dp/xxx]", null, true);

        assertTrue(result.contains("lb-amazon-card"));
        assertTrue(result.contains("商品名"));
        verify(projectBridgeClient, atLeastOnce()).resolveTagDesign(isNull(), anyString(), any());
    }

    @Test
    void render_タグが無ければそのまま返す() {
        String markdown = "普通の本文です。";
        assertTrue(service().render(markdown, 1L, true).equals(markdown));
    }
}
