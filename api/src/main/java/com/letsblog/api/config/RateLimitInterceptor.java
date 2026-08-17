package com.letsblog.api.config;

import com.letsblog.api.service.AppSettingService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String UPLOAD_ENDPOINT = "upload-endpoint";

    /**
     * 認証情報を扱わない読み取り専用の状態確認エンドポイント。ブルートフォース対策の
     * 対象ではないため、他の/auth/配下のエンドポイントと同じ厳しいauth-endpointバケットを
     * 共有させない(共有させると、画面表示のたびに呼ばれるこれらの呼び出しだけで枠を使い切り、
     * 本来保護すべきログイン・セットアップ自体がブロックされてしまう。issue #321)。
     */
    private static final Set<String> AUTH_STATUS_CHECK_PATHS =
            Set.of("/api/auth/setup-status", "/api/auth/totp/status");

    /**
     * 実ファイルアップロードではなく、軽量なメタデータ取得/設定更新のエンドポイント。
     * パス文字列に"/image"を含むためupload-endpointの厳しい制限(デフォルト10req/h、
     * アプリ全体で共有)に巻き込まれると、パネルを開いたり設定を変更しただけで枠を
     * 消費し、本来の画像生成(/api/ai/image)自体が429になってしまう(issue #442)。
     */
    private static final Set<String> LIGHTWEIGHT_IMAGE_METADATA_PATH_SUFFIXES = Set.of(
            "/image-options", "/image-generation-prompt-defaults", "/image-generation-size-defaults");

    private final RateLimiterRegistry rateLimiterRegistry;
    private final AppSettingService appSettingService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestPath = request.getRequestURI();
        String rateLimiterName = getRateLimiterName(requestPath);

        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(rateLimiterName);

        /*
         * upload-endpointのみ、管理画面(システム設定)からリクエスト数を変更できる(issue #444)。
         * limitRefreshPeriod(期間)はResilience4jの制約により実行中のインスタンスへ安全に反映する
         * 手段がないため対象外(env変数UPLOAD_RATE_LIMIT_PERIODのまま、再起動時のみ反映)。
         */
        if (UPLOAD_ENDPOINT.equals(rateLimiterName)) {
            int limit = appSettingService.getUploadRateLimitRequests();
            if (limit == AppSettingService.UNLIMITED) {
                return true;
            }
            rateLimiter.changeLimitForPeriod(limit);
        }

        if (!rateLimiter.acquirePermission()) {
            log.warn("Rate limit exceeded for {} (limiter: {})", requestPath, rateLimiterName);
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            return false;
        }

        return true;
    }

    private String getRateLimiterName(String requestPath) {
        if (AUTH_STATUS_CHECK_PATHS.contains(requestPath)) {
            return "api-global";
        } else if (requestPath.contains("/auth/") || requestPath.contains("/login") || requestPath.contains("/register")) {
            return "auth-endpoint";
        } else if (isLightweightImageMetadataPath(requestPath)) {
            return "api-global";
        } else if (requestPath.contains("/upload") || requestPath.contains("/image")) {
            return UPLOAD_ENDPOINT;
        }
        return "api-global";
    }

    private boolean isLightweightImageMetadataPath(String requestPath) {
        return LIGHTWEIGHT_IMAGE_METADATA_PATH_SUFFIXES.stream().anyMatch(requestPath::endsWith);
    }
}
