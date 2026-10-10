package com.letsblog.platform.service;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/** ComfyUIの {@code /system_stats} が200を返すかを確かめる(issue #1399 要件5)。 */
@Component
public class HttpComputeDeviceHealthProbe implements ComputeDeviceHealthProbe {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final RestClient client;

    @Autowired
    public HttpComputeDeviceHealthProbe() {
        this(builderWithTimeout());
    }

    /** テスト専用。 */
    HttpComputeDeviceHealthProbe(RestClient.Builder builder) {
        this.client = builder.build();
    }

    private static RestClient.Builder builderWithTimeout() {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("compute-device-health")).requestFactory(factory);
    }

    @Override
    public boolean isHealthy(String url) {
        try {
            return client.get().uri(url).retrieve().toBodilessEntity().getStatusCode().value() == 200;
        } catch (RestClientException e) {
            return false;
        }
    }
}
