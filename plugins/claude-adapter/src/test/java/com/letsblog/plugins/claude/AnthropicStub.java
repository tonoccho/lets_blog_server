package com.letsblog.plugins.claude;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Anthropic API を模したスタブ。受けた要求を記録し、設定した応答を返す。 */
final class AnthropicStub implements AutoCloseable {

    record Received(String method, String pathAndQuery, String apiKey, String version, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile long delayMillis;

    AnthropicStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("x-api-key"),
                    exchange.getRequestHeaders().getFirst("anthropic-version"),
                    body));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(int newStatus, String newBody) {
        this.status = newStatus;
        this.responseBody = newBody;
    }

    void delay(long millis) {
        this.delayMillis = millis;
    }

    List<Received> received() {
        return received;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
