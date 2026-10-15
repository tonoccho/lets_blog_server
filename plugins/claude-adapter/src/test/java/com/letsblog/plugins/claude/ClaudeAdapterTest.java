package com.letsblog.plugins.claude;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** アダプターを実際に起動し、Anthropic を模したスタブに対して契約(CONTRACTS.md §1)を確認する。 */
class ClaudeAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OK_MESSAGE = """
            {"id":"msg_1","type":"message","role":"assistant",
             "content":[{"type":"text","text":"こんにちは"}],"stop_reason":"end_turn"}""";

    private AnthropicStub anthropic;
    private ClaudeAdapter adapter;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        anthropic = new AnthropicStub();
        adapter = startAdapter(anthropic.baseUrl(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        adapter.close();
        anthropic.close();
    }

    private static ClaudeAdapter startAdapter(String anthropicBaseUrl, Duration timeout) throws IOException {
        return ClaudeAdapter.start(new ClaudeAdapter.Config(0, anthropicBaseUrl, timeout));
    }

    private HttpResponse<String> send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adapter.port() + path))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> chat(String body) throws Exception {
        return send("POST", "/chat/completions", "Bearer sk-ant-test", body);
    }

    private static String chatBody(String contentJson) {
        return "{\"model\":\"claude-sonnet-4-5\",\"stream\":false,\"messages\":"
                + "[{\"role\":\"user\",\"content\":" + contentJson + "}]}";
    }

    private static JsonNode json(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    private static void assertOpenAiError(HttpResponse<String> response, int status, String type) throws IOException {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = json(response.body()).get("error");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("type").asText()).isEqualTo(type);
        assertThat(error.get("code").asText()).isNotBlank();
    }

    // ---- POST /chat/completions ----

    @Test
    void stringContentIsTranslatedToMessagesApiAndTextIsReturned() throws Exception {
        anthropic.respond(200, OK_MESSAGE);

        HttpResponse<String> response = chat(chatBody("\"こんにちはと言って\""));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).at("/choices/0/message/content").asText()).isEqualTo("こんにちは");
        assertThat(json(response.body()).at("/choices/0/message/role").asText()).isEqualTo("assistant");
        assertThat(anthropic.received()).hasSize(1);
        AnthropicStub.Received sent = anthropic.received().get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.pathAndQuery()).isEqualTo("/v1/messages");
        assertThat(sent.version()).isEqualTo("2023-06-01");
        JsonNode body = json(sent.body());
        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("こんにちはと言って");
    }

    @Test
    void textAndImageAreTranslatedToImageSourceBlockBeforeText() throws Exception {
        anthropic.respond(200, OK_MESSAGE);
        String content = "[{\"type\":\"text\",\"text\":\"この画像は?\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,QUJD\"}}]";

        HttpResponse<String> response = chat(chatBody(content));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode blocks = json(anthropic.received().get(0).body()).at("/messages/0/content");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("type").asText()).isEqualTo("image");
        assertThat(blocks.get(0).at("/source/type").asText()).isEqualTo("base64");
        assertThat(blocks.get(0).at("/source/media_type").asText()).isEqualTo("image/png");
        assertThat(blocks.get(0).at("/source/data").asText()).isEqualTo("QUJD");
        assertThat(blocks.get(1).get("type").asText()).isEqualTo("text");
        assertThat(blocks.get(1).get("text").asText()).isEqualTo("この画像は?");
    }

    @Test
    void textOnlyArrayAndImageOnlyArrayAreAccepted() throws Exception {
        anthropic.respond(200, OK_MESSAGE);

        assertThat(chat(chatBody("[{\"type\":\"text\",\"text\":\"a\"}]")).statusCode()).isEqualTo(200);
        JsonNode textOnly = json(anthropic.received().get(0).body()).at("/messages/0/content");
        assertThat(textOnly).hasSize(1);
        assertThat(textOnly.get(0).get("type").asText()).isEqualTo("text");

        String imageOnly = "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,QUJD\"}}]";
        assertThat(chat(chatBody(imageOnly)).statusCode()).isEqualTo(200);
        JsonNode image = json(anthropic.received().get(1).body()).at("/messages/0/content");
        assertThat(image).hasSize(1);
        assertThat(image.get(0).get("type").asText()).isEqualTo("image");
    }

    @Test
    void bearerTokenIsSentAsAnthropicApiKey() throws Exception {
        anthropic.respond(200, OK_MESSAGE);

        send("POST", "/chat/completions", "Bearer sk-ant-secret", chatBody("\"hi\""));
        send("POST", "/chat/completions", "bearer   sk-ant-lower  ", chatBody("\"hi\""));

        assertThat(anthropic.received().get(0).apiKey()).isEqualTo("sk-ant-secret");
        assertThat(anthropic.received().get(1).apiKey()).isEqualTo("sk-ant-lower");
    }

    @Test
    void requestWithoutUsableBearerIs401AndNeverReachesAnthropic() throws Exception {
        String body = chatBody("\"hi\"");
        assertOpenAiError(send("POST", "/chat/completions", null, body), 401, "authentication_error");
        assertOpenAiError(send("POST", "/chat/completions", "Basic abc", body), 401, "authentication_error");
        assertOpenAiError(send("POST", "/chat/completions", "Bearer ", body), 401, "authentication_error");
        assertOpenAiError(send("POST", "/chat/completions", "Bearer", body), 401, "authentication_error");
        assertOpenAiError(send("GET", "/models", null, null), 401, "authentication_error");
        assertThat(anthropic.received()).isEmpty();
    }

    @Test
    void v1PrefixedPathsAreAlsoServed() throws Exception {
        anthropic.respond(200, OK_MESSAGE);

        assertThat(send("POST", "/v1/chat/completions", "Bearer k", chatBody("\"hi\"")).statusCode()).isEqualTo(200);
    }

    @Test
    void malformedRequestsAre400() throws Exception {
        assertOpenAiError(chat("{not json"), 400, "invalid_request_error");
        assertOpenAiError(chat("[]"), 400, "invalid_request_error");
        assertOpenAiError(chat("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"), 400,
                "invalid_request_error");
        assertOpenAiError(chat("{\"model\":\"m\"}"), 400, "invalid_request_error");
        assertOpenAiError(chat("{\"model\":\"m\",\"messages\":[]}"), 400, "invalid_request_error");
        assertOpenAiError(chat("{\"model\":\"m\",\"messages\":[{\"role\":\"user\"}]}"), 400,
                "invalid_request_error");
        assertOpenAiError(chat(chatBody("42")), 400, "invalid_request_error");
        assertOpenAiError(chat(chatBody("[{\"type\":\"audio\"}]")), 400, "invalid_request_error");
        assertOpenAiError(chat(chatBody("[{\"type\":\"text\"}]")), 400, "invalid_request_error");
        assertOpenAiError(chat(chatBody("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x/y.png\"}}]")),
                400, "invalid_request_error");
        assertOpenAiError(chat(chatBody("[{\"type\":\"image_url\"}]")), 400, "invalid_request_error");
        assertOpenAiError(chat(chatBody("[\"plain\"]")), 400, "invalid_request_error");
        assertOpenAiError(chat(chatBody("[]")), 400, "invalid_request_error");
        assertThat(anthropic.received()).isEmpty();
    }

    @Test
    void unknownPathIs404AndWrongMethodIs405() throws Exception {
        assertOpenAiError(send("GET", "/nope", "Bearer k", null), 404, "invalid_request_error");
        assertOpenAiError(send("GET", "/chat/completions", "Bearer k", null), 405, "invalid_request_error");
        assertOpenAiError(send("POST", "/models", "Bearer k", "{}"), 405, "invalid_request_error");
    }

    // ---- errors ----

    @Test
    void anthropicErrorIsReturnedAsOpenAiErrorWithSameStatusAndIsNotRetried() throws Exception {
        anthropic.respond(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                + "\"message\":\"Number of request tokens has exceeded your rate limit\"}}");

        HttpResponse<String> response = chat(chatBody("\"hi\""));

        assertOpenAiError(response, 429, "rate_limit_error");
        assertThat(json(response.body()).at("/error/message").asText())
                .contains("Number of request tokens has exceeded your rate limit");
        assertThat(anthropic.received()).hasSize(1);
    }

    @Test
    void anthropicErrorWithoutJsonBodyKeepsStatusAndBodyText() throws Exception {
        anthropic.respond(500, "upstream exploded");

        HttpResponse<String> response = chat(chatBody("\"hi\""));

        assertOpenAiError(response, 500, "api_error");
        assertThat(json(response.body()).at("/error/message").asText()).contains("upstream exploded");
        assertThat(anthropic.received()).hasSize(1);
    }

    @Test
    void anthropicErrorWithJsonButNoErrorObjectUsesStatusText() throws Exception {
        anthropic.respond(401, "{\"unexpected\":true}");

        assertOpenAiError(chat(chatBody("\"hi\"")), 401, "api_error");
    }

    @Test
    void anthropicErrorWithEmptyBodyStillHasAMessage() throws Exception {
        anthropic.respond(503, "");

        HttpResponse<String> response = chat(chatBody("\"hi\""));

        assertOpenAiError(response, 503, "api_error");
    }

    @Test
    void successWithoutTextBlockIs502() throws Exception {
        anthropic.respond(200, "{\"content\":[]}");
        assertOpenAiError(chat(chatBody("\"hi\"")), 502, "api_error");

        anthropic.respond(200, "{\"content\":[{\"type\":\"tool_use\"}]}");
        assertOpenAiError(chat(chatBody("\"hi\"")), 502, "api_error");

        anthropic.respond(200, "not json");
        assertOpenAiError(chat(chatBody("\"hi\"")), 502, "api_error");
    }

    @Test
    void unreachableAnthropicIs502() throws Exception {
        String deadUrl = anthropic.baseUrl();
        anthropic.close();
        try (ClaudeAdapter lonely = startAdapter(deadUrl, Duration.ofSeconds(2))) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + lonely.port() + "/chat/completions"))
                    .header("Authorization", "Bearer k")
                    .POST(HttpRequest.BodyPublishers.ofString(chatBody("\"hi\"")))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertOpenAiError(response, 502, "api_error");
        }
    }

    @Test
    void bearerWithIllegalCharacterGivesOpenAiErrorBodyWithoutLeakingTheKey() throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", adapter.port())) {
            String body = chatBody("\"hi\"");
            socket.getOutputStream().write(("POST /chat/completions HTTP/1.1\r\nHost: x\r\n"
                    + "Authorization: Bearer secret\u0001key\r\nContent-Type: application/json\r\n"
                    + "Connection: close\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String raw = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            assertThat(raw).startsWith("HTTP/1.1 500");
            assertThat(raw).contains("\"error\"").contains("\"type\":\"api_error\"").doesNotContain("secret");
        }
    }

    @Test
    void invalidAnthropicBaseUrlGivesOpenAiErrorBody() throws Exception {
        try (ClaudeAdapter broken = startAdapter("http://bad host", Duration.ofSeconds(2))) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + broken.port() + "/chat/completions"))
                    .header("Authorization", "Bearer k")
                    .POST(HttpRequest.BodyPublishers.ofString(chatBody("\"hi\"")))
                    .build();

            assertOpenAiError(http.send(request, HttpResponse.BodyHandlers.ofString()), 500, "api_error");
        }
    }

    @Test
    void slowAnthropicTimesOutAs502WithoutRetry() throws Exception {
        anthropic.respond(200, OK_MESSAGE);
        anthropic.delay(1500);
        try (ClaudeAdapter impatient = startAdapter(anthropic.baseUrl(), Duration.ofMillis(300))) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + impatient.port() + "/chat/completions"))
                    .header("Authorization", "Bearer k")
                    .POST(HttpRequest.BodyPublishers.ofString(chatBody("\"hi\"")))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertOpenAiError(response, 502, "api_error");
            assertThat(anthropic.received()).hasSize(1);
        }
    }

    // ---- GET /models ----

    @Test
    void modelsAreListedWithImageInputCapability() throws Exception {
        anthropic.respond(200, """
                {"data":[
                  {"type":"model","id":"claude-sonnet-4-5","display_name":"Sonnet 4.5"},
                  {"type":"model","id":"claude-2.1"},
                  {"type":"model","id":"claude-instant-1.2"},
                  {"type":"model","id":""},
                  {"type":"model"}
                ],"has_more":false}""");

        HttpResponse<String> response = send("GET", "/models", "Bearer sk-ant-test", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = json(response.body()).get("data");
        assertThat(data).hasSize(3);
        assertThat(data.get(0).get("id").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(data.get(0).at("/capabilities/image_input").asBoolean()).isTrue();
        assertThat(data.get(1).get("id").asText()).isEqualTo("claude-2.1");
        assertThat(data.get(1).at("/capabilities/image_input").asBoolean()).isFalse();
        assertThat(data.get(2).at("/capabilities/image_input").asBoolean()).isFalse();
        AnthropicStub.Received sent = anthropic.received().get(0);
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.pathAndQuery()).isEqualTo("/v1/models?limit=1000");
        assertThat(sent.apiKey()).isEqualTo("sk-ant-test");
        assertThat(sent.version()).isEqualTo("2023-06-01");
    }

    @Test
    void modelsErrorsAreOpenAiErrors() throws Exception {
        anthropic.respond(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                + "\"message\":\"invalid x-api-key\"}}");
        HttpResponse<String> unauthorized = send("GET", "/models", "Bearer bad", null);
        assertOpenAiError(unauthorized, 401, "authentication_error");
        assertThat(json(unauthorized.body()).at("/error/message").asText()).contains("invalid x-api-key");

        anthropic.respond(200, "{\"nodata\":1}");
        assertOpenAiError(send("GET", "/models", "Bearer k", null), 502, "api_error");

        anthropic.respond(200, "garbage");
        assertOpenAiError(send("GET", "/models", "Bearer k", null), 502, "api_error");
    }
}
