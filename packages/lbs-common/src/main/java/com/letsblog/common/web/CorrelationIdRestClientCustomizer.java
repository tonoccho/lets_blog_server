package com.letsblog.common.web;

import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.web.client.RestClient;

/**
 * Springが注入する{@link RestClient.Builder}から作る全クライアントの要求に、呼び出し元スレッドの
 * MDCにある処理ID(相関ID)を{@value CorrelationIdFilter#CORRELATION_ID_HEADER}として付ける
 * (issue #1730)。{@code SyncServiceClient}を使わない素のRestClientでも、呼び出し先の
 * {@link CorrelationIdFilter}が新しいUUIDを採番せず、同じ処理IDでログが続く。
 *
 * <p>MDCに処理IDが無い(空白を含む)ときはヘッダを付けない。呼び出し側が同名ヘッダを既に明示している
 * 場合はそれを尊重し上書きしない({@code SyncServiceClient}は同じ値を設定するため干渉しない)。
 * 各サービスが{@code @Bean}として登録する({@link CorrelationIdFilter}と同じ流儀)。
 */
public class CorrelationIdRestClientCustomizer implements RestClientCustomizer {

    @Override
    public void customize(RestClient.Builder restClientBuilder) {
        restClientBuilder.requestInterceptor((request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()
                    && !request.getHeaders().containsHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)) {
                request.getHeaders().set(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
