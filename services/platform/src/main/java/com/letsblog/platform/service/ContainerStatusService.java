package com.letsblog.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.dto.ContainerStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * legacy-apiから移設(issue #695、C10-3、元は issue #280)。ダッシュボードに表示する、このアプリ自体を
 * 構成するDockerコンテナ(container_name: lbs-*)の稼働状況を取得する。platformコンテナ自身は
 * Dockerデーモンへ直接アクセスできない(docker.sockは意図的に未マウント)ため、読み取り専用の
 * tecnativa/docker-socket-proxyを経由してDocker Engine API(GET /containers/json)を呼び出す。
 *
 * <p>docker-socket-proxyへのアクセスは本サービス(platform-service)のみに限定する(Epic #551の方針。
 * 移設前はlegacy-apiがこの特権を保持していた)。
 */
@Service
public class ContainerStatusService {

    private static final Logger log = LoggerFactory.getLogger(ContainerStatusService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String CONTAINER_NAME_PREFIX = "lbs-";

    private final RestClient dockerClient;

    @Autowired
    public ContainerStatusService(@Value("${app.docker-socket-proxy-base-url}") String dockerSocketProxyBaseUrl) {
        this(builderWithTimeout(dockerSocketProxyBaseUrl));
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ContainerStatusService(RestClient.Builder builder) {
        preferJackson2(builder);
        this.dockerClient = builder.build();
    }

    private static RestClient.Builder builderWithTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
    }

    /**
     * Boot 4ではRestClientの既定JSONコンバータがJackson3(tools.jackson)になったが、本クラスは
     * com.fasterxml.jackson.databind.JsonNode(Jackson2)でレスポンスを組み立てている
     * (KeycloakAdminClientと同じ理由)。
     */
    private static void preferJackson2(RestClient.Builder builder) {
        builder.messageConverters(converters -> {
            converters.removeIf(AbstractJacksonHttpMessageConverter.class::isInstance);
            converters.add(0, new MappingJackson2HttpMessageConverter());
        });
    }

    /**
     * このアプリ構成コンテナ(コンテナ名が"lbs-"で始まるもの)の一覧を、名前順で返す。
     * docker-socket-proxyが未設定/到達不能な環境(ローカルでのgradlew bootRun単体実行など)では
     * 空リストを返す(例外は投げない。コンテナ状態はダッシュボード表示への補助情報のため)。
     */
    public List<ContainerStatusResponse> listAll() {
        try {
            JsonNode response = dockerClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/containers/json").queryParam("all", "true").build())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return List.of();
            }
            List<ContainerStatusResponse> containers = new ArrayList<>();
            for (JsonNode item : response) {
                String rawName = firstName(item.path("Names"));
                if (rawName == null || !rawName.startsWith(CONTAINER_NAME_PREFIX)) {
                    continue;
                }
                String name = rawName.substring(CONTAINER_NAME_PREFIX.length());
                String state = item.path("State").asText("");
                String detail = item.path("Status").asText("");
                containers.add(new ContainerStatusResponse(name, name, resolveStatus(state, detail), state, detail));
            }
            containers.sort(Comparator.comparing(ContainerStatusResponse::name));
            return containers;
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("コンテナ状態の取得に失敗しました(docker-socket-proxy未設定/未到達の可能性があります): {}", e.getMessage());
            return List.of();
        }
    }

    private String firstName(JsonNode namesNode) {
        if (!namesNode.isArray() || namesNode.isEmpty()) {
            return null;
        }
        String raw = namesNode.get(0).asText("");
        return raw.startsWith("/") ? raw.substring(1) : raw;
    }

    /**
     * running以外は全てエラー扱いにする(停止・再起動中・作成直後など、いずれも「使えない状態」のため)。
     * runningでもヘルスチェック異常(Docker HEALTHCHECK設定時にStatus文字列へ付与される)は警告にする。
     */
    private Status resolveStatus(String state, String detail) {
        if (!"running".equals(state)) {
            return Status.ERROR;
        }
        if (detail.contains("(unhealthy)") || detail.contains("(health: starting)")) {
            return Status.WARNING;
        }
        return Status.NORMAL;
    }
}
