package com.letsblog.analytics.testsupport;

/**
 * Google の API(GA4 Data API・AdSense Management API)のサーバー側フェイクの応答(issue #1827)。
 * {@link AnalyticsWorld}の{@code /ga-data}・{@code /adsense}配下に載せる。
 */
public final class GoogleFakes {

    private GoogleFakes() {
    }

    /**
     * GA4 の runReport(合計・日次・チャネル別の3回呼ばれる)に、
     * sessions=120 / activeUsers=80 / pageViews=300 を返す。
     */
    public static void gaReport(FakeHttpServer http, String propertyId) {
        http.on("POST", "/ga-data/v1beta/properties/" + propertyId + ":runReport", request -> {
            String body = request.bodyText();
            if (body.contains("\"date\"")) {
                return FakeHttpServer.Response.ok("""
                        {"rows":[{"dimensionValues":[{"value":"20240101"}],
                          "metricValues":[{"value":"10"},{"value":"5"},{"value":"20"}]}]}
                        """);
            }
            if (body.contains("sessionDefaultChannelGroup")) {
                return FakeHttpServer.Response.ok("""
                        {"rows":[{"dimensionValues":[{"value":"Organic Search"}],
                          "metricValues":[{"value":"120"},{"value":"80"},{"value":"300"}]}]}
                        """);
            }
            return FakeHttpServer.Response.ok("""
                    {"rows":[{"metricValues":[{"value":"120"},{"value":"80"},{"value":"300"}]}]}
                    """);
        });
    }

    /** AdSense の reports:generate(合計・日次・プラットフォーム別の3回呼ばれる)に、収益 12.34 / クリック 100 / 表示 5000 を返す。 */
    public static void adSenseReport(FakeHttpServer http, String accountId) {
        http.on("GET", "/adsense/v2/accounts/" + accountId + "/reports:generate", request -> {
            String query = request.query();
            if (query.contains("dimensions=DATE")) {
                return FakeHttpServer.Response.ok("""
                        {"rows":[{"cells":[{"value":"2024-01-01"},{"value":"1.23"},{"value":"10"},{"value":"500"}]}]}
                        """);
            }
            if (query.contains("dimensions=PLATFORM_TYPE_NAME")) {
                return FakeHttpServer.Response.ok("""
                        {"rows":[{"cells":[{"value":"Desktop"},{"value":"12.34"},{"value":"100"},{"value":"5000"}]}]}
                        """);
            }
            return FakeHttpServer.Response.ok("""
                    {"totals":{"cells":[{"value":"12.34"},{"value":"100"},{"value":"5000"}]}}
                    """);
        });
    }
}
