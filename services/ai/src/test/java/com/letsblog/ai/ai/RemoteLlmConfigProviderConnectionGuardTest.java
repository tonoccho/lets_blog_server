package com.letsblog.ai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.service.CurrentActorService;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.GuardedTarget;
import com.letsblog.common.net.InterfaceAddr;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * プロジェクトのOllama接続先の上書きは、接続時に解決後のアドレスを検査して使う(issue #1547)。
 * システム設定の既定の接続先は検査しない。名前解決とインタフェース列挙は差し替えて決定論的に検証する。
 */
class RemoteLlmConfigProviderConnectionGuardTest {

    private final PlatformServiceClient client = mock(PlatformServiceClient.class);
    private final CurrentActorService actor = mock(CurrentActorService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final ProjectAiSettingsService projectSettings = mock(ProjectAiSettingsService.class);
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

    private final RemoteLlmConfigProvider provider = new RemoteLlmConfigProvider(
            client, actor, request, projectSettings, mock(CredentialCipher.class), guard);

    private void project(String ollamaOverride) {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("OLLAMA", "Bearer t")).thenReturn(new PlatformServiceClient.LlmConfig(
                "OLLAMA", "http://system-ollama:11434/v1", "", "m", List.of("m"), 10L));
        when(projectSettings.getOllamaBaseUrl(7L)).thenReturn(ollamaOverride);
        provider.useProject(7L);
    }

    @Test
    void dockerRangeIpLiteralOverrideIsRejectedNamingTheOllamaSetting() {
        dns.put("172.18.0.5", "172.18.0.5");
        project("http://172.18.0.5:3306");
        assertThatThrownBy(() -> provider.targetFor(AiProvider.OLLAMA))
                .isInstanceOf(ForbiddenDestinationException.class)
                .hasMessageContaining("Ollama")
                .hasMessageContaining("サーバ内部のネットワーク");
    }

    @Test
    void hostnameResolvingIntoDockerOrDeniedAddressIsRejected() {
        dns.put("evil.example", "172.18.0.9");
        dns.put("meta.example", "169.254.169.254");
        dns.put("loop.example", "127.0.0.1");
        for (String host : List.of("evil.example", "meta.example", "loop.example")) {
            project("http://" + host + ":11434/v1");
            assertThatThrownBy(() -> provider.targetFor(AiProvider.OLLAMA))
                    .isInstanceOf(ForbiddenDestinationException.class);
        }
    }

    @Test
    void lanPrivateAddressOutsideDockerRangeIsStillUsed() {
        dns.put("192.168.1.50", "192.168.1.50");
        dns.put("lan.example", "10.0.0.5");
        project("http://192.168.1.50:11434/v1");
        assertThat(provider.targetFor(AiProvider.OLLAMA).baseUrl()).isEqualTo("http://192.168.1.50:11434/v1");
        project("http://lan.example:11434/v1");
        assertThat(provider.targetFor(AiProvider.OLLAMA).baseUrl()).isEqualTo("http://10.0.0.5:11434/v1");
    }

    @Test
    void bundledOllamaNameIsAllowedByNameButNotByIpLiteral() {
        dns.put("ollama", "172.18.0.20");
        dns.put("172.18.0.20", "172.18.0.20");
        project("http://ollama:11434/v1");
        assertThat(provider.targetFor(AiProvider.OLLAMA).baseUrl()).isEqualTo("http://172.18.0.20:11434/v1");
        project("http://172.18.0.20:11434/v1");
        assertThatThrownBy(() -> provider.targetFor(AiProvider.OLLAMA))
                .isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void systemDefaultIsNeverCheckedAndNeverResolvedByTheGuard() {
        project(null);
        GuardedTarget target = provider.targetFor(AiProvider.OLLAMA);
        assertThat(target.baseUrl()).isEqualTo("http://system-ollama:11434/v1");
        assertThat(target.sniHost()).isNull();
        project("  ");
        assertThat(provider.targetFor(AiProvider.OLLAMA).baseUrl()).isEqualTo("http://system-ollama:11434/v1");
        assertThat(resolved).isEmpty();
    }

    @Test
    void otherProvidersAreNotChecked() {
        project("http://172.18.0.5:3306");
        when(client.resolveLlmConfig("OPENAI", "Bearer t")).thenReturn(new PlatformServiceClient.LlmConfig(
                "OPENAI", "https://api.openai.com/v1", "k", "m", List.of("m"), 10L));
        assertThat(provider.targetFor(AiProvider.OPENAI).baseUrl()).isEqualTo("https://api.openai.com/v1");
    }
}
