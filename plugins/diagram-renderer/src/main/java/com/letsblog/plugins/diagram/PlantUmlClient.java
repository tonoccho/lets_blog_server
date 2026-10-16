package com.letsblog.plugins.diagram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * PlantUML サーバー({@code GET /png/<エンコード>})を呼び、PNG を取る。
 * services/media の {@code PlantUmlClient} の移植(#1828)。
 *
 * <p>記法エラーは応答ヘッダー {@code X-PlantUML-Diagram-Error} / {@code -Line} の有無で判定する
 * (PlantUML サーバーはエラーでも本文に PNG を返すので、本文は見ない)。ヘッダーが無い失敗・5xx・届かないものは
 * 記法エラーと区別して {@code render_failed} にする(#1776)。
 */
final class PlantUmlClient {

    private static final String ERROR_HEADER = "X-PlantUML-Diagram-Error";
    private static final String ERROR_LINE_HEADER = "X-PlantUML-Diagram-Error-Line";
    private static final int MAX_DETAIL_LENGTH = 200;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;

    PlantUmlClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** @param authorization 受けた Authorization ヘッダーの値。null ならヘッダーを付けない。 */
    byte[] renderPng(String source, String authorization) throws RenderException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/png/" + PlantUmlEncoder.encode(source)))
                .timeout(timeout)
                .GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RenderException(502, "render_failed", "PlantUML サーバーに接続できません: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderException(502, "render_failed", "PlantUML サーバーへの接続が中断されました");
        }
        if (response.statusCode() / 100 == 2) {
            return response.body();
        }
        String error = response.headers().firstValue(ERROR_HEADER).orElse("");
        if (error.isBlank()) {
            throw new RenderException(502, "render_failed",
                    "PlantUML サーバーが図を描けませんでした: HTTP " + response.statusCode());
        }
        throw new RenderException(422, "syntax_error", sanitize(error),
                parseLine(response.headers().firstValue(ERROR_LINE_HEADER).orElse("")));
    }

    private static Integer parseLine(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sanitize(String detail) {
        String cleaned = CONTROL_CHARS.matcher(detail).replaceAll(" ").strip();
        return cleaned.length() > MAX_DETAIL_LENGTH ? cleaned.substring(0, MAX_DETAIL_LENGTH) + "…" : cleaned;
    }
}
