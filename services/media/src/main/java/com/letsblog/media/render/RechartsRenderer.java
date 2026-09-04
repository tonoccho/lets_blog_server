package com.letsblog.media.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * React + Recharts(api/tools/recharts-renderer/、MIT License)を共有のヘッドレスChromium
 * ({@link com.letsblog.media.config.PlaywrightConfig})上で1回だけ実行し、静的なHTML(SVG+凡例)へ
 * 変換する。React/Recharts自体は公開記事にもプレビューのWebviewにも一切配信されず、
 * サーバー内部のレンダリングにのみ使い捨てのページとして使う。
 * [recharts]組み込みタグ({@link com.letsblog.media.service.RechartsTagRenderService}参照)から呼ばれる。
 */
@Component
public class RechartsRenderer {

    private static final double RENDER_TIMEOUT_MS = 10_000;
    private static final String BUNDLE_RESOURCE_PATH = "recharts/renderer.bundle.js";

    private final Browser browser;
    private volatile String bundleJs;

    /**
     * {@code @Lazy}は<b>注入点に必要</b>である(issue #1020)。
     *
     * <p>{@link com.letsblog.media.config.PlaywrightConfig}の{@code browser}Bean定義には元から
     * {@code @Lazy}が付いていたが、それだけでは効かない。{@code @Component}である本クラスは
     * eager singletonであり、さらに{@link com.letsblog.media.controller.RenderController}が
     * 本クラスをeagerに注入するため、コンテキスト起動時に{@code browser}が実体化され、
     * Chromiumの実行バイナリが無いホストではApplicationContextごと落ちていた
     * (実測した連鎖: {@code renderController → rechartsRenderer → browser →
     * BrowserType.launch → chrome-headless-shell: libatk-1.0.so.0 が無い})。
     * 巻き添えで認可マトリクス(#772)・AdminAuthorization(#644)・Flyway契約(#914)の
     * 計46件が常に赤になり、本物の退行が紛れても気づけない状態だった。
     *
     * <p>ここに{@code @Lazy}を置くとSpringは{@link Browser}のプロキシを注入し、実体は
     * {@code browser.newPage()}が最初に呼ばれるまで作られない。<b>実行時経路は変わらない</b>
     * ため、Chromiumを持つコンテナでの{@code POST /api/render/recharts}は従来どおり動く。
     *
     * <p>採らなかった案:
     * <ul>
     *   <li><b>本クラス自体を{@code @Lazy}にする</b> — {@code RenderController}がeagerに
     *       注入するので実体化は結局起動時に起きる。消費者が増えるたびに全員へ
     *       {@code @Lazy}を付けて回る必要があり、付け忘れが再発の形になる</li>
     *   <li><b>プロファイル分離</b>(テストだけ{@code browser}を差し替える) — テストは
     *       通るが、Chromiumの無いホストで<b>アプリを起動する</b>ことは相変わらずできない。
     *       {@code PlaywrightConfig}のコメントが元から述べていた意図(ブラウザが無くても
     *       アプリは起動する)を、テスト専用の迂回で置き換えることになる</li>
     *   <li><b>ホストへ依存パッケージを導入する</b> — 環境側の対処であり、
     *       「ブラウザの有無に関係なく実行できる」というissue #1020のGoalと方向が逆。
     *       Node側(受け入れテスト)のブラウザ導入は別issue #1045で扱う</li>
     * </ul>
     *
     * <p>この不変条件は{@code PlaywrightLazyBrowserTest}が検査する。
     */
    public RechartsRenderer(@Lazy Browser browser) {
        this.browser = browser;
    }

    /**
     * @return renderer.bundle.jsが生成した`.recharts-wrapper`要素のouterHTML(静的なHTML/SVG)
     * @throws RechartsRenderException レンダリングに失敗した場合(ハーネス側のエラー・タイムアウト等)
     */
    @SuppressWarnings("unchecked")
    public String render(RechartsChartConfig config) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("type", config.type());
        args.put("data", config.data());
        args.put("xAxisKey", config.xAxisKey());
        args.put("seriesKeys", config.seriesKeys());
        args.put("colors", config.colors());
        args.put("stacked", config.stacked());
        args.put("width", config.width());
        args.put("height", config.height());
        args.put("textColor", config.textColor());
        args.put("gridColor", config.gridColor());
        args.put("yAxisLabel", config.yAxisLabel());

        try (Page page = browser.newPage()) {
            page.setContent("<!DOCTYPE html><html><head></head><body><div id=\"root\"></div></body></html>");
            page.addScriptTag(new Page.AddScriptTagOptions().setContent(loadBundleJs()));
            page.evaluate("(config) => window.renderChart(config)", args);
            page.waitForFunction("() => window.__chartResult || window.__chartError",
                    null, new Page.WaitForFunctionOptions().setTimeout(RENDER_TIMEOUT_MS));

            Object raw = page.evaluate("() => ({ result: window.__chartResult, error: window.__chartError })");
            Map<String, Object> result = (Map<String, Object>) raw;
            Object error = result.get("error");
            if (error != null) {
                throw new RechartsRenderException("チャートの生成に失敗しました: " + error);
            }
            Object html = result.get("result");
            if (!(html instanceof String svg) || svg.isBlank()) {
                throw new RechartsRenderException("チャートの生成結果を取得できませんでした");
            }
            return svg;
        } catch (PlaywrightException e) {
            throw new RechartsRenderException("チャートのレンダリングに失敗しました: " + e.getMessage(), e);
        }
    }

    private String loadBundleJs() {
        String cached = bundleJs;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (bundleJs == null) {
                try (var in = new ClassPathResource(BUNDLE_RESOURCE_PATH).getInputStream()) {
                    bundleJs = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("recharts renderer bundleの読み込みに失敗しました", e);
                }
            }
            return bundleJs;
        }
    }
}
