package com.letsblog.plugins.comfyui;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** ComfyUI のネイティブ API を模したスタブ。受けた要求を記録し、設定した応答を返す。 */
final class ComfyUiStub implements AutoCloseable {

    record Received(String method, String pathAndQuery, String contentType, byte[] body) {

        String path() {
            int q = pathAndQuery.indexOf('?');
            return q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
        }

        String bodyText() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }
    }

    /** 生成結果として /view が返す PNG 風のバイト列(ファイル名ごとに変える)。 */
    static byte[] imageBytes(String filename) {
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] name = filename.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + name.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(name, 0, out, header.length, name.length);
        return out;
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger historyCalls = new AtomicInteger();

    volatile int promptStatus = 200;
    volatile String promptResponse = "{\"prompt_id\":\"p1\"}";
    volatile int historyStatus = 200;
    /** 完了を返すまでに空の /history を返す回数。Integer.MAX_VALUE なら完了しない。 */
    volatile int pendingPolls;
    volatile int outputCount = 1;
    volatile int viewStatus = 200;
    volatile int uploadStatus = 200;
    volatile String uploadResponse = "{\"name\":\"ref.png\",\"subfolder\":\"\",\"type\":\"input\"}";
    volatile int loraInfoStatus = 200;
    volatile int checkpointInfoStatus = 200;

    ComfyUiStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String target = exchange.getRequestURI().toString();
            Received request = new Received(exchange.getRequestMethod(), target,
                    exchange.getRequestHeaders().getFirst("Content-Type"), body);
            received.add(request);
            int status = 200;
            byte[] out;
            String contentType = "application/json";
            switch (request.path()) {
                case "/object_info/CheckpointLoaderSimple" -> {
                    status = checkpointInfoStatus;
                    out = bytes(status == 200 ? """
                            {"CheckpointLoaderSimple":{"input":{"required":{"ckpt_name":[["a.safetensors","b.safetensors"]]}}}}"""
                            : "{}");
                }
                case "/object_info/KSampler" -> out = bytes("""
                        {"KSampler":{"input":{"required":{"sampler_name":[["euler","dpmpp_2m"]],"scheduler":[["normal","karras"]]}}}}""");
                case "/object_info/LoraLoader" -> {
                    status = loraInfoStatus;
                    out = status == 200 ? bytes("""
                            {"LoraLoader":{"input":{"required":{"lora_name":[["style.safetensors"]]}}}}""") : bytes("{}");
                }
                case "/prompt" -> {
                    status = promptStatus;
                    out = bytes(status == 200 ? promptResponse : "{\"error\":\"bad workflow\"}");
                }
                case "/upload/image" -> {
                    status = uploadStatus;
                    out = bytes(status == 200 ? uploadResponse : "upload failed");
                }
                case "/view" -> {
                    status = viewStatus;
                    contentType = "image/png";
                    out = status == 200 ? imageBytes(filenameOf(target)) : bytes("view failed");
                }
                default -> {
                    if (request.path().startsWith("/history/")) {
                        status = historyStatus;
                        out = bytes(status == 200 ? history() : "history failed");
                    } else {
                        status = 404;
                        out = bytes("{}");
                    }
                }
            }
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    private String history() {
        if (historyCalls.incrementAndGet() <= pendingPolls) {
            return "{}";
        }
        StringBuilder images = new StringBuilder();
        for (int i = 1; i <= outputCount; i++) {
            images.append(i > 1 ? "," : "")
                    .append("{\"filename\":\"letsblog_").append(i).append(".png\",\"subfolder\":\"\",\"type\":\"output\"}");
        }
        return "{\"p1\":{\"outputs\":{\"7\":{},\"9\":{\"images\":[" + images + "]}}}}";
    }

    private static String filenameOf(String target) {
        int at = target.indexOf("filename=");
        String rest = target.substring(at + "filename=".length());
        int amp = rest.indexOf('&');
        return URLDecoder.decode(amp < 0 ? rest : rest.substring(0, amp), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Received> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    List<Received> receivedAt(String path) {
        return received().stream().filter(r -> r.path().equals(path)).toList();
    }

    int historyCallCount() {
        return (int) received().stream().filter(r -> r.path().startsWith("/history/")).count();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
