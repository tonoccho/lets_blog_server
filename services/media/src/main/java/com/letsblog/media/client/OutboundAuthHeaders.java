package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.ServiceAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 内部ブリッジ呼び出し({@link ProjectServiceClient}・{@link AiGenerationClient})へ付ける認証。
 *
 * <p>HTTPリクエストの処理中は、従来どおり呼び出し元ユーザーのBearerを転送する。一方、画像生成ジョブ
 * (issue #1405)のような{@code @Async}スレッドにはバインドされたリクエストが無く、
 * {@code HttpServletRequest}のプロキシを読むと「No thread-bound request found」になる
 * (QAで判明)。その場合はこのサービス自身のClient Credentialsトークンを使う。ユーザーのトークンを
 * 引き回すと、5分で失効する(#1083)ため長い生成の途中で切れる。呼び先の内部ブリッジ
 * ({@code /api/internal/**})は有効なJWTがあれば通る。
 */
@Component
public class OutboundAuthHeaders {

    private final HttpServletRequest request;
    private final ServiceTokenClient serviceTokenClient;

    public OutboundAuthHeaders(HttpServletRequest request, ServiceTokenClient serviceTokenClient) {
        this.request = request;
        this.serviceTokenClient = serviceTokenClient;
    }

    public Consumer<HttpHeaders> current() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return ServiceAuthHeaders.clientCredentials(serviceTokenClient);
        }
        return ServiceAuthHeaders.forwardedBearer(request);
    }
}
