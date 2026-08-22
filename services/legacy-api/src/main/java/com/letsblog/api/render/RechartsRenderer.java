package com.letsblog.api.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * React + Recharts(api/tools/recharts-renderer/、MIT License)を共有のヘッドレスChromium
 * ({@link com.letsblog.api.config.PlaywrightConfig})上で1回だけ実行し、静的なHTML(SVG+凡例)へ
 * 変換する。React/Recharts自体は公開記事にもプレビューのWebviewにも一切配信されず、
 * サーバー内部のレンダリングにのみ使い捨てのページとして使う。
 * [recharts]組み込みタグ({@link com.letsblog.api.service.RechartsTagRenderService}参照)から呼ばれる。
 */
@Component
public class RechartsRenderer {

    private static final double RENDER_TIMEOUT_MS = 10_000;
    private static final String BUNDLE_RESOURCE_PATH = "recharts/renderer.bundle.js";

    private final Browser browser;
    private volatile String bundleJs;

    public RechartsRenderer(Browser browser) {
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
