package com.letsblog.project.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.letsblog.project.service.ProvisioningException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WordPressProvisioningClientTimeoutTest {

    private static final Duration CONNECT = Duration.ofSeconds(1);
    private static final Duration READ = Duration.ofSeconds(1);
    // 無期限に待つ実装なら、この上限でテスト自体が失敗する。
    private static final Duration LIMIT = Duration.ofSeconds(8);

    private static final WordPressProvisioningClient.ProvisionCommand COMMAND =
            new WordPressProvisioningClient.ProvisionCommand("s", "db", "t", "u", "u@example.com", "pw", "ja");

    @Test
    void provisionは無応答のエージェントに対して読み取りタイムアウトでProvisioningExceptionになる() throws Exception {
        try (HangingAgentServer server = new HangingAgentServer()) {
            WordPressProvisioningClient client = new WordPressProvisioningClient(server.baseUrl(), "tok", CONNECT, READ);
            assertTimeoutPreemptively(LIMIT, () ->
                    assertThatThrownBy(() -> client.provision(COMMAND)).isInstanceOf(ProvisioningException.class));
        }
    }

    @Test
    void adoptは無応答のエージェントに対してProvisioningExceptionになる() throws Exception {
        try (HangingAgentServer server = new HangingAgentServer()) {
            WordPressProvisioningClient client = new WordPressProvisioningClient(server.baseUrl(), "tok", CONNECT, READ);
            assertTimeoutPreemptively(LIMIT, () -> assertThatThrownBy(
                    () -> client.adopt(new WordPressProvisioningClient.AdoptCommand("s", "u")))
                    .isInstanceOf(ProvisioningException.class));
        }
    }

    @Test
    void deprovisionは無応答のエージェントに対してProvisioningExceptionになる() throws Exception {
        try (HangingAgentServer server = new HangingAgentServer()) {
            WordPressProvisioningClient client = new WordPressProvisioningClient(server.baseUrl(), "tok", CONNECT, READ);
            assertTimeoutPreemptively(LIMIT, () ->
                    assertThatThrownBy(() -> client.deprovision("s", "db")).isInstanceOf(ProvisioningException.class));
        }
    }

    @Test
    void 接続できないエージェントでもProvisioningExceptionになる() {
        WordPressProvisioningClient client = new WordPressProvisioningClient("http://localhost:1", "tok", CONNECT, READ);
        assertThatThrownBy(() -> client.provision(COMMAND)).isInstanceOf(ProvisioningException.class);
    }

    @Test
    void 既定の読み取りタイムアウトは実測最大240秒の構築を切らない300秒以上である() {
        assertThat(WordPressProvisioningClient.DEFAULT_READ_TIMEOUT_SECONDS).isGreaterThanOrEqualTo(300);
    }

    @Test
    void syncは無応答のエージェントに対してProvisioningExceptionになる() throws Exception {
        try (HangingAgentServer server = new HangingAgentServer()) {
            WordPressSyncClient client = new WordPressSyncClient(server.baseUrl(), "tok", CONNECT, READ);
            assertTimeoutPreemptively(LIMIT, () -> assertThatThrownBy(() -> client.sync(
                    new WordPressSyncClient.SyncCommand("a", "adb", "b", "bdb", List.of("themes"))))
                    .isInstanceOf(ProvisioningException.class));
        }
    }

    @Test
    void プラグイン一覧は無応答のエージェントに対して空リストを返す() throws Exception {
        try (HangingAgentServer server = new HangingAgentServer()) {
            WordPressAgentPluginsClient client = new WordPressAgentPluginsClient(server.baseUrl(), "tok", CONNECT, READ);
            assertTimeoutPreemptively(LIMIT, () -> assertThat(client.listActivePluginNames("s")).isEqualTo(List.of()));
        }
    }

    @Test
    void syncの各インポートも無応答のエージェントに対してProvisioningExceptionになる() throws Exception {
        try (HangingAgentServer server = new HangingAgentServer()) {
            WordPressSyncClient client = new WordPressSyncClient(server.baseUrl(), "tok", CONNECT, READ);
            assertTimeoutPreemptively(LIMIT, () -> {
                assertThatThrownBy(() -> client.importDatabase("b", "bdb", "u", "p", new byte[] {1}))
                        .isInstanceOf(ProvisioningException.class);
                assertThatThrownBy(() -> client.importMedia("b", new byte[] {1}))
                        .isInstanceOf(ProvisioningException.class);
                assertThatThrownBy(() -> client.importThemes("b", new byte[] {1}))
                        .isInstanceOf(ProvisioningException.class);
            });
        }
    }
}
