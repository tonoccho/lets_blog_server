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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** docker composeが全コンテナへ付けるプロジェクト名ラベル。一覧APIのLabelsに含まれる。 */
    private static final String COMPOSE_PROJECT_LABEL = "com.docker.compose.project";

    /** 演算デバイスの代替構成で停止しているコンテナに付ける状態値(issue #1584)。 */
    static final String STANDBY_STATE = "standby";
    /**
     * 代替構成の組(issue #1584)。片方が稼働している間、もう片方は意図的に停止している。
     * 組を足すときは {@link #ALTERNATIVE_PAIRS} に1行足す(例: {@code {"ollama", "ollama-cpu"}})。
     */
    private static final String[][] ALTERNATIVE_PAIRS = {{"comfyui", "comfyui-cpu"}};
    private static final Map<String, String> ALTERNATIVE_OF = alternativeOf();

    private static Map<String, String> alternativeOf() {
        Map<String, String> map = new java.util.HashMap<>();
        for (String[] pair : ALTERNATIVE_PAIRS) {
            map.put(pair[0], pair[1]);
            map.put(pair[1], pair[0]);
        }
        return Map.copyOf(map);
    }

    private record RawContainer(String id, String name, String state, String detail) {}

    private final RestClient dockerClient;
    /**
     * 自分が属するcomposeプロジェクト名(issue #803)。空文字なら判定に使わない。
     *
     * <p>docker-socket-proxyはホストのDockerデーモン全体を見ているため、
     * {@code GET /containers/json} には他プロジェクトのコンテナも含まれる。名前が{@code lbs-}で
     * 始まるかだけで絞ると、同一ホスト上の無関係なコンテナが紛れ込む。実際に開発環境の
     * テストハーネスが起動する{@code lbs-test-db}(別プロジェクト)が載ることが確認されている。
     *
     * <p>しかもそれは{@code restart: no}で常駐するMySQLのため、#725で入れた
     * 「終了コード0かつ再起動ポリシーnoならワンショットジョブの正常完了」の判定に合致し、
     * 停止すると「正常」と表示されてしまう(誤りだと気付く手がかりが無い)。
     */
    private final String composeProject;

    @Autowired
    public ContainerStatusService(
            @Value("${app.docker-socket-proxy-base-url}") String dockerSocketProxyBaseUrl,
            @Value("${app.compose-project-name:}") String composeProject) {
        this(builderWithTimeout(dockerSocketProxyBaseUrl), composeProject);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    ContainerStatusService(RestClient.Builder builder, String composeProject) {
        preferJackson2(builder);
        this.dockerClient = builder.build();
        this.composeProject = composeProject == null ? "" : composeProject.trim();
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
            List<RawContainer> raws = new ArrayList<>();
            for (JsonNode item : response) {
                String rawName = firstName(item.path("Names"));
                if (rawName == null || !rawName.startsWith(CONTAINER_NAME_PREFIX)) {
                    continue;
                }
                if (!belongsToThisProject(item)) {
                    continue;
                }
                raws.add(new RawContainer(
                        item.path("Id").asText(""),
                        rawName.substring(CONTAINER_NAME_PREFIX.length()),
                        item.path("State").asText(""),
                        item.path("Status").asText("")));
            }
            Set<String> running = new HashSet<>();
            for (RawContainer raw : raws) {
                if ("running".equals(raw.state())) {
                    running.add(raw.name());
                }
            }
            List<ContainerStatusResponse> containers = new ArrayList<>();
            for (RawContainer raw : raws) {
                if (isStandbyAlternative(raw, running)) {
                    containers.add(new ContainerStatusResponse(
                            raw.name(), raw.name(), Status.NORMAL, STANDBY_STATE, raw.detail()));
                    continue;
                }
                Status status = resolveStatus(raw.id(), raw.state(), raw.detail());
                containers.add(new ContainerStatusResponse(raw.name(), raw.name(), status, raw.state(), raw.detail()));
            }
            containers.sort(Comparator.comparing(ContainerStatusResponse::name));
            return containers;
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("コンテナ状態の取得に失敗しました(docker-socket-proxy未設定/未到達の可能性があります): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 代替構成の組の相方が稼働中で、自分は停止({@code created}/{@code exited})している場合に真(issue #1584)。
     * 相方が稼働していなければ偽(従来どおりの判定へ進む)。
     */
    private static boolean isStandbyAlternative(RawContainer raw, Set<String> running) {
        String partner = ALTERNATIVE_OF.get(raw.name());
        if (partner == null || !running.contains(partner)) {
            return false;
        }
        return "created".equals(raw.state()) || "exited".equals(raw.state());
    }

    /**
     * このアプリを構成するコンテナかを、composeのプロジェクトラベルで判定する(issue #803)。
     *
     * <p>プロジェクト名が解決できない場合({@code app.compose-project-name}が未設定)は、
     * 従来どおり名前の前方一致だけで通す。ラベルで絞れないことを理由に一覧を空にすると、
     * 設定漏れのある環境でダッシュボードが「コンテナが1つも無い」という誤った表示になり、
     * 本物の障害と区別が付かなくなるため。
     */
    private boolean belongsToThisProject(JsonNode item) {
        if (composeProject.isEmpty()) {
            return true;
        }
        return composeProject.equals(item.path("Labels").path(COMPOSE_PROJECT_LABEL).asText(""));
    }

    private String firstName(JsonNode namesNode) {
        if (!namesNode.isArray() || namesNode.isEmpty()) {
            return null;
        }
        String raw = namesNode.get(0).asText("");
        return raw.startsWith("/") ? raw.substring(1) : raw;
    }

    /**
     * runningでもヘルスチェック異常(Docker HEALTHCHECK設定時にStatus文字列へ付与される)は警告にする。
     *
     * <p>running以外は原則エラー扱いだが、{@code exited}だけは例外を設ける(issue #725)。
     * {@code legacy-schema-migrate}のようなワンショットジョブは正常に完了しても{@code exited}に
     * なるため、一律エラーにするとダッシュボードが常時赤くなり、本当の異常が埋もれる。
     * 判定は{@link #isCompletedOneShotJob}に委ねる。
     */
    private Status resolveStatus(String id, String state, String detail) {
        if ("running".equals(state)) {
            if (detail.contains("(unhealthy)") || detail.contains("(health: starting)")) {
                return Status.WARNING;
            }
            return Status.NORMAL;
        }
        if ("exited".equals(state) && isCompletedOneShotJob(id)) {
            return Status.NORMAL;
        }
        return Status.ERROR;
    }

    /**
     * 停止中のコンテナが「正常に完了したワンショットジョブ」かどうかを判定する(issue #725)。
     *
     * <p>終了コード0だけでは足りない。継続稼働が期待されるサービスを{@code docker compose stop}で
     * 正常停止した場合も終了コードは0になり、それをNORMALと表示すると停止に気付けなくなるためである。
     * 再起動ポリシーを併せて見て、<b>終了コードが0であり、かつ再起動ポリシーが{@code no}</b>の場合だけ
     * 「完了した」と扱う。docker-compose.ymlでは共通アンカー{@code x-common-service}が
     * {@code restart: unless-stopped}を与えており、ワンショットジョブだけが{@code restart: "no"}で
     * 上書きしている(legacy-schema-migrate)。この違いがそのまま判定材料になる。
     *
     * <p>一覧APIの{@code GET /containers/json}は{@code HostConfig}として{@code NetworkMode}しか
     * 返さず、終了コードもStatus文字列("Exited (0) 2 hours ago")に埋まっているだけなので、
     * 停止中のコンテナに限って{@code GET /containers/{id}/json}を追加で呼ぶ。通常このAPIを叩く
     * 時点で停止中のコンテナは多くても数個なので、往復の増加は限定的である。
     *
     * <p>取得に失敗した場合はfalseを返す(=エラー表示のまま)。判定できないことを理由に
     * 異常を隠さない方が安全なため。
     */
    private boolean isCompletedOneShotJob(String id) {
        if (id.isEmpty()) {
            return false;
        }
        try {
            JsonNode inspect = dockerClient.get()
                    .uri("/containers/{id}/json", id)
                    .retrieve()
                    .body(JsonNode.class);
            if (inspect == null) {
                return false;
            }
            boolean exitedSuccessfully = inspect.path("State").path("ExitCode").asInt(-1) == 0;
            String restartPolicy = inspect.path("HostConfig").path("RestartPolicy").path("Name").asText("");
            // 再起動ポリシーが読めなかった場合(空文字)は完了扱いにしない。ExitCodeのasInt(-1)と
            // 同じく「判定できなければ異常のまま」に倒す。現行のDockerは未指定でも"no"を返すため、
            // 空文字を許容する必要は無い。
            return exitedSuccessfully && "no".equals(restartPolicy);
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("コンテナ {} の詳細取得に失敗しました。停止中として扱います: {}", id, e.getMessage());
            return false;
        }
    }
}
