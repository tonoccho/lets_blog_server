package com.letsblog.api.service;

import com.letsblog.api.contentcache.ContentScrapingException;
import com.letsblog.api.dto.ThemeSkeletonResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ヘッドレスブラウザ(Playwright)でサイトの記事ページを開き、実テーマが描画したHTML/CSSを取得する。
 * 2つの経路がある({@link ArticlePreviewService#renderSkeleton}参照):
 *
 * <ul>
 *   <li>{@link #fetchAndSplice}: サイト内の既存記事ページを開き、実テーマが描画したDOM構造
 *       (タイトルの見出し要素、アイキャッチ、本文コンテナ)を保ったまま、タイトル・本文・
 *       アイキャッチをプレビュー対象記事の内容へ差し替える。差し替え位置は、WP REST APIで
 *       取得した既存記事のtitle.rendered/content.renderedの文字列を、実際に描画されたDOM内から
 *       検索することで特定する(テーマ固有のID/クラス名を一切知らなくても、実際に出力された
 *       文字列と一致する要素を探せば位置が特定できるため)。位置を特定できなかった場合は
 *       available=falseを返し、呼び出し側で従来のプレーンな表示へフォールバックする。
 *   <li>{@link #fetchRealPost}: プレビュー対象記事そのものを非公開(private)投稿として
 *       WordPressへ作成し、その実ページを認証Cookie付きで直接閲覧する(managed/agentサイトの
 *       ローカル/テスト環境限定)。差し替え探索が不要なため、上記のような特定失敗は発生しない。
 * </ul>
 */
@Component
public class PreviewSkeletonFetcher {

    private static final Logger logger = LoggerFactory.getLogger(PreviewSkeletonFetcher.class);

    private static final double NAVIGATION_TIMEOUT_MS = 15_000;
    /**
     * {@link #fetchRealPost}専用のタイムアウト。本番サイトはCloudflare等のボット対策JSチャレンジを
     * 経由することがあり、そのインタースティシャルページはDOMContentLoadedが即座に発火してしまう。
     * チャレンジのJS計算自体はネットワーク要求を伴わないことが多く、単にwaitUntilを緩めるだけでは
     * インタースティシャルの空のDOMを掴んでしまう(issue #483フィードバック: 本番プレビューが
     * 常に真っ白になる)。{@link #waitForChallengeToClear}でチャレンジ特有のマーカーが消えるまで
     * 追加で待つため、通常のナビゲーションタイムアウトより長めに取る。
     */
    private static final double REAL_POST_NAVIGATION_TIMEOUT_MS = 30_000;
    /** 上記のうち、チャレンジ突破待ちに残す猶予(ナビゲーション自体の待ち時間と分けて確保する)。 */
    private static final double CHALLENGE_CLEAR_TIMEOUT_MS = 15_000;
    /**
     * CloudflareのJSチャレンジ(「Just a moment...」インタースティシャル)によく見られるマーカー。
     * 存在しなくなるまで待つことで、チャレンジ自動突破→リダイレクト後の実ページを掴む。
     * 通常のWordPressページ(Cloudflare非経由、またはチャレンジ不要)では最初から存在しないため、
     * このwaitForFunctionは即座に成立して待ち時間は発生しない。
     */
    private static final String CHALLENGE_CLEARED_CONDITION =
            "() => !document.title.includes('Just a moment') "
                    + "&& !document.querySelector('#challenge-running, #challenge-form')";

    /**
     * fetchAndSplice/fetchRealPost共通のJSヘルパー。CSS収集(collectCss)と、骨格取得部分の
     * <script>除去・相対URL(画像・リンク・インラインstyleのurl())の絶対URLへの解決(absolutizeUrls)
     * を提供する。ブラウザ内で完結させることで、DOM自身の解決結果(src/hrefプロパティ)に
     * 任せられる(自前でURLを書き換えるより堅牢)。
     */
    private static final String COMMON_HELPERS_SCRIPT = """
              function collectCss() {
                const parts = [];
                for (const sheet of Array.from(document.styleSheets)) {
                  try {
                    const rules = Array.from(sheet.cssRules).map((r) => r.cssText).join('\\n');
                    if (rules) {
                      parts.push('/* ' + (sheet.href || '<style>') + ' */\\n' + rules);
                    }
                  } catch (e) {
                    // クロスオリジンのstylesheet(CORSヘッダー無し)はcssRulesへのアクセスがブロックされる。
                    // その場合はスキップする(トップページ経由のCSS取得側で別途カバーされ得る)。
                  }
                }
                return parts.join('\\n');
              }
              function absolutizeUrls(root) {
                root.querySelectorAll('script').forEach((el) => el.remove());
                root.querySelectorAll('img[src]').forEach((el) => {
                  el.setAttribute('src', el.src);
                  el.removeAttribute('srcset');
                });
                root.querySelectorAll('a[href]').forEach((el) => {
                  el.setAttribute('href', el.href);
                });
                root.querySelectorAll('[style*="url("]').forEach((el) => {
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
              }
            """;

    /**
     * 実記事ページのDOMから、タイトル/本文/アイキャッチの差し替え位置を特定して置き換える。
     */
    private static final String SPLICE_SCRIPT = """
            (args) => {
            """ + COMMON_HELPERS_SCRIPT + """
              const css = collectCss();
              try {
                const titleRendered = args.titleRendered;
                const contentRenderedRaw = (args.contentRendered || '').trim();
                const ourTitle = args.ourTitle;
                const ourContentHtml = args.ourContentHtml;
                const featuredImageDataUri = args.featuredImageDataUri;

                function textOf(html) {
                  const d = document.createElement('div');
                  d.innerHTML = html;
                  return d.textContent.trim();
                }

                // WP REST APIのcontent.renderedは<img ... />のようなXHTML形式の自己終了タグを
                // 含み得るが、ブラウザはinnerHTML読み出し時にvoid要素の自己終了スラッシュを除去して
                // 再直列化する。生文字列のまま比較すると常に不一致になるため、比較対象も同じDOM
                // 経由の直列化に通してから比較する(ブラウザ側の正規化と揃える)。
                function normalizeHtml(html) {
                  const d = document.createElement('div');
                  d.innerHTML = html;
                  return d.innerHTML;
                }
                const contentRendered = normalizeHtml(contentRenderedRaw);

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
                  return {
                    available: false, reason: '本文の位置を特定できませんでした', html: null,
                    eyecatchSpliced: false, css: css
                  };
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

                absolutizeUrls(ancestor);

                return {
                  available: true, reason: null, html: ancestor.outerHTML,
                  eyecatchSpliced: eyecatchSpliced, css: css
                };
              } catch (e) {
                return {
                  available: false, reason: 'DOM解析に失敗しました: ' + e.message, html: null,
                  eyecatchSpliced: false, css: css
                };
              }
            }
            """;

    /**
     * 非公開(private)投稿として作成したプレビュー記事の実ページを、そのままCSS収集・URL絶対化
     * するだけの単純なスクリプト。差し替え位置の探索を行わないため、fetchAndSpliceと異なり
     * 「本文の位置を特定できませんでした」のような失敗ケースが原理的に発生しない。
     */
    private static final String REAL_POST_SCRIPT = """
            () => {
            """ + COMMON_HELPERS_SCRIPT + """
              const css = collectCss();
              try {
                absolutizeUrls(document.body);
                return {
                  available: true, reason: null, html: document.body.innerHTML,
                  eyecatchSpliced: false, css: css
                };
              } catch (e) {
                return {
                  available: false, reason: 'DOM解析に失敗しました: ' + e.message, html: null,
                  eyecatchSpliced: false, css: css
                };
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

    /**
     * 非公開(private)投稿として作成したプレビュー記事の実ページを、認証Cookieを注入した
     * ブラウザコンテキストでナビゲートしてHTML/CSSを取得する。
     *
     * @param url 閲覧対象の投稿ページURL(非公開投稿。ログイン済みユーザーのみ閲覧可能)
     * @param cookieName 認証Cookie名({@link com.letsblog.api.cms.AuthCookie#name()})
     * @param cookieValue 認証Cookie値({@link com.letsblog.api.cms.AuthCookie#value()})
     */
    public ThemeSkeletonResponse fetchRealPost(String url, String cookieName, String cookieValue) {
        Cookie cookie = new Cookie(cookieName, cookieValue);
        cookie.url = url;
        cookie.httpOnly = true;

        try (BrowserContext context = browser.newContext()) {
            context.addCookies(List.of(cookie));
            try (Page page = context.newPage()) {
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(REAL_POST_NAVIGATION_TIMEOUT_MS)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                waitForChallengeToClear(page, url);
                Object result = page.evaluate(REAL_POST_SCRIPT);
                return toResponse(result);
            }
        } catch (PlaywrightException e) {
            throw new ContentScrapingException("記事ページの取得に失敗しました: " + url, e);
        }
    }

    /**
     * CloudflareのJSチャレンジのインタースティシャルが表示されている場合、それが自動突破されて
     * 実ページへ遷移し終わるまで待つ。チャレンジのJS計算はタイマー待ちが主でネットワーク要求を
     * 伴わないことが多いため、waitUntil(NETWORKIDLE等)だけではインタースティシャル自体を
     * 「読み込み完了」と誤認して空のDOMを返してしまう。マーカーが最初から存在しない(チャレンジが
     * 表示されていない)通常ページでは即座に成立するため、待ち時間は発生しない。
     * チャレンジが{@link #CHALLENGE_CLEAR_TIMEOUT_MS}以内に消えない場合(ブロック/対話式CAPTCHA等、
     * 待つだけでは突破できないケース)はベストエフォートで諦め、その時点のDOMをそのまま返す
     * (呼び出し元は従来通りavailable=trueだが内容が空、というケースを受け取る)。
     */
    private void waitForChallengeToClear(Page page, String url) {
        try {
            page.waitForFunction(CHALLENGE_CLEARED_CONDITION, null,
                    new Page.WaitForFunctionOptions().setTimeout(CHALLENGE_CLEAR_TIMEOUT_MS));
        } catch (TimeoutError e) {
            logger.warn("ボット対策チャレンジの突破待ちがタイムアウトしました: {}", url);
        }
    }

    @SuppressWarnings("unchecked")
    private ThemeSkeletonResponse toResponse(Object result) {
        if (!(result instanceof Map)) {
            return new ThemeSkeletonResponse(null, false, "予期しない結果形式です", false, "");
        }
        Map<String, Object> map = (Map<String, Object>) result;
        boolean available = Boolean.TRUE.equals(map.get("available"));
        String html = (String) map.get("html");
        String reason = (String) map.get("reason");
        boolean eyecatchSpliced = Boolean.TRUE.equals(map.get("eyecatchSpliced"));
        String css = (String) map.get("css");
        return new ThemeSkeletonResponse(html, available, reason, eyecatchSpliced, css != null ? css : "");
    }
}
