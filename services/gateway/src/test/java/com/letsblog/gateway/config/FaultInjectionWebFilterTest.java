package com.letsblog.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受け入れテスト用の故障注入(#1519)。注入はプロジェクトID単位で、対象の
 * {@code GET /api/projects/{id}/users} だけを5xxにし、他のAPI・他のプロジェクトを巻き込まない。
 */
class FaultInjectionWebFilterTest {

    private static final String CONTROL = "/api/__fault-injection/project-users/";

    private final FaultInjectionWebFilter filter = new FaultInjectionWebFilter();
    private final AtomicInteger forwarded = new AtomicInteger();
    private final WebFilterChain chain = ex -> {
        forwarded.incrementAndGet();
        return Mono.empty();
    };

    private ServerWebExchange run(HttpMethod method, String path) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        return exchange;
    }

    private HttpStatus control(HttpMethod method, String id) {
        return (HttpStatus) run(method, CONTROL + id).getResponse().getStatusCode();
    }

    @Test
    void 注入していないときはメンバー一覧を素通しする() {
        ServerWebExchange exchange = run(HttpMethod.GET, "/api/projects/7/users");

        assertEquals(1, forwarded.get());
        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void 注入したプロジェクトのメンバー一覧だけが503になる() {
        assertEquals(HttpStatus.NO_CONTENT, control(HttpMethod.PUT, "7"));

        ServerWebExchange target = run(HttpMethod.GET, "/api/projects/7/users");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, target.getResponse().getStatusCode());
        assertEquals(0, forwarded.get());

        // 同じプロジェクトの他API、別プロジェクトのメンバー一覧は素通し
        assertEquals(null, run(HttpMethod.GET, "/api/projects/7").getResponse().getStatusCode());
        assertEquals(null, run(HttpMethod.GET, "/api/projects/8/users").getResponse().getStatusCode());
        assertEquals(null, run(HttpMethod.GET, "/api/projects/7/users/3").getResponse().getStatusCode());
        assertEquals(3, forwarded.get());
    }

    @Test
    void メンバー一覧のGET以外は注入中でも素通しする() {
        control(HttpMethod.PUT, "7");

        ServerWebExchange post = run(HttpMethod.POST, "/api/projects/7/users");

        assertEquals(null, post.getResponse().getStatusCode());
        assertEquals(1, forwarded.get());
    }

    @Test
    void 末尾スラッシュ付きのメンバー一覧も注入の対象になる() {
        control(HttpMethod.PUT, "7");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                run(HttpMethod.GET, "/api/projects/7/users/").getResponse().getStatusCode());
    }

    @Test
    void 解除すると元に戻る() {
        control(HttpMethod.PUT, "7");
        assertEquals(HttpStatus.NO_CONTENT, control(HttpMethod.DELETE, "7"));

        assertEquals(null, run(HttpMethod.GET, "/api/projects/7/users").getResponse().getStatusCode());
        assertEquals(1, forwarded.get());
    }

    @Test
    void 注入していないプロジェクトの解除も成功する() {
        assertEquals(HttpStatus.NO_CONTENT, control(HttpMethod.DELETE, "99"));
    }

    @Test
    void 注入の複数プロジェクト同時保持は互いに干渉しない() {
        control(HttpMethod.PUT, "7");
        control(HttpMethod.PUT, "8");
        control(HttpMethod.DELETE, "7");

        assertEquals(null, run(HttpMethod.GET, "/api/projects/7/users").getResponse().getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                run(HttpMethod.GET, "/api/projects/8/users").getResponse().getStatusCode());
    }

    @Test
    void プロジェクトIDが数値でない制御リクエストは400で素通ししない() {
        assertEquals(HttpStatus.BAD_REQUEST, control(HttpMethod.PUT, "abc"));
        assertEquals(0, forwarded.get());
    }

    @Test
    void Long範囲を超える桁数のプロジェクトIDは500にならない() {
        String huge = "1".repeat(25);

        ServerWebExchange members = run(HttpMethod.GET, "/api/projects/" + huge + "/users");
        assertEquals(null, members.getResponse().getStatusCode());
        assertEquals(1, forwarded.get());

        assertEquals(HttpStatus.BAD_REQUEST, control(HttpMethod.PUT, huge));
        assertEquals(HttpStatus.BAD_REQUEST, control(HttpMethod.DELETE, huge));
    }

    @Test
    void 制御パスへのPUTとDELETE以外は405() {
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, control(HttpMethod.GET, "7"));
        assertEquals(0, forwarded.get());
    }

    @Test
    void メンバー一覧のパス形式でないリクエストは素通しする() {
        control(HttpMethod.PUT, "7");

        assertEquals(null, run(HttpMethod.GET, "/api/projects/abc/users").getResponse().getStatusCode());
        assertEquals(null, run(HttpMethod.GET, "/api/sites").getResponse().getStatusCode());
        assertEquals(2, forwarded.get());
    }

    @Test
    void 有効化の設定が無い構成では注入の手段そのものが存在しない() {
        new ApplicationContextRunner()
                .withUserConfiguration(FaultInjectionWebFilter.class)
                .run(context -> assertTrue(context.getBeansOfType(FaultInjectionWebFilter.class).isEmpty()));
    }

    @Test
    void 明示的にfalseでも注入の手段は存在しない() {
        new ApplicationContextRunner()
                .withUserConfiguration(FaultInjectionWebFilter.class)
                .withPropertyValues("app.fault-injection.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(FaultInjectionWebFilter.class).isEmpty()));
    }

    @Test
    void 受け入れテスト構成の設定でだけ注入の手段が存在する() {
        new ApplicationContextRunner()
                .withUserConfiguration(FaultInjectionWebFilter.class)
                .withPropertyValues("app.fault-injection.enabled=true")
                .run(context -> assertEquals(1, context.getBeansOfType(FaultInjectionWebFilter.class).size()));
    }
}
