package com.letsblog.publishing.testsupport;

import com.letsblog.publishing.cms.ssh.SshCommandExecutor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * {@code @SpringBootTest}で起動した実物のアプリケーションコンテキストの、外部との通信だけを{@link PublishingWorld}の
 * サーバー側フェイクへ向ける部品(issue #1816)。自前のBean({@code *Service}・{@code *Client}・コントローラ)は
 * 実物のままで、HTTPの接続先は{@link FakeHttpServer}、SSHは{@link FakeWordPress}へ差し替える。
 *
 * <p>使い方: テストクラスが{@code static final PublishingWorld WORLD = SpringWorld.start()}で世界を作り、
 * {@code @DynamicPropertySource}で{@link #register}を呼び、{@code @Import(SpringWorld.SshBoundary.class)}を付ける。
 */
public final class SpringWorld {

    private static volatile PublishingWorld current;

    private SpringWorld() {
    }

    /** 世界を作り、{@link SshBoundary}が使うSSHフェイクとして登録する。 */
    public static PublishingWorld start() {
        PublishingWorld world = new PublishingWorld();
        current = world;
        return world;
    }

    /** アプリケーションの接続先設定(project/identity/content/media-service・provision-agent・GitHub・ai-service)を世界へ向ける。 */
    public static void register(DynamicPropertyRegistry registry, PublishingWorld world) {
        registry.add("app.project-service-uri", world.project::baseUrl);
        registry.add("app.identity-service-uri", world.identity::baseUrl);
        registry.add("app.content-service-uri", world.content::baseUrl);
        registry.add("app.media-service-uri", world.media::baseUrl);
        registry.add("app.wordpress-provision-base-url", world.agent::baseUrl);
        registry.add("app.github-api-base-url", world.github::baseUrl);
        registry.add("app.ai-service-uri", world.ai::baseUrl);
    }

    /** SSH(外部通信)の境界。{@code SshjCommandExecutor}の代わりに{@link FakeWordPress}を使わせる。 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class SshBoundary {

        @Bean
        @Primary
        SshCommandExecutor sshCommandExecutorFake() {
            return current.ssh;
        }
    }
}
