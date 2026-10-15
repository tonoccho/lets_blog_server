package com.letsblog.publishing.cms;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * provision-agent(HTTP境界)のサーバー側フェイク(issue #1814)。JDK標準の{@link HttpServer}を
 * ループバックで起動し、パスごとに登録した応答を返す。登録の無いパスは404(JSONのerror)にする。
 * 受けたリクエスト(パス・本文・トークンヘッダ)を記録する。
 */
final class FakeProvisionAgent implements AutoCloseable {

    record Request(String path, String body, String token) {
    }

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Response> responses = new HashMap<>();
    final List<Request> requests = new ArrayList<>();

    FakeProvisionAgent() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            String path = exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(new Request(path, body, exchange.getRequestHeaders().getFirst("X-Provision-Token")));
            }
            Response response = responses.getOrDefault(path, new Response(404, "{\"error\":\"no route\"}"));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    FakeProvisionAgent respond(String path, String jsonBody) {
        return respond(path, 200, jsonBody);
    }

    FakeProvisionAgent respond(String path, int status, String jsonBody) {
        responses.put(path, new Response(status, jsonBody));
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<String> paths() {
        synchronized (requests) {
            return requests.stream().map(Request::path).toList();
        }
    }

    Request last() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
