package com.letsblog.api.config;

import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、
 * 社内の外部API連携クライアント群(WordPress/GitHub等)は引き続き
 * com.fasterxml.jackson.databind.JsonNode/ObjectNodeでレスポンスを組み立てている。
 * Jackson3のコンバータはJsonNode.class(Jackson2)を誤って受理した上でデシリアライズに失敗するため、
 * 各クライアントの構築時にJackson3コンバータを外し、Jackson2コンバータへ差し替える。
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
