package com.letsblog.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受け入れテスト専用の故障注入(#1519)。指定したプロジェクトの
 * {@code GET /api/projects/{id}/users} だけを503にする。
 *
 * <p><b>本番構成には存在しない。</b>{@code app.fault-injection.enabled=true} のときだけBeanが
 * 作られ、その設定は {@code docker-compose.e2e-stubs.yml} の {@code gateway.environment} にしか
 * 無い。無効な構成ではフィルタ自体が存在しないので、制御パスは通常どおりルート表に当たって
 * 404になり、注入の手段は残らない。
 *
 * <p>制御は {@code PUT/DELETE /api/__fault-injection/project-users/{projectId}}(注入/解除、204)。
 * 注入はプロジェクトID単位なので、同じエンドポイントを使う他のシナリオ(ダッシュボードの
 * メンバー一覧など)を並列実行しても巻き込まない。project-serviceのadmin認可はgatewayを経由せず
 * identity-serviceを直接呼ぶため、この注入の影響を受けない。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(name = "app.fault-injection.enabled", havingValue = "true")
public class FaultInjectionWebFilter implements WebFilter {

    private static final Pattern CONTROL_PATH =
            Pattern.compile("^/api/__fault-injection/project-users/(.*)$");
    // 18桁まで: Long.parseLongが必ず成功する範囲。それを超えるIDは注入対象外として素通しする
    private static final Pattern MEMBERS_PATH = Pattern.compile("^/api/projects/(\\d{1,18})/users/?$");

    private final Set<Long> failingProjects = ConcurrentHashMap.newKeySet();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();

        Matcher control = CONTROL_PATH.matcher(path);
        if (control.matches()) {
            return handleControl(exchange, method, control.group(1));
        }
        Matcher members = MEMBERS_PATH.matcher(path);
        if (HttpMethod.GET.equals(method) && members.matches()
                && failingProjects.contains(Long.parseLong(members.group(1)))) {
            return respond(exchange, HttpStatus.SERVICE_UNAVAILABLE);
        }
        return chain.filter(exchange);
    }

    private Mono<Void> handleControl(ServerWebExchange exchange, HttpMethod method, String rawId) {
        boolean put = HttpMethod.PUT.equals(method);
        if (!put && !HttpMethod.DELETE.equals(method)) {
            return respond(exchange, HttpStatus.METHOD_NOT_ALLOWED);
        }
        long projectId;
        try {
            projectId = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            return respond(exchange, HttpStatus.BAD_REQUEST);
        }
        if (put) {
            failingProjects.add(projectId);
        } else {
            failingProjects.remove(projectId);
        }
        return respond(exchange, HttpStatus.NO_CONTENT);
    }

    private Mono<Void> respond(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
