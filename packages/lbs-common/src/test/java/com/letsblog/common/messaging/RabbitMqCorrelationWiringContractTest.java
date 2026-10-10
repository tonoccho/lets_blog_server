package com.letsblog.common.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * RabbitMQの発行・購読の構成が、全サービスで処理ID(相関ID)を引き継ぐ形になっていることを
 * 検査する契約テスト(issue #1735)。サービスの一覧は固定で持たず、ソース上の使用を見て
 * 対象を決めるので、後から発行・購読を足したサービスも自動的に検査に入る。
 *
 * <ul>
 *   <li>{@code RabbitTemplate}を使うサービスは、自前の{@code new RabbitTemplate(}を組み立てる
 *       クラスで{@link CorrelationIdMessagePostProcessor}を登録していること(Spring Bootが自動構成する
 *       RabbitTemplateには付かないため、使っていて自前のBeanが無いのは構成の抜け)。</li>
 *   <li>{@code SimpleRabbitListenerContainerFactory}を組み立てる全クラスで
 *       {@link CorrelationIdListenerAdvice}を登録していること。</li>
 *   <li>{@code @RabbitListener}を持つサービスは、上記のファクトリを自前で組み立てていること
 *       (自動構成のファクトリにはadviceが付かない)。</li>
 * </ul>
 */
class RabbitMqCorrelationWiringContractTest {

    private static final List<String> ALL_SERVICES = List.of(
            "log-writer", "identity", "media", "ai", "content", "analytics",
            "project", "platform", "publishing", "gateway");

    private static final Pattern TEMPLATE_USE = Pattern.compile("\\bRabbitTemplate\\b");

    @Test
    void 検査対象のサービスが1つ以上ある() throws IOException {
        assertFalse(servicesMatching(TEMPLATE_USE).isEmpty(), "RabbitTemplateを使うサービスが見つかりません");
        assertFalse(servicesMatching(Pattern.compile("@RabbitListener")).isEmpty(),
                "@RabbitListenerを持つサービスが見つかりません");
    }

    @TestFactory
    Stream<DynamicTest> RabbitTemplateを使う全サービスが処理IDのPostProcessor付きの自前Beanを持つ() throws IOException {
        Path repoRoot = findRepoRoot();
        return servicesMatching(TEMPLATE_USE).stream().map(service -> dynamicTest(service, () -> {
            List<Path> builders = sourcesContaining(sourceRoot(repoRoot, service), "new RabbitTemplate(");
            assertFalse(builders.isEmpty(), "services/" + service + " はRabbitTemplateを使うが、new RabbitTemplate(...)で"
                    + "組み立てる@Beanが無く、自動構成のRabbitTemplate(処理IDのヘッダが付かない)になります。");
            for (Path builder : builders) {
                String source = readFile(builder);
                assertTrue(source.contains("setBeforePublishPostProcessors(") && source.contains("new CorrelationIdMessagePostProcessor()"),
                        builder + " はRabbitTemplateを組み立てるが、CorrelationIdMessagePostProcessorを"
                                + "setBeforePublishPostProcessorsへ登録していません。");
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> リスナーコンテナファクトリを組み立てる全クラスがCorrelationIdListenerAdviceを登録している()
            throws IOException {
        Path repoRoot = findRepoRoot();
        return ALL_SERVICES.stream().flatMap(service -> sourcesContaining(sourceRoot(repoRoot, service),
                "new SimpleRabbitListenerContainerFactory(").stream()).map(factory -> dynamicTest(factory.toString(), () -> {
                    String source = readFile(factory);
                    assertTrue(source.contains("CorrelationIdListenerAdvice") && source.contains("setAdviceChain("),
                            factory + " はlistener container factoryを組み立てるが、CorrelationIdListenerAdviceを"
                                    + "setAdviceChainへ登録していません。");
                }));
    }

    @TestFactory
    Stream<DynamicTest> RabbitListenerを持つ全サービスがadvice付きのファクトリを自前で組み立てている() throws IOException {
        Path repoRoot = findRepoRoot();
        return servicesMatching(Pattern.compile("@RabbitListener")).stream().map(service -> dynamicTest(service, () ->
                assertFalse(sourcesContaining(sourceRoot(repoRoot, service), "new SimpleRabbitListenerContainerFactory(").isEmpty(),
                        "services/" + service + " は@RabbitListenerを持つが、自前のSimpleRabbitListenerContainerFactoryが無く、"
                                + "処理ID・完了行のadviceが付きません。")));
    }

    private static List<String> servicesMatching(Pattern pattern) {
        Path repoRoot = findRepoRoot();
        return ALL_SERVICES.stream().filter(service -> {
            Path root = sourceRoot(repoRoot, service);
            return Files.exists(root) && !sourcesMatching(root, pattern).isEmpty();
        }).toList();
    }

    private static Path sourceRoot(Path repoRoot, String service) {
        return repoRoot.resolve("services").resolve(service).resolve("src/main/java");
    }

    private static List<Path> sourcesContaining(Path root, String needle) {
        return Files.exists(root) ? sourcesMatching(root, Pattern.compile(Pattern.quote(needle))) : List.of();
    }

    private static List<Path> sourcesMatching(Path root, Pattern pattern) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> pattern.matcher(readFile(p)).find())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("settings.gradleが見つからずリポジトリルートを特定できませんでした");
    }
}
