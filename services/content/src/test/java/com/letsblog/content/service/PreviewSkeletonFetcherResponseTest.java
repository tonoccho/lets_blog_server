package com.letsblog.content.service;

import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.dto.ThemeSkeletonResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link PreviewSkeletonFetcher} がブラウザ内スクリプトの戻り値をDTOへ変換する部分の分岐。
 *
 * <p>変換はブラウザ内で動くJSの戻り値が相手なので、型も欠損も保証されない。ページ側が
 * 想定外の値を返したときに例外で500になるのではなく、{@code available=false} として
 * 呼び出し元(記事プレビュー)がプレーン表示へフォールバックできる形で返ることを見る。
 *
 * <p>Chromium は要らない。{@link Browser}/{@link Page} をモックして
 * {@code page.evaluate()} の戻り値だけを差し替える。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("content-service: プレビュー骨格の取得結果をDTOへ変換する")
class PreviewSkeletonFetcherResponseTest {

    @Mock
    private Browser browser;
    @Mock
    private Page page;

    private ThemeSkeletonResponse fetchWith(Object evaluated) {
        when(browser.newPage()).thenReturn(page);
        when(page.evaluate(anyString(), any())).thenReturn(evaluated);
        return new PreviewSkeletonFetcher(browser, new ReentrantLock())
                .fetchAndSplice("https://example.com/post/1", "題", "<p>本文</p>",
                        "新しい題", "<p>新しい本文</p>", null);
    }

    private static Map<String, Object> result(String css) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", true);
        map.put("reason", null);
        map.put("html", "<article>本文</article>");
        map.put("eyecatchSpliced", true);
        map.put("css", css);
        return map;
    }

    @Test
    @DisplayName("ページがMap以外を返したら、例外にせずavailable=falseで返す")
    void 想定外の結果形式はavailableFalseになる() {
        ThemeSkeletonResponse response = fetchWith("まさかの文字列");

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("予期しない結果形式です");
        assertThat(response.html()).isNull();
        assertThat(response.eyecatchSpliced()).isFalse();
        assertThat(response.css())
                .as("cssはnullではなく空文字。呼び出し元が素直に連結できるようにする")
                .isEmpty();
    }

    @Test
    @DisplayName("cssが取れなかった場合はnullではなく空文字にする")
    void cssがnullなら空文字になる() {
        ThemeSkeletonResponse response = fetchWith(result(null));

        assertThat(response.available()).isTrue();
        assertThat(response.html()).isEqualTo("<article>本文</article>");
        assertThat(response.eyecatchSpliced()).isTrue();
        assertThat(response.css()).isEmpty();
    }

    @Test
    @DisplayName("cssが取れた場合はそのまま返す")
    void cssがあればそのまま返す() {
        ThemeSkeletonResponse response = fetchWith(result("body{margin:0}"));

        assertThat(response.available()).isTrue();
        assertThat(response.css()).isEqualTo("body{margin:0}");
    }

    @Test
    @DisplayName("ブラウザ側の失敗はContentScrapingExceptionに変換される")
    void ブラウザの失敗は変換される() {
        when(browser.newPage()).thenReturn(page);
        when(page.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(new PlaywrightException("Timeout 15000ms exceeded"));

        PreviewSkeletonFetcher fetcher = new PreviewSkeletonFetcher(browser, new ReentrantLock());

        assertThat(assertThrows(ContentScrapingException.class,
                () -> fetcher.fetchAndSplice("https://example.com/post/1", "題", "<p>本文</p>",
                        "新しい題", "<p>新しい本文</p>", null)))
                .hasMessageContaining("https://example.com/post/1");
    }
}
