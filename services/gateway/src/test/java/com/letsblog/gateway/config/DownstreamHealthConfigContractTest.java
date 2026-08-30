package com.letsblog.gateway.config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DownstreamHealthConfig}が{@code services/}配下の全サービスを網羅していることを検証する
 * (issue #743)。
 *
 * <p>#743で発覚したのは「platform/project/publishingが集約ヘルスチェックから漏れていた」
 * ことだが、根本は<b>サービスを新設したときに追加すべき場所が複数あり、片方だけ忘れられる</b>
 * ことにある。同型の漏れはルート表側でも起きており、#716でplatformが
 * {@link RouteControllerContractTest}の対応表から抜けていた。
 *
 * <p>そこで個別のBeanを目視で数えるのではなく、{@code services/}配下のディレクトリを列挙して
 * 突き合わせる。次にサービスを増やしたとき、このテストが落ちて気付ける。
 *
 * <p>gatewayだけは対象外。自分自身のヘルスを集約する意味が無い。
 */
@DisplayName("DownstreamHealthConfig: 全サービスの網羅(issue #743)")
class DownstreamHealthConfigContractTest {

    /** 自分自身なので集約対象にしない。 */
    private static final Set<String> EXCLUDED_MODULES = Set.of("gateway");

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "settings.gradleが見つからずリポジトリルートを特定できませんでした(起点: "
                        + Paths.get("").toAbsolutePath() + ")");
    }

    /** {@code services/}配下の、Gradleサブプロジェクトになっているディレクトリ名。 */
    private static Set<String> serviceModules() throws IOException {
        Path servicesDir = findRepoRoot().resolve("services");
        try (Stream<Path> children = Files.list(servicesDir)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve("build.gradle")))
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !EXCLUDED_MODULES.contains(name))
                    .collect(TreeSet::new, Set::add, Set::addAll);
        }
    }

    /**
     * {@code ReactiveHealthIndicator}を返す{@code @Bean}メソッド名から、対応するサービス名を導く。
     * 命名規約は {@code <camelCaseModule>ServiceHealthIndicator} または
     * {@code <camelCaseModule>HealthIndicator}(legacyApiのようにServiceが付かないもの)。
     */
    private static Set<String> coveredModules() {
        Set<String> covered = new TreeSet<>();
        for (Method method : DownstreamHealthConfig.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Bean.class)
                    || !ReactiveHealthIndicator.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            String name = method.getName()
                    .replaceFirst("HealthIndicator$", "")
                    .replaceFirst("Service$", "");
            // camelCase を kebab-case へ(logWriter -> log-writer、legacyApi -> legacy-api)
            covered.add(name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT));
        }
        return covered;
    }

    @Test
    @DisplayName("services/配下の全サービスに対応するReactiveHealthIndicatorのBeanがある")
    void 全サービスがヘルス集約の対象になっている() throws IOException {
        Set<String> modules = serviceModules();
        Set<String> covered = coveredModules();

        assertThat(modules)
                .as("services/配下のサービスが検出できていません。ディレクトリ構成が変わった可能性があります")
                .isNotEmpty();

        assertThat(covered)
                .as("DownstreamHealthConfigに@Beanが見つかりません。命名規約"
                        + "(<module>ServiceHealthIndicator / <module>HealthIndicator)が変わった可能性があります")
                .isNotEmpty();

        assertThat(covered)
                .as("集約ヘルスチェックから漏れているサービスがあります。"
                        + "DownstreamHealthConfigに<module>ServiceHealthIndicatorのBeanを追加してください")
                .containsAll(modules);
    }

    @Test
    @DisplayName("実在しないサービスのヘルスインジケータが残っていない")
    void 実在しないサービスのインジケータが無い() throws IOException {
        assertThat(serviceModules())
                .as("services/配下に無いサービスのヘルスインジケータが残っています。"
                        + "サービスを廃止した際の削除漏れの可能性があります")
                .containsAll(coveredModules());
    }

    /** 網羅の確認だけでなく、検出できた一覧を失敗時に読めるようにしておく。 */
    @Test
    @DisplayName("検出されたサービスとインジケータの一覧が一致する")
    void 検出結果が一致する() throws IOException {
        List<String> modules = List.copyOf(serviceModules());
        List<String> covered = List.copyOf(coveredModules());

        assertThat(covered)
                .as("services/配下: %s / インジケータ: %s", modules, covered)
                .containsExactlyElementsOf(modules);
    }
}
