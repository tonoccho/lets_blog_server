package com.letsblog.api.service;

import com.letsblog.api.contentcache.ContentScrapingException;
import com.letsblog.api.dto.ThemeSkeletonResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * ヘッドレスブラウザ(Playwright)でサイト内の既存記事ページを開き、実テーマが描画したDOM構造
 * (タイトルの見出し要素、アイキャッチ、本文コンテナ)を保ったまま、タイトル・本文・アイキャッチを
 * プレビュー対象記事の内容へ差し替える。
 *
 * タイトル/本文の差し替え位置は、WP REST APIで取得した既存記事のtitle.rendered/content.renderedの
 * 文字列を、実際に描画されたDOM内から検索することで特定する(テーマ固有のID/クラス名を
 * 一切知らなくても、実際に出力された文字列と一致する要素を探せば位置が特定できるため)。
 * 位置を特定できなかった場合はavailable=falseを返し、呼び出し側で従来のプレーンな表示へ
 * フォールバックする({@link ArticlePreviewService#renderSkeleton}参照)。
 */
@Component
public class PreviewSkeletonFetcher {

    private static final double NAVIGATION_TIMEOUT_MS = 15_000;

    /**
     * 実記事ページのDOMから、タイトル/本文/アイキャッチの差し替え位置を特定して置き換える。
     * ブラウザ内で完結させることで、相対URL(画像・リンク)の絶対URLへの解決をDOM自身の
     * 解決結果(src/hrefプロパティ)に任せられる(自前でURLを書き換えるより堅牢)。
     */
    private static final String SPLICE_SCRIPT = """
            (args) => {
              try {
                const titleRendered = args.titleRendered;
                const contentRendered = (args.contentRendered || '').trim();
                const ourTitle = args.ourTitle;
                const ourContentHtml = args.ourContentHtml;
                const featuredImageDataUri = args.featuredImageDataUri;

                function textOf(html) {
                  const d = document.createElement('div');
                  d.innerHTML = html;
                  return d.textContent.trim();
                }

                const allEls = Array.from(document.querySelectorAll('body *'));
                let contentEl = null;
                for (const el of allEls) {
                  if (contentRendered && el.innerHTML.indexOf(contentRendered) !== -1) {
                    if (!contentEl || el.innerHTML.length < contentEl.innerHTML.length) {
                      contentEl = el;
                    }
                  }
                }
                if (!contentEl) {
                  return { available: false, reason: '本文の位置を特定できませんでした', html: null, eyecatchSpliced: false };
                }

                const titleText = textOf(titleRendered || '');
                const headings = Array.from(document.querySelectorAll('h1, h2, h3'));
                let titleEl = null;
                for (const h of headings) {
                  if (titleText && h.textContent.trim() === titleText) {
                    const rel = h.compareDocumentPosition(contentEl);
                    if (rel & Node.DOCUMENT_POSITION_FOLLOWING) {
                      titleEl = h;
                    }
                  }
                }

                let ancestor = null;
                if (titleEl) {
                  let candidate = contentEl.parentElement;
                  while (candidate && !candidate.contains(titleEl)) {
                    candidate = candidate.parentElement;
                  }
                  ancestor = candidate;
                }
                if (!ancestor) {
                  ancestor = contentEl.closest('article') || contentEl.parentElement || contentEl;
                }

                let eyecatchImg = null;
                Array.from(ancestor.querySelectorAll('img')).some((img) => {
                  if (contentEl.contains(img)) return false;
                  const rel = img.compareDocumentPosition(contentEl);
                  if (rel & Node.DOCUMENT_POSITION_FOLLOWING) {
                    eyecatchImg = img;
                    return true;
                  }
                  return false;
                });

                if (titleEl) {
                  titleEl.textContent = ourTitle;
                }

                let eyecatchSpliced = false;
                if (eyecatchImg) {
                  if (featuredImageDataUri) {
                    eyecatchImg.setAttribute('src', featuredImageDataUri);
                    eyecatchImg.removeAttribute('srcset');
                    eyecatchImg.removeAttribute('sizes');
                    eyecatchImg.removeAttribute('loading');
                    eyecatchSpliced = true;
                  } else {
                    eyecatchImg.remove();
                  }
                } else if (featuredImageDataUri) {
                  const img = document.createElement('img');
                  img.setAttribute('src', featuredImageDataUri);
                  img.setAttribute('alt', '');
                  img.setAttribute('style', 'max-width:100%;height:auto;display:block;margin:0 0 16px;');
                  contentEl.parentNode.insertBefore(img, contentEl);
                }

                contentEl.innerHTML = ourContentHtml;

                ancestor.querySelectorAll('script').forEach((el) => el.remove());
                ancestor.querySelectorAll('img[src]').forEach((el) => {
                  el.setAttribute('src', el.src);
                  el.removeAttribute('srcset');
                });
                ancestor.querySelectorAll('a[href]').forEach((el) => {
                  el.setAttribute('href', el.href);
                });
                ancestor.querySelectorAll('[style*="url("]').forEach((el) => {
                  const style = el.getAttribute('style');
                  const rewritten = style.replace(/url\\(([^)]+)\\)/g, (match, group) => {
                    const raw = group.trim().replace(/^["']|["']$/g, '');
                    if (/^(data:|https?:)/.test(raw) || raw.indexOf('//') === 0) {
                      return match;
                    }
                    try {
                      return 'url("' + new URL(raw, document.baseURI).href + '")';
                    } catch (e) {
                      return match;
                    }
                  });
                  el.setAttribute('style', rewritten);
                });

                return { available: true, reason: null, html: ancestor.outerHTML, eyecatchSpliced: eyecatchSpliced };
              } catch (e) {
                return { available: false, reason: 'DOM解析に失敗しました: ' + e.message, html: null, eyecatchSpliced: false };
              }
            }
            """;

    private final Browser browser;

    public PreviewSkeletonFetcher(Browser browser) {
        this.browser = browser;
    }

    /**
     * @param url 骨格として使う実記事ページのURL(サイト内既存記事)
     * @param titleRendered 上記記事のWP REST APIから得たtitle.rendered(差し替え位置の検索キー)
     * @param contentRendered 上記記事のWP REST APIから得たcontent.rendered(差し替え位置の検索キー)
     * @param ourTitle プレビュー対象記事のタイトル
     * @param ourContentHtml プレビュー対象記事の本文HTML(Markdown変換済み)
     * @param featuredImageDataUri プレビュー対象記事のアイキャッチ(data URI、未設定ならnull)
     */
    public ThemeSkeletonResponse fetchAndSplice(
            String url,
            String titleRendered,
            String contentRendered,
            String ourTitle,
            String ourContentHtml,
            String featuredImageDataUri) {
        Map<String, Object> args = new HashMap<>();
        args.put("titleRendered", titleRendered);
        args.put("contentRendered", contentRendered);
        args.put("ourTitle", ourTitle);
        args.put("ourContentHtml", ourContentHtml);
        args.put("featuredImageDataUri", featuredImageDataUri);

        try (Page page = browser.newPage()) {
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(NAVIGATION_TIMEOUT_MS)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            Object result = page.evaluate(SPLICE_SCRIPT, args);
            return toResponse(result);
        } catch (PlaywrightException e) {
            throw new ContentScrapingException("記事ページの取得に失敗しました: " + url, e);
        }
    }

    @SuppressWarnings("unchecked")
    private ThemeSkeletonResponse toResponse(Object result) {
        if (!(result instanceof Map)) {
            return new ThemeSkeletonResponse(null, false, "予期しない結果形式です", false);
        }
        Map<String, Object> map = (Map<String, Object>) result;
        boolean available = Boolean.TRUE.equals(map.get("available"));
        String html = (String) map.get("html");
        String reason = (String) map.get("reason");
        boolean eyecatchSpliced = Boolean.TRUE.equals(map.get("eyecatchSpliced"));
        return new ThemeSkeletonResponse(html, available, reason, eyecatchSpliced);
    }
}
