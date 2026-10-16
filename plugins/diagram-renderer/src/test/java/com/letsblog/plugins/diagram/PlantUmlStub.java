package com.letsblog.plugins.diagram;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PlantUML サーバー(GET /png/&lt;エンコード&gt;)を模したスタブ。受けた要求を記録し、設定した応答を返す。 */
final class PlantUmlStub implements AutoCloseable {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    record Received(String method, String path, String authorization) {
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile byte[] responseBody = PNG;
    private volatile Map<String, String> headers = new LinkedHashMap<>();
    private volatile long delayMillis;

    PlantUmlStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(status, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(int newStatus, byte[] newBody, Map<String, String> newHeaders) {
        this.status = newStatus;
        this.responseBody = newBody;
        this.headers = new LinkedHashMap<>(newHeaders);
    }

    void delay(long millis) {
        this.delayMillis = millis;
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
