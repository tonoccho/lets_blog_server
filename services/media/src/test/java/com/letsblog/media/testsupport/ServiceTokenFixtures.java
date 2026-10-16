package com.letsblog.media.testsupport;

import com.letsblog.common.auth.ServiceTokenClient;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.client.RestClient;

/**
 * 実物の{@link ServiceTokenClient}を、トークンエンドポイント(Keycloakの代わり)をサーバー側で模した
 * ループバックのHTTPサーバーへ向けるための部品(issue #1820)。
 *
 * <p>{@link ServiceTokenClient}は自前のクラスなのでモックしない。外部との通信であるHTTPの先(トークン
 * エンドポイント)だけを模す。
 */
public final class ServiceTokenFixtures {

    /** トークンエンドポイントが返すアクセストークン。 */
    public static final String TOKEN = "svc-token";

    /** トークンエンドポイントのパス。 */
    public static final String TOKEN_PATH = "/token";

    private ServiceTokenFixtures() {
    }

    /** {@code baseUrl + "/token"}をトークンエンドポイントとする実物のクライアント。 */
    public static ServiceTokenClient client(String baseUrl) {
        return new ServiceTokenClient(RestClient.builder(), baseUrl + TOKEN_PATH, "media-service", "secret");
    }

    /** 到達できないトークンエンドポイントを向く実物のクライアント({@code getAccessToken()}は例外になる)。 */
    public static ServiceTokenClient unreachable() {
        return client("http://127.0.0.1:1");
    }

    /**
     * リクエストがトークンエンドポイントへのものなら{@link #TOKEN}を返して{@code true}を返す。
     * {@code HttpServer}のハンドラの先頭で呼ぶ。
     */
    public static boolean handleTokenRequest(HttpExchange exchange) throws IOException {
        if (!TOKEN_PATH.equals(exchange.getRequestURI().getPath())) {
            return false;
        }
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = ("{\"access_token\":\"" + TOKEN + "\",\"expires_in\":3600}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
        return true;
    }
}
