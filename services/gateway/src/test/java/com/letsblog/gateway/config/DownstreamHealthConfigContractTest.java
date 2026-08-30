package com.letsblog.gateway.config;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.beans.factory.annotation.Value;
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

    /** {@code settings.gradle}の {@code include 'services:xxx'} 行。 */
    private static final Pattern SERVICE_INCLUDE =
            Pattern.compile("^\\s*include\\s+'services:([a-z0-9-]+)'", Pattern.MULTILINE);

    /**
     * Bean名から導いたサービス名 → 期待する環境変数名 の既定規則からの例外。
     * legacy-apiだけは歴史的経緯で{@code LEGACY_API_URI}(SERVICEが入らない)。
     */
    private static final Map<String, String> ENV_VAR_EXCEPTIONS =
            Map.of("legacy-api", "LEGACY_API_URI", "log-writer", "LOG_SERVICE_URI");

    /** 既定URIのホスト名がモジュール名と異なるもの。 */
    private static final Map<String, String> HOST_EXCEPTIONS = Map.of("legacy-api", "api");

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

    /**
     * {@code settings.gradle}の{@code include 'services:xxx'}行からサービス名を取る。
     *
     * <p>ディレクトリの存在({@code build.gradle}があるか)ではなく{@code settings.gradle}を
     * 一次情報にしているのは、そちらが「このリポジトリが公式に持つサービス」の定義だから。
     * ディレクトリ走査だと、将来{@code build.gradle.kts}を採用したサービスが無言で対象外になり、
     * 逆に未登録のディレクトリを拾ってしまう。
     */
    private static Set<String> serviceModules() throws IOException {
        String settings = Files.readString(findRepoRoot().resolve("settings.gradle"));
        Set<String> modules = new TreeSet<>();
        Matcher matcher = SERVICE_INCLUDE.matcher(settings);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!EXCLUDED_MODULES.contains(name)) {
                modules.add(name);
            }
        }
        return modules;
    }

    /**
     * {@code ReactiveHealthIndicator}を返す{@code @Bean}メソッド名から、対応するサービス名を導く。
     * 命名規約は {@code <camelCaseModule>ServiceHealthIndicator} または
     * {@code <camelCaseModule>HealthIndicator}(legacyApiのようにServiceが付かないもの)。
     */
    private static Set<String> coveredModules() {
        Set<String> covered = new TreeSet<>();
        for (Method method : healthIndicatorBeans()) {
            // camelCase を kebab-case へ(logWriter -> log-writer、legacyApi -> legacy-api)
            covered.add(moduleNameOf(method));
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

    /**
     * Bean名だけを見ていると、コピペで別サービスのURIを指してしまう配線ミスを検出できない
     * (issue #743のレビュー指摘)。たとえば
     * {@code platformServiceHealthIndicator} の引数が
     * {@code @Value("${PROJECT_SERVICE_URI:http://project:8080}")} になっていても、
     * 網羅性のテストも{@link DownstreamHealthConfigTest}(URIを引数で直接渡すため
     * {@code @Value}を評価しない)も通ってしまう。
     *
     * <p>その状態では集約ヘルスが他サービスの状態を別名で報告し続け、
     * #743が問題視した誤報とまったく同じことが起きる。Bean名から期待される
     * 環境変数名と既定ホストを導いて突き合わせる。
     */
    @Test
    @DisplayName("各インジケータの@Valueが自分のサービスのURIを指している")
    void 各インジケータが自分のサービスを見ている() {
        for (Method method : healthIndicatorBeans()) {
            String module = moduleNameOf(method);

            // legacy-apiだけは既定値の無い app.gateway.fallback-uri を使う(ルート表と共有するため)。
            if ("legacy-api".equals(module)) {
                assertThat(valueExpressionOf(method))
                        .as("legacyApiのインジケータはルート表と同じfallback-uriを見るべき")
                        .isEqualTo("${app.gateway.fallback-uri}");
                continue;
            }

            String expectedEnvVar = ENV_VAR_EXCEPTIONS.getOrDefault(
                    module, module.toUpperCase(Locale.ROOT).replace('-', '_') + "_SERVICE_URI");
            String expectedHost = HOST_EXCEPTIONS.getOrDefault(module, module);

            assertThat(valueExpressionOf(method))
                    .as("%s のインジケータが別サービスのURIを指しています(コピペミスの疑い)", module)
                    .isEqualTo("${" + expectedEnvVar + ":http://" + expectedHost + ":8080}");
        }
    }

    private static List<Method> healthIndicatorBeans() {
        return Stream.of(DownstreamHealthConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .filter(m -> ReactiveHealthIndicator.class.isAssignableFrom(m.getReturnType()))
                .sorted(java.util.Comparator.comparing(Method::getName))
                .toList();
    }

    private static String moduleNameOf(Method method) {
        String name = method.getName()
                .replaceFirst("HealthIndicator$", "")
                .replaceFirst("Service$", "");
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String valueExpressionOf(Method method) {
        for (Parameter parameter : method.getParameters()) {
            Value value = parameter.getAnnotation(Value.class);
            if (value != null) {
                return value.value();
            }
        }
        throw new AssertionError(
                method.getName() + " に @Value を持つ引数がありません。URIの配線を検証できません");
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
