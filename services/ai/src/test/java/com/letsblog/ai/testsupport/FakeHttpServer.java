package com.letsblog.ai.testsupport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * HTTP境界(他サービス・SNS の API・provision-agent等)のサーバー側フェイク(issue #1823)。JDK標準の
 * {@link HttpServer}をループバックで起動し、メソッドとパス(正規表現)ごとに登録した応答を返す。
 * Mockitoは使わず、実際のHTTPでクライアントの実物({@code *Client})を通すための部品。
 *
 * <ul>
 *   <li>後から登録したルートが先に一致する(共通の既定応答を、個別のテストで上書きできる)。
 *       ハンドラが{@code null}を返したときは、その要求を扱わなかったものとして先に登録されたルートへ進む。</li>
 *   <li>どのルートにも一致しない要求は404(JSONの{@code error})にする。登録漏れがテストの成功に化けない。</li>
 *   <li>受けた要求(メソッド・パス・クエリ・ヘッダー・本文)をすべて記録する。</li>
 * </ul>
 */
public final class FakeHttpServer implements AutoCloseable {

    /** 受けた要求の記録。 */
    public record Request(String method, String path, String query, Headers headers, byte[] body) {
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    /** 返す応答。 */
    public record Response(int status, String contentType, byte[] body) {
        public static Response json(int status, String body) {
            return new Response(status, "application/json", body.getBytes(StandardCharsets.UTF_8));
        }

        public static Response ok(String jsonBody) {
            return json(200, jsonBody);
        }

        public static Response bytes(int status, String contentType, byte[] body) {
            return new Response(status, contentType, body);
        }

        public static Response empty(int status) {
            return new Response(status, null, new byte[0]);
        }
    }

    private record Route(String method, Pattern path, Function<Request, Response> handler) {
    }

    private final HttpServer server;
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public FakeHttpServer() {
        this(false);
    }

    /**
     * {@code allInterfaces}がtrueなら、ループバックだけでなく全インタフェースで待ち受ける。宛先検査
     * ({@code ConnectionDestinationGuard})はループバックを必ず拒否するので、検査を通る接続先
     * (プロジェクト単位の上書き)を実物の検査のまま試すには、ループバックでないアドレスで受ける必要がある。
     * そのアドレスは{@link #lanBaseUrl()}で得る。
     */
    public static FakeHttpServer onAllInterfaces() {
        return new FakeHttpServer(true);
    }

    private FakeHttpServer(boolean allInterfaces) {
        try {
            server = HttpServer.create(allInterfaces ? new InetSocketAddress(0) : new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders(), body);
            requests.add(request);
            Response response = resolve(request);
            exchange.getResponseHeaders().add("Content-Type",
                    response.contentType() == null ? "application/json" : response.contentType());
            if (response.body().length == 0) {
                exchange.sendResponseHeaders(response.status(), -1);
            } else {
                exchange.sendResponseHeaders(response.status(), response.body().length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response.body());
                }
            }
            exchange.close();
        });
        // 並行して届く要求を並行に処理する(複数環境への並列呼び出しが重なることを観測できるように)。
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    private Response resolve(Request request) {
        for (int i = routes.size() - 1; i >= 0; i--) {
            Route route = routes.get(i);
            if ((route.method().equals("*") || route.method().equalsIgnoreCase(request.method()))
                    && route.path().matcher(request.path()).matches()) {
                Response response = route.handler().apply(request);
                if (response != null) {
                    return response;
                }
            }
        }
        return Response.json(404, "{\"error\":\"no route\"}");
    }

    /** メソッド(「*」は任意)とパス(正規表現、全体一致)に対する応答を登録する。 */
    public FakeHttpServer on(String method, String pathRegex, Function<Request, Response> handler) {
        routes.add(new Route(method, Pattern.compile(pathRegex), handler));
        return this;
    }

    public FakeHttpServer on(String method, String pathRegex, Response response) {
        return on(method, pathRegex, request -> response);
    }

    /** 200でJSONを返す。 */
    public FakeHttpServer json(String method, String pathRegex, String jsonBody) {
        return on(method, pathRegex, Response.ok(jsonBody));
    }

    /** 指定ステータスでJSON(または文字列)を返す。 */
    public FakeHttpServer status(String method, String pathRegex, int status, String body) {
        return on(method, pathRegex, Response.json(status, body));
    }

    /** 一致するたびに先頭から順に返し、最後の応答は以降も返し続ける。 */
    public FakeHttpServer sequence(String method, String pathRegex, Response... responses) {
        AtomicInteger index = new AtomicInteger();
        return on(method, pathRegex, request -> responses[Math.min(index.getAndIncrement(), responses.length - 1)]);
    }

    /** 登録した応答と、記録した要求をすべて消す(JVMで共有するサーバーをテストごとに初期化する用)。 */
    public void reset() {
        routes.clear();
        requests.clear();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * このホストの、ループバックでも169.254/16でもない起動中のIPv4アドレスで組んだベースURL(宛先検査を通る)。
     * {@link #onAllInterfaces()}で作ったサーバーに限り届く。該当するインタフェースが無ければテストを失敗させる。
     */
    public String lanBaseUrl() {
        try {
            for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
                    if (address instanceof java.net.Inet4Address && !address.isLinkLocalAddress()) {
                        return "http://" + address.getHostAddress() + ":" + server.getAddress().getPort();
                    }
                }
            }
        } catch (java.net.SocketException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("ループバックでない起動中のIPv4インタフェースが無く、宛先検査を通る接続先を用意できません");
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** メソッドとパス(正規表現)に一致した要求だけを返す。 */
    public List<Request> requests(String method, String pathRegex) {
        Pattern pattern = Pattern.compile(pathRegex);
        List<Request> matched = new ArrayList<>();
        for (Request request : requests) {
            if ((method.equals("*") || method.equalsIgnoreCase(request.method()))
                    && pattern.matcher(request.path()).matches()) {
                matched.add(request);
            }
        }
        return matched;
    }

    public List<String> paths() {
        return requests.stream().map(Request::path).toList();
    }

    public Map<String, Integer> countsByPath() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Request request : requests) {
            counts.merge(request.path(), 1, Integer::sum);
        }
        return counts;
    }

    public Request last() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }
}
