package com.letsblog.media.dto;

/**
 * [recharts]組み込みタグ({@code com.letsblog.api.service.RechartsTagRenderService}、legacy-api側に残る)
 * からの{@code POST /api/render/recharts}呼び出しに対する応答(#573)。
 *
 * @param html レンダリング結果({@code .recharts-wrapper}要素のouterHTML、静的なHTML/SVG)
 */
public record RechartsRenderResponse(String html) {
}
