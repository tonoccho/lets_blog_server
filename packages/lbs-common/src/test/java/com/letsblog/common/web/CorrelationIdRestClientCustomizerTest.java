package com.letsblog.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注入されたRestClient.Builderから作るクライアントが、呼び出し元スレッドのMDCの処理IDを
 * X-Correlation-Idで送ることの単体テスト(issue #1730)。
 */
class CorrelationIdRestClientCustomizerTest {

    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    private static MockRestServiceServer bind(RestClient.Builder builder) {
        new CorrelationIdRestClientCustomizer().customize(builder);
        return MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void MDCに処理IDがあると要求にその値のヘッダが付く() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        server.expect(requestTo("http://callee/x")).andExpect(method(HttpMethod.GET))
                .andExpect(header(CorrelationIdFilter.CORRELATION_ID_HEADER, "abc-123"))
                .andRespond(withSuccess());
        MDC.put(CorrelationIdFilter.MDC_KEY, "abc-123");

        builder.build().get().uri("http://callee/x").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void MDCに処理IDが無いとヘッダは付かない() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        server.expect(requestTo("http://callee/x"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER))
                        .isNull())
                .andRespond(withSuccess());

        builder.build().get().uri("http://callee/x").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void MDCの処理IDが空白ならヘッダは付かない() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        server.expect(requestTo("http://callee/x"))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER))
                        .isNull())
                .andRespond(withSuccess());
        MDC.put(CorrelationIdFilter.MDC_KEY, "  ");

        builder.build().get().uri("http://callee/x").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void 呼び出し側が明示したヘッダは上書きしない() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = bind(builder);
        server.expect(requestTo("http://callee/x"))
                .andExpect(header(CorrelationIdFilter.CORRELATION_ID_HEADER, "explicit"))
                .andRespond(withSuccess());
        MDC.put(CorrelationIdFilter.MDC_KEY, "from-mdc");

        builder.build().get().uri("http://callee/x")
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, "explicit")
                .retrieve().toBodilessEntity();

        server.verify();
    }
}
