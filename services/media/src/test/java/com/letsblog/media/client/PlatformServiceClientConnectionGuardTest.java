package com.letsblog.media.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.ServiceTokenFixtures;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.InterfaceAddr;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * プロジェクトのComfyUI接続先の上書きは、接続時に解決後のアドレスを検査して使う(issue #1547)。
 * システム設定の既定の接続先は検査しない。名前解決とインタフェース列挙は差し替えて決定論的に検証する。
 */
class PlatformServiceClientConnectionGuardTest {

    /** ai-service(接続先の上書きを返す)とトークンエンドポイントを、サーバー側で模す。 */
    private final FakeHttpServer http = new FakeHttpServer();
    private final Map<String, String> dns = new HashMap<>();
    private final List<String> resolved = new ArrayList<>();

    private final ConnectionDestinationGuard guard = new ConnectionDestinationGuard(host -> {
        resolved.add(host);
        String address = dns.get(host);
        if (address == null) {
            throw new UnknownHostException(host);
        }
        return new InetAddress[] {InetAddress.getByName(address)};
    }, () -> {
        try {
            return List.of(new InterfaceAddr(InetAddress.getByName("172.18.0.7").getAddress(), 16));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }, () -> true);

    private PlatformServiceClient client;

    PlatformServiceClientConnectionGuardTest() {
        http.on("POST", ServiceTokenFixtures.TOKEN_PATH, FakeHttpServer.Response.ok(
                "{\"access_token\":\"" + ServiceTokenFixtures.TOKEN + "\",\"expires_in\":3600}"));
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    /**
     * ai-serviceがプロジェクト7・8のComfyUI接続先の上書きとして{@code override}(nullなら上書き無し)を返す状態で、
     * クライアントを作る。上書きは5秒キャッシュされるので、上書きを変えるたびに作り直す。
     * システム設定の取得先は到達不能(使うテストは上書きありのものだけ)。
     */
    private PlatformServiceClient clientWithOverride(String override) {
        String body = override == null
                ? "{\"comfyuiBaseUrl\":null}"
                : "{\"comfyuiBaseUrl\":\"" + override + "\"}";
        http.json("GET", "/api/internal/ai/projects/\\d+/connections", body);
        AiServiceConnectionClient aiConnections = new AiServiceConnectionClient(
                RestClient.builder(), http.baseUrl(), ServiceTokenFixtures.client(http.baseUrl()));
        return new PlatformServiceClient(
                RestClient.builder(), "http://127.0.0.1:1", ServiceTokenFixtures.unreachable(), aiConnections, guard);
    }

    @Test
    void dockerRangeIpLiteralOverrideIsRejectedNamingTheComfyUiSetting() {
        dns.put("172.18.0.5", "172.18.0.5");
        client = clientWithOverride("http://172.18.0.5:8188");
        assertThatThrownBy(() -> client.comfyUiTarget(7L))
                .isInstanceOf(ForbiddenDestinationException.class)
                .hasMessageContaining("ComfyUI")
                .hasMessageContaining("サーバ内部のネットワーク");
    }

    @Test
    void hostnameResolvingIntoDockerOrDeniedAddressIsRejected() {
        dns.put("evil.example", "172.18.0.9");
        dns.put("meta.example", "169.254.169.254");
        dns.put("loop.example", "127.0.0.1");
        for (String host : List.of("evil.example", "meta.example", "loop.example")) {
            client = clientWithOverride("http://" + host + ":8188");
            assertThatThrownBy(() -> client.comfyUiTarget(7L)).isInstanceOf(ForbiddenDestinationException.class);
        }
    }

    @Test
    void lanPrivateAddressOutsideDockerRangeIsStillUsed() {
        dns.put("10.0.0.5", "10.0.0.5");
        dns.put("lan.example", "192.168.1.60");
        client = clientWithOverride("http://10.0.0.5:8188");
        assertThat(client.comfyUiTarget(7L).baseUrl()).isEqualTo("http://10.0.0.5:8188");
        client = clientWithOverride("http://lan.example:8188");
        assertThat(client.comfyUiTarget(7L).baseUrl()).isEqualTo("http://192.168.1.60:8188");
    }

    /**
     * issue #1746: ComfyUIは同梱しないので、comfyuiという名前はdockerネットワーク内へ解決されても
     * もう例外として許可しない(GPU PCのLANアドレスは許可する)。
     */
    @Test
    void comfyUiNameResolvingIntoTheDockerRangeIsNoLongerAnException() {
        dns.put("comfyui", "172.18.0.20");
        client = clientWithOverride("http://comfyui:8188");
        assertThatThrownBy(() -> client.comfyUiTarget(7L)).isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void gpuPcLanUrlIsUsedForTheProjectOverride() {
        dns.put("192.168.1.71", "192.168.1.71");
        client = clientWithOverride("http://192.168.1.71:8188");
        assertThat(client.comfyUiTarget(7L).baseUrl()).isEqualTo("http://192.168.1.71:8188");
    }

    @Test
    void withoutOverrideOrProjectTheGuardIsNotUsed() {
        // システム設定の値はこのテストでは取得できないため、上書きなし・projectIdなしは設定取得へ進む(=例外)。
        // ここで確認したいのは、ガードが名前解決を一切しないこと。
        client = clientWithOverride(null);
        assertThatThrownBy(() -> client.comfyUiTarget(8L)).isNotInstanceOf(ForbiddenDestinationException.class);
        assertThatThrownBy(() -> client.comfyUiTarget(null)).isNotInstanceOf(ForbiddenDestinationException.class);
        assertThat(resolved).isEmpty();
    }
}
