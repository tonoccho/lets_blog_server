package com.letsblog.platform.service;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * docker-socket-proxy経由のDocker Engine API呼び出し(issue #1399)。
 *
 * <p>向き先は {@code app.compute-device.docker-base-url}(既定は {@code DOCKER_SOCKET_PROXY_BASE_URL}、
 * ダッシュボードの {@link ContainerStatusService} と同じ)。受け入れ環境ではこれだけをスタブへ向け、
 * ダッシュボードのコンテナ一覧には影響させない。
 */
@Component
public class RestDockerEngineClient implements DockerEngineClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** {@code stop} はコンテナの終了待ち(既定の猶予10秒)を含むため、一覧より長く取る。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String COMPOSE_PROJECT_LABEL = "com.docker.compose.project";

    private final RestClient client;
    private final String composeProject;

    @Autowired
    public RestDockerEngineClient(
            @Value("${app.compute-device.docker-base-url}") String baseUrl,
            @Value("${app.compose-project-name:}") String composeProject) {
        this(builderWithTimeout(baseUrl), composeProject);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取る。 */
    RestDockerEngineClient(RestClient.Builder builder, String composeProject) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
        this.client = builder.build();
        this.composeProject = composeProject.trim();
    }

    private static RestClient.Builder builderWithTimeout(String baseUrl) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("docker-engine")).baseUrl(baseUrl).requestFactory(factory);
    }

    @Override
    public List<ContainerRef> listContainers() {
        JsonNode response;
        try {
            response = client.get()
                    .uri(uri -> uri.path("/containers/json").queryParam("all", "true").build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new DockerEngineException("コンテナ一覧を取得できませんでした: " + e.getMessage(), e);
        }
        List<ContainerRef> refs = new ArrayList<>();
        if (response == null) {
            return refs;
        }
        for (JsonNode item : response) {
            JsonNode names = item.path("Names");
            if (!names.isArray() || names.isEmpty() || !belongsToThisProject(item)) {
                continue;
            }
            String name = names.get(0).asText("");
            refs.add(new ContainerRef(
                    item.path("Id").asText(""),
                    name.startsWith("/") ? name.substring(1) : name,
                    item.path("State").asText("")));
        }
        return refs;
    }

    @Override
    public ContainerInspection inspectContainer(String id) {
        JsonNode response;
        try {
            response = client.get().uri("/containers/{id}/json", id).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new DockerEngineException("コンテナの詳細を取得できませんでした: " + e.getMessage(), e);
        }
        if (response == null) {
            return new ContainerInspection("", "");
        }
        return new ContainerInspection(
                response.path("HostConfig").path("Runtime").asText(""),
                response.path("State").path("Health").path("Status").asText(""));
    }

    @Override
    public void startContainer(String id) {
        post(id, "start");
    }

    @Override
    public void stopContainer(String id) {
        post(id, "stop");
    }

    /** 204(成功)と304(既にその状態)を成功とする。proxyが拒否すると403になる。 */
    private void post(String id, String action) {
        try {
            client.post().uri("/containers/{id}/{action}", id, action).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new DockerEngineException(
                    action + " は HTTP " + e.getStatusCode().value() + " で拒否されました", e);
        } catch (RestClientException e) {
            throw new DockerEngineException(action + " に失敗しました: " + e.getMessage(), e);
        }
    }

    private boolean belongsToThisProject(JsonNode item) {
        return composeProject.isEmpty()
                || composeProject.equals(item.path("Labels").path(COMPOSE_PROJECT_LABEL).asText(""));
    }
}
