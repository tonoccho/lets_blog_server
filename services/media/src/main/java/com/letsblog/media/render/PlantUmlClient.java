package com.letsblog.media.render;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.letsblog.media.ai.AiServiceException;
import org.springframework.beans.factory.annotation.Value;
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

    private final RestClient client;

    public PlantUmlClient(@Value("${app.plantuml-base-url}") String baseUrl) {
        this.client = RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("plantuml")).baseUrl(baseUrl).build();
    }

    public byte[] renderPng(String plantUmlSource) {
        String encoded = PlantUmlEncoder.encode(plantUmlSource);
        try {
            return client.get()
                    .uri("/png/" + encoded)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            throw new AiServiceException("PlantUMLのレンダリングに失敗しました: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            // 名前解決失敗・接続拒否・タイムアウト等(HTTP応答なし)。素の500にせず502へ写像させる(issue #1759)。
            throw new AiServiceException("PlantUMLサーバーに接続できません: " + e.getMessage(), e);
        }
    }
}
