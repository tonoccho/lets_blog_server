package com.letsblog.media.render;

import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.common.auth.ToolsTokenUnavailableException;
import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.letsblog.media.ai.AiServiceException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * PlantUMLサーバー(plantuml/plantuml-server:jetty)にPlantUML記法のテキストを送り、
 * PNG画像を取得するクライアント。
 */
@Component
public class PlantUmlClient {

    private static final String ERROR_HEADER = "X-PlantUML-Diagram-Error";
    private static final String ERROR_LINE_HEADER = "X-PlantUML-Diagram-Error-Line";
    private static final int MAX_DETAIL_LENGTH = 200;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private final RestClient client;
    private final ToolsTokenProvider toolsTokenProvider;

    /**
     * 接続先は{@code PLANTUML_BASE_URL}(システムの設定。プロジェクト単位の上書きは無い)なので、有効なら必ず
     * extensionsのツール用トークン(#1804)を付ける。
     */
    @Autowired
    public PlantUmlClient(@Value("${app.plantuml-base-url}") String baseUrl, ToolsTokenProvider toolsTokenProvider) {
        this.client = RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("plantuml")).baseUrl(baseUrl).build();
        this.toolsTokenProvider = toolsTokenProvider;
    }

    /** extensionsのツールのトークンを付けない(従来どおり)。 */
    public PlantUmlClient(String baseUrl) {
        this(baseUrl, ToolsTokenProvider.disabled());
    }

    public byte[] renderPng(String plantUmlSource) {
        String encoded = PlantUmlEncoder.encode(plantUmlSource);
        try {
            // 有効なのに取れないときは、送らずに理由の分かるエラーにする(ヘッダー無しで続けない)。
            java.util.Optional<String> authorization = toolsTokenProvider.authorization();
            return client.get()
                    .uri("/png/" + encoded)
                    .headers(headers -> authorization.ifPresent(value -> headers.set(HttpHeaders.AUTHORIZATION, value)))
                    .retrieve()
                    .body(byte[].class);
        } catch (ToolsTokenUnavailableException e) {
            throw new AiServiceException(e.getMessage(), e);
        } catch (RestClientResponseException e) {
            throw new PlantUmlRenderRejectedException("PlantUML が図を描けませんでした: " + rejectionDetail(e), e);
        } catch (RestClientException e) {
            // 名前解決失敗・接続拒否・タイムアウト等(HTTP応答なし)。素の500にせず502へ写像させる(issue #1759)。
            throw new AiServiceException("PlantUMLサーバーに接続できません: " + e.getMessage(), e);
        }
    }

    /**
     * 拒否の詳細。PlantUMLサーバーはエラーのときも本文にPNGを返すため本文は使わず、
     * 応答ヘッダー(X-PlantUML-Diagram-Error / -Line)、無ければstatusコードから作る。
     */
    private static String rejectionDetail(RestClientResponseException e) {
        String error = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(ERROR_HEADER);
        String detail;
        if (error == null || error.isBlank()) {
            detail = e.getStatusCode().toString();
        } else {
            detail = error;
            String line = e.getResponseHeaders().getFirst(ERROR_LINE_HEADER);
            if (line != null && !line.isBlank()) {
                detail += " (" + line.trim() + " 行目)";
            }
        }
        detail = CONTROL_CHARS.matcher(detail).replaceAll(" ").strip();
        return detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) + "…" : detail;
    }
}
