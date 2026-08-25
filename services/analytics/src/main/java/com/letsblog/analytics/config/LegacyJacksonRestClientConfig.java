package com.letsblog.analytics.config;

import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、GoogleAnalyticsClient/
 * AdSenseClientはGoogleのOAuth2/GA4 Data API/AdSense Management APIレスポンスを
 * com.fasterxml.jackson.databind.JsonNode/ObjectNodeで組み立てている。Jackson3のコンバータは
 * JsonNode.class(Jackson2)を誤って受理した上でデシリアライズに失敗するため、各クライアントの構築時に
 * Jackson3コンバータを外し、Jackson2コンバータへ差し替える(legacy-api/ai-serviceと同じ理由)。
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
