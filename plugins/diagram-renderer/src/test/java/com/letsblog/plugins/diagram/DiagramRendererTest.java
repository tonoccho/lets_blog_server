package com.letsblog.plugins.diagram;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiagramRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE = "@startuml\nAlice -> Bob: hi\n@enduml";

    private PlantUmlStub plantUml;
    private DiagramRenderer renderer;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        plantUml = new PlantUmlStub();
        renderer = start(plantUml.baseUrl(), Duration.ofSeconds(5), 1024);
    }

    @AfterEach
    void tearDown() {
        renderer.close();
        plantUml.close();
    }

    private static DiagramRenderer start(String plantUmlBaseUrl, Duration timeout, long maxBody) throws IOException {
        return DiagramRenderer.start(new DiagramRenderer.Config(0, plantUmlBaseUrl, timeout, maxBody));
    }

    private HttpResponse<byte[]> post(DiagramRenderer target, String path, String body, String authorization)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + target.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> postSource(String source) throws Exception {
        return post(renderer, "/render/plantuml", MAPPER.writeValueAsString(Map.of("source", source)), null);
    }

    private static JsonNode json(HttpResponse<byte[]> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    // ---- AC1: 正しい記法 → 200 image/png ----

    @Test
    void validSourceReturnsPngTranslatedToGetPng() throws Exception {
        HttpResponse<byte[]> response = postSource(SOURCE);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(response.body()).isEqualTo(PlantUmlStub.PNG);
        assertThat(plantUml.received()).hasSize(1);
        PlantUmlStub.Received received = plantUml.received().get(0);
        assertThat(received.method()).isEqualTo("GET");
        assertThat(received.path()).isEqualTo("/png/" + PlantUmlEncoder.encode(SOURCE));
        assertThat(received.authorization()).isNull();
    }

    @Test
    void authorizationHeaderIsForwardedToPlantUmlServer() throws Exception {
        HttpResponse<byte[]> response = post(renderer, "/render/plantuml",
                MAPPER.writeValueAsString(Map.of("source", SOURCE)), "Bearer tool-token");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(plantUml.received().get(0).authorization()).isEqualTo("Bearer tool-token");
    }

    // ---- AC2: 422 / 400 / 413 ----

    @Test
    void syntaxErrorReturns422WithMessageAndLine() throws Exception {
        plantUml.respond(400, PlantUmlStub.PNG, Map.of(
                "X-PlantUML-Diagram-Error", "Syntax Error?\t bad",
                "X-PlantUML-Diagram-Error-Line", " 3 "));

        HttpResponse<byte[]> response = postSource("@startuml\nbroken\n@enduml");

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = json(response).get("error");
        assertThat(error.get("type").asText()).isEqualTo("syntax_error");
        assertThat(error.get("message").asText()).isEqualTo("Syntax Error?  bad");
        assertThat(error.get("line").asInt()).isEqualTo(3);
    }

    @Test
    void syntaxErrorWithoutLineOmitsLine() throws Exception {
        plantUml.respond(400, PlantUmlStub.PNG, Map.of("X-PlantUML-Diagram-Error", "oops"));

        JsonNode error = json(postSource(SOURCE)).get("error");

        assertThat(error.get("type").asText()).isEqualTo("syntax_error");
        assertThat(error.has("line")).isFalse();
    }

    @Test
    void syntaxErrorWithNonNumericLineOmitsLine() throws Exception {
        plantUml.respond(400, PlantUmlStub.PNG, Map.of(
                "X-PlantUML-Diagram-Error", "oops", "X-PlantUML-Diagram-Error-Line", "abc"));

        JsonNode error = json(postSource(SOURCE)).get("error");

        assertThat(error.get("type").asText()).isEqualTo("syntax_error");
        assertThat(error.has("line")).isFalse();
    }

    @Test
    void longSyntaxErrorMessageIsTruncated() throws Exception {
        plantUml.respond(400, PlantUmlStub.PNG, Map.of("X-PlantUML-Diagram-Error", "x".repeat(500)));

        String message = json(postSource(SOURCE)).get("error").get("message").asText();

        assertThat(message).hasSize(201).endsWith("…");
    }

    @Test
    void blankErrorHeaderIsNotASyntaxError() throws Exception {
        plantUml.respond(400, PlantUmlStub.PNG, Map.of("X-PlantUML-Diagram-Error", " "));

        HttpResponse<byte[]> response = postSource(SOURCE);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response).get("error").get("type").asText()).isEqualTo("render_failed");
    }

    @Test
    void emptyOrBlankOrMissingSourceReturns400() throws Exception {
        for (String body : new String[] {"{\"source\":\"\"}", "{\"source\":\"  \"}", "{}", "{\"source\":5}"}) {
            HttpResponse<byte[]> response = post(renderer, "/render/plantuml", body, null);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(json(response).get("error").get("type").asText()).isEqualTo("invalid_request");
        }
        assertThat(plantUml.received()).isEmpty();
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        HttpResponse<byte[]> response = post(renderer, "/render/plantuml", "not json", null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).get("error").get("type").asText()).isEqualTo("invalid_request");
    }

    @Test
    void declaredOversizedBodyReturns413WithoutCallingPlantUml() throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("source", "a".repeat(2000)));

        HttpResponse<byte[]> response = post(renderer, "/render/plantuml", body, null);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(json(response).get("error").get("type").asText()).isEqualTo("payload_too_large");
        assertThat(plantUml.received()).isEmpty();
    }

    @Test
    void chunkedOversizedBodyReturns413() throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(Map.of("source", "a".repeat(2000)));
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + renderer.port() + "/render/plantuml"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(plantUml.received()).isEmpty();
    }

    @Test
    void bodyAtTheLimitIsAccepted() throws Exception {
        String prefix = "{\"source\":\"";
        String suffix = "\"}";
        String body = prefix + "a".repeat(1024 - prefix.length() - suffix.length()) + suffix;
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(1024);

        assertThat(post(renderer, "/render/plantuml", body, null).statusCode()).isEqualTo(200);
    }

    // ---- AC3: 届かない・5xx → render_failed ----

    @Test
    void plantUmlServerErrorReturns502RenderFailed() throws Exception {
        plantUml.respond(503, new byte[0], Map.of());

        HttpResponse<byte[]> response = postSource(SOURCE);

        assertThat(response.statusCode()).isEqualTo(502);
        JsonNode error = json(response).get("error");
        assertThat(error.get("type").asText()).isEqualTo("render_failed");
        assertThat(error.get("message").asText()).contains("503");
    }

    @Test
    void unreachablePlantUmlServerReturns502RenderFailed() throws Exception {
        String deadUrl;
        try (PlantUmlStub dead = new PlantUmlStub()) {
            deadUrl = dead.baseUrl();
        }
        try (DiagramRenderer other = start(deadUrl, Duration.ofSeconds(2), 1024)) {
            HttpResponse<byte[]> response = post(other, "/render/plantuml",
                    MAPPER.writeValueAsString(Map.of("source", SOURCE)), null);

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(json(response).get("error").get("type").asText()).isEqualTo("render_failed");
        }
    }

    @Test
    void slowPlantUmlServerTimesOutAsRenderFailed() throws Exception {
        plantUml.delay(1500);
        try (DiagramRenderer other = start(plantUml.baseUrl(), Duration.ofMillis(200), 1024)) {
            HttpResponse<byte[]> response = post(other, "/render/plantuml",
                    MAPPER.writeValueAsString(Map.of("source", SOURCE)), null);

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(json(response).get("error").get("type").asText()).isEqualTo("render_failed");
        }
    }

    // ---- AC4: capabilities ----

    @Test
    void capabilitiesDeclaresPlantUml() throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + renderer.port() + "/capabilities")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(MAPPER.readTree(response.body()))
                .isEqualTo(MAPPER.readTree("{\"notations\":[\"plantuml\"]}"));
    }

    // ---- ルーティング ----

    @Test
    void wrongMethodReturns405AndUnknownPathReturns404() throws Exception {
        HttpResponse<byte[]> get = http.send(HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + renderer.port() + "/render/plantuml")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> postCaps = post(renderer, "/capabilities", "{}", null);
        HttpResponse<byte[]> unknown = post(renderer, "/render/unknown", "{}", null);

        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(postCaps.statusCode()).isEqualTo(405);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json(unknown).get("error").get("type").asText()).isEqualTo("not_found");
    }
}
