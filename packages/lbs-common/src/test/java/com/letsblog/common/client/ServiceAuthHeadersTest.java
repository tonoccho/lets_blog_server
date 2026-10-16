package com.letsblog.common.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/** サービス間呼び出しのAuthorizationヘッダー付与(#1831)。トークンが無い・空白のときは付けない。 */
class ServiceAuthHeadersTest {

    private static HttpHeaders apply(java.util.function.Consumer<HttpHeaders> consumer) {
        HttpHeaders headers = new HttpHeaders();
        consumer.accept(headers);
        return headers;
    }

    @Test
    void bearerSetsTheTokenFromTheSupplier() {
        assertThat(apply(ServiceAuthHeaders.bearer(() -> "abc")).getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer abc");
    }

    @Test
    void bearerSkipsANullToken() {
        assertThat(apply(ServiceAuthHeaders.bearer(() -> null)).containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    void bearerSkipsABlankToken() {
        assertThat(apply(ServiceAuthHeaders.bearer(() -> "  ")).containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    void forwardedBearerFromARequestCopiesTheHeaderVerbatim() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer xyz");

        assertThat(apply(ServiceAuthHeaders.forwardedBearer(request)).getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer xyz");
    }

    @Test
    void forwardedBearerFromARequestWithoutTheHeaderSetsNothing() {
        assertThat(apply(ServiceAuthHeaders.forwardedBearer(new MockHttpServletRequest()))
                .containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    void forwardedBearerFromAStringSkipsNullAndBlank() {
        assertThat(apply(ServiceAuthHeaders.forwardedBearer((String) null))
                .containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(apply(ServiceAuthHeaders.forwardedBearer(" "))
                .containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
        assertThat(apply(ServiceAuthHeaders.forwardedBearer("Bearer q")).getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer q");
    }
}
