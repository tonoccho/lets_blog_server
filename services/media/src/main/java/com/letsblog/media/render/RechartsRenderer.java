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
 *
 * <h2>{@code render()}を丸ごとsynchronized(this)にしている理由(issue #1047)</h2>
 *
 * <p>{@code render()}は{@link #browser}(全リクエスト共有のシングルトン)から{@code newPage()}する。
 * 複数スレッドから排他なく{@code newPage()}以降のPlaywright呼び出しを行うと、
 * {@code PlaywrightException: Cannot find object to call pausedStateChanged}でHTTP 502になる
 * (実運用で4並行時に概ね半数が失敗することを確認済み)。
 *
 * <p><b>{@code BrowserContext}を分けても直らない。</b>Playwright Javaバインディングの
 * {@code Connection}(playwright-1.62.0の{@code com.microsoft.playwright.impl.Connection})は
 * <b>Browser1個につき1本のメッセージパイプ</b>であり、逆アセンブルして確認した限り内部に
 * {@code synchronized}は無い。{@code BrowserContext}/{@code Page}を分けても、それらが発行する
 * RPCは結局同じ{@code Connection}を経由するため、複数スレッドが同時にRPCの送受信
 * (送信→応答ポーリング→ディスパッチ)を行えば競合したままである。Playwright Javaの
 * 公式スレッドモデルも「Playwrightインスタンスは作成したスレッドに束縛される」という前提であり、
 * 複数スレッドからの同時アクセスはそもそも想定されていない。
 *
 * <p>そのため対処は<b>{@code newPage()}からpage使用後の{@code close()}までを丸ごと直列化する</b>
 * (=1度に1リクエストしか{@link #browser}に触らせない)。既存の{@link #loadBundleJs()}の
 * 二重チェックロックも同じ{@code this}をモニタにしているため、ロックの取得順序は
 * 常に「{@code render()}の外側ロック→{@code loadBundleJs()}の内側ロック」の一方向のみで、
 * デッドロックの余地は無い(Javaのモニタは再入可能)。
 *
 * <p><b>スループットへの影響。</b>1回のレンダリングは概ね1秒未満だが、直列化により
 * 並行リクエストは待ち行列に積まれる。並行数が増えるほどテール待ち時間は線形に伸びるが、
 * [recharts]組み込みタグの利用頻度・記事あたりの図表数を踏まえると許容範囲と判断した
 * (常時大量の同時レンダリングが発生する経路ではない)。将来スループットが問題になった場合は、
 * {@code Browser}/{@code BrowserContext}を複数プールする設計への変更を検討すること
 * (issue #1047のGoal参照。プーリング自体は本Issueのスコープ外)。
 *
 * <p>回帰テスト: {@code RechartsRendererConcurrentAccessTest}。
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

        // issue #1047: browser.newPage()からpage.close()までを丸ごとsynchronized(this)で直列化する。
        // 理由・採らなかった案はクラスJavadoc(直列化のスコープに関する節)を参照。
        synchronized (this) {
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
