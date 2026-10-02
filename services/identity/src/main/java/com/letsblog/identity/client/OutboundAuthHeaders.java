package com.letsblog.identity.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.ServiceAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 内部ブリッジ呼び出し({@link ProjectServiceClient}・{@link PublishingServiceClient})へ付ける認証
 * (issue #1324)。
 *
 * <p>HTTPリクエストの処理中は、従来どおり呼び出し元ユーザーのBearerを転送する。一方、
 * {@code project.environment-bound}を受けた補填はRabbitのリスナースレッドで動き、バインドされた
 * リクエストが無い。そこで{@code HttpServletRequest}のプロキシを読むと「No thread-bound request
 * found」になり、トークンも無く401になる。その場合はこのサービス自身のClient Credentialsトークンを
 * 使う。呼び先の{@code /api/internal/project/**}・{@code /api/internal/publishing/**}は有効なJWTが
 * あれば通り、ロールでは絞っていない(media-serviceの同名クラスと同じ前提)。
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
