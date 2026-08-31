package com.letsblog.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ルーティング表(#560)。Spring Cloud Gatewayはこの環境のSpring Boot 4.1系と非互換のため
 * (build.gradleのコメント参照)、独自のルーティング設定として定義している。
 * リストの先頭から順に評価し、最初にマッチしたルートを使う(先勝ち。特定度の高いパスを
 * 先に置くこと)。
 */
@ConfigurationProperties(prefix = "app.gateway")
public class RouteProperties {

    /** どのルートにもマッチしなかった場合のフォールバック先(#560。移行期間中はlegacy-api)。 */

    private Duration defaultResponseTimeout = Duration.ofSeconds(60);

    private List<Route> routes = new ArrayList<>();



    public Duration getDefaultResponseTimeout() {
        return defaultResponseTimeout;
    }

    public void setDefaultResponseTimeout(Duration defaultResponseTimeout) {
        this.defaultResponseTimeout = defaultResponseTimeout;
    }

    public List<Route> getRoutes() {
        return routes;
    }

    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    public static class Route {
        private String id;
        private String uri;
        private List<String> paths = new ArrayList<>();
        private Duration responseTimeout;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public List<String> getPaths() {
            return paths;
        }

        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
        }
    }
}
