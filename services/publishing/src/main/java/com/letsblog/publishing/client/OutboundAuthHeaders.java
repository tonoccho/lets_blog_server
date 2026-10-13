package com.letsblog.publishing.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.ServiceAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * project-serviceの内部ブリッジ呼び出し({@link ProjectServiceClient})へ付ける認証(issue #1702)。
 * project-serviceの{@code OutboundAuthHeaders}(#1409)・media-serviceの同名クラス(#1405)と同じ理由・同じ形。
 *
 * <p>HTTPリクエストの処理中は、従来どおり呼び出し元ユーザーのBearerを転送する(同期APIの挙動を変えない)。
 * 一方、全環境インストールのジョブ({@code BulkInstallJobRunner}、issue #1702)のような{@code @Async}スレッドには
 * バインドされたリクエストが無く、{@code HttpServletRequest}のプロキシを読むと
 * 「No thread-bound request found」になる。その場合はこのサービス自身のClient Credentialsトークンを使う。
 * ユーザーのトークンを引き回すと、5分で失効する(#1083)ため待ち行列で待つ間や長い処理の途中で切れる。
 * 呼び先の内部ブリッジ({@code /api/internal/project/**})は有効なJWTがあれば通る。
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
