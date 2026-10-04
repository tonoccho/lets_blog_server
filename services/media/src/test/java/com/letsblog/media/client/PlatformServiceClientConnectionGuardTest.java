package com.letsblog.media.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.InterfaceAddr;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * プロジェクトのComfyUI接続先の上書きは、接続時に解決後のアドレスを検査して使う(issue #1547)。
 * システム設定の既定の接続先は検査しない。名前解決とインタフェース列挙は差し替えて決定論的に検証する。
 */
class PlatformServiceClientConnectionGuardTest {

    private final AiServiceConnectionClient aiConnections = mock(AiServiceConnectionClient.class);
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

    private final PlatformServiceClient client;

    PlatformServiceClientConnectionGuardTest() {
        ServiceTokenClient tokens = mock(ServiceTokenClient.class);
        // システム設定の取得先は到達不能(使うテストは上書きありのものだけ)
        client = new PlatformServiceClient(RestClient.builder(), "http://127.0.0.1:1", tokens, aiConnections, guard);
    }

    @Test
    void dockerRangeIpLiteralOverrideIsRejectedNamingTheComfyUiSetting() {
        dns.put("172.18.0.5", "172.18.0.5");
        when(aiConnections.comfyUiBaseUrlOverride(7L)).thenReturn("http://172.18.0.5:8188");
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
            when(aiConnections.comfyUiBaseUrlOverride(7L)).thenReturn("http://" + host + ":8188");
            assertThatThrownBy(() -> client.comfyUiTarget(7L)).isInstanceOf(ForbiddenDestinationException.class);
        }
    }

    @Test
    void lanPrivateAddressOutsideDockerRangeIsStillUsed() {
        dns.put("10.0.0.5", "10.0.0.5");
        dns.put("lan.example", "192.168.1.60");
        when(aiConnections.comfyUiBaseUrlOverride(7L)).thenReturn("http://10.0.0.5:8188");
        assertThat(client.comfyUiTarget(7L).baseUrl()).isEqualTo("http://10.0.0.5:8188");
        when(aiConnections.comfyUiBaseUrlOverride(7L)).thenReturn("http://lan.example:8188");
        assertThat(client.comfyUiTarget(7L).baseUrl()).isEqualTo("http://192.168.1.60:8188");
    }

    @Test
    void bundledComfyUiNameIsAllowedByNameButNotByIpLiteral() {
        dns.put("comfyui", "172.18.0.20");
        dns.put("172.18.0.20", "172.18.0.20");
        when(aiConnections.comfyUiBaseUrlOverride(7L)).thenReturn("http://comfyui:8188");
        assertThat(client.comfyUiTarget(7L).baseUrl()).isEqualTo("http://172.18.0.20:8188");
        when(aiConnections.comfyUiBaseUrlOverride(7L)).thenReturn("http://172.18.0.20:8188");
        assertThatThrownBy(() -> client.comfyUiTarget(7L)).isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void withoutOverrideOrProjectTheGuardIsNotUsed() {
        // システム設定の値はこのテストでは取得できないため、上書きなし・projectIdなしは設定取得へ進む(=例外)。
        // ここで確認したいのは、ガードが名前解決を一切しないこと。
        when(aiConnections.comfyUiBaseUrlOverride(8L)).thenReturn(null);
        assertThatThrownBy(() -> client.comfyUiTarget(8L)).isNotInstanceOf(ForbiddenDestinationException.class);
        assertThatThrownBy(() -> client.comfyUiTarget(null)).isNotInstanceOf(ForbiddenDestinationException.class);
        assertThat(resolved).isEmpty();
    }
}
