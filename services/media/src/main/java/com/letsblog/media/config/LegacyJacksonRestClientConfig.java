package com.letsblog.media.config;

import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、
 * 外部API連携クライアント(Penpot等)は引き続きcom.fasterxml.jackson.databind.JsonNode/ObjectNodeで
 * レスポンスを組み立てている。Jackson3のコンバータはJsonNode.class(Jackson2)を誤って受理した上で
 * デシリアライズに失敗するため、各クライアントの構築時にJackson3コンバータを外し、Jackson2コンバータへ
 * 差し替える。legacy-apiの{@code com.letsblog.api.config.LegacyJacksonRestClientConfig}と同一の実装
 * (#573でPenpotClientと共にmedia-serviceへ移設)。
 */
public final class LegacyJacksonRestClientConfig {

    private LegacyJacksonRestClientConfig() {
    }

    public static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }
}
