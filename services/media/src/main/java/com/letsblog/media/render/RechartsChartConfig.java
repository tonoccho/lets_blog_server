package com.letsblog.media.render;

import java.util.List;
import java.util.Map;

/**
 * {@link RechartsRenderer}へ渡すチャート設定。api/tools/recharts-renderer/harness.jsの
 * `window.renderChart(config)`が受け取る形式に対応する。
 *
 * @param type "bar"/"line"/"area"/"pie"(小文字、harness.js側の分岐と一致させる)
 * @param data 各行を{列名: 値}のMapにしたリスト(数値列は事前にDoubleへ変換済み)
 * @param xAxisKey X軸に使う列名(pieの場合はnull)
 * @param seriesKeys 系列として描画する列名のリスト(pieの場合は[nameKey, valueKey]の2要素)
 * @param colors 系列へ割り当てる色(#RRGGBB)。空の場合はharness.js側の既定パレットを使う
 * @param stacked bar/areaを積み上げ表示するか(それ以外の型では無視される)
 * @param width 描画幅(px)
 * @param height 描画高さ(px)
 * @param textColor 軸ラベル等のテキスト色(#RRGGBB)
 * @param gridColor グリッド線の色(#RRGGBB)
 * @param yAxisLabel Y軸に添えるラベル文字列(未指定ならnull、pieでは無視される)
 */
public record RechartsChartConfig(
        String type,
        List<Map<String, Object>> data,
        String xAxisKey,
        List<String> seriesKeys,
        List<String> colors,
        boolean stacked,
        int width,
        int height,
        String textColor,
        String gridColor,
        String yAxisLabel) {
}
