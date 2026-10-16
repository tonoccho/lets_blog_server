package com.letsblog.ai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.ai.testsupport.AiWorld;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * プロジェクトのOllama接続先の上書きは、接続時に解決後のアドレスを検査して使う(issue #1547)。
 * システム設定の既定の接続先は検査しない。名前解決とインタフェース列挙は差し替えて決定論的に検証する。
 *
 * <p>issue #1824: PlatformServiceClient・CurrentActorService・ProjectAiSettingsService・CredentialCipherは実物で、
 * platform-serviceはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。名前解決とインタフェース列挙は
 * 外部(DNS・OS)の境界なので、関数として差し替える。
 */
class RemoteLlmConfigProviderConnectionGuardTest {

    private AiWorld world;
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

    private RemoteLlmConfigProvider provider;

    @BeforeEach
    void setUp() {
        world = new AiWorld().admin();
        provider = new RemoteLlmConfigProvider(
                world.platformServiceClient, world.currentActorService, world.request,
                world.projectAiSettingsService, world.credentialCipher, guard);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void project(String ollamaOverride) {
        world.llmConfig(AiProvider.OLLAMA, "http://system-ollama:11434/v1", "", "m", List.of("m"), false);
        world.projectAiSettingsService.getOrCreate(7L).setOllamaBaseUrl(ollamaOverride);
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
    void ollamaNameInsideDockerRangeIsRejectedBecauseThereIsNoBundledOllama_issue1745() {
        dns.put("ollama", "172.18.0.20");
        project("http://ollama:11434/v1");
        assertThatThrownBy(() -> provider.targetFor(AiProvider.OLLAMA))
                .isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void gpuPcLanUrlInProjectSettingIsTheTargetCheckedByTheGuard_issue1745() {
        dns.put("192.168.1.71", "192.168.1.71");
        project("http://192.168.1.71:11434/v1");
        GuardedTarget target = provider.targetFor(AiProvider.OLLAMA);
        assertThat(target.baseUrl()).isEqualTo("http://192.168.1.71:11434/v1");
        assertThat(resolved).containsExactly("192.168.1.71");
    }

    @Test
    void gpuPcLanUrlInSystemSettingIsUsedAsIs_issue1745() {
        world.llmConfig(AiProvider.OLLAMA, "http://192.168.1.71:11434/v1", "", "m", List.of("m"), false);
        provider.useProject(7L);
        assertThat(provider.targetFor(AiProvider.OLLAMA).baseUrl()).isEqualTo("http://192.168.1.71:11434/v1");
        assertThat(resolved).isEmpty();
    }

    @Test
    void unsetOllamaUrlMakesGenerationFailWithAClearMessageAndNoConnection_issue1745() {
        // platform は未設定のとき baseUrl を空で返す(既定値を書き込まない)。
        world.llmConfig(AiProvider.OLLAMA, "", "", "m", List.of("m"), false);
        provider.useProject(7L);

        assertThatThrownBy(() -> new LlmClient(provider).generate("hi", null, AiProvider.OLLAMA))
                .isInstanceOf(AiServiceException.class)
                .hasMessage(LlmClient.OLLAMA_URL_REQUIRED)
                .hasMessageContaining("Ollama")
                .hasMessageContaining("設定されていません");
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
        world.llmConfig(AiProvider.OPENAI, "https://api.openai.com/v1", "k", "m", List.of("m"), false);
        assertThat(provider.targetFor(AiProvider.OPENAI).baseUrl()).isEqualTo("https://api.openai.com/v1");
    }
}
