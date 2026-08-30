package com.letsblog.publishing.config;

import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、本サービスの
 * 内部ブリッジクライアント群(LegacyApiBridgeClient/IdentityClient/AiGenerationClient等)は
 * com.fasterxml.jackson.databind.JsonNode/ObjectMapperでレスポンスを組み立てる箇所がある。
 * Jackson3のコンバータはJsonNode.class(Jackson2)を誤って受理した上でデシリアライズに失敗するため、
 * 各クライアントの構築時にJackson3コンバータを外し、Jackson2コンバータへ差し替える
 * (legacy-api/content-serviceのLegacyJacksonRestClientConfigと同じ実装)。
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
