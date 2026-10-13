package com.letsblog.common.async;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全サービスの{@code new ThreadPoolTaskExecutor()}がMDC引き継ぎを設定するクラスの中にだけあることを
 * ソースツリーの走査で検証する契約テスト(issue #1731)。各サービスの{@code AsyncJobConfigMdcContractTest}
 * が既知の5つのAsyncJobConfigを振る舞いで検査し、これは、検査の網に掛からない場所に新しいExecutorを
 * 作った場合(別クラスの追加)を失敗させる。
 */
class AsyncExecutorDecoratorSourceContractTest {

    private static final List<String> KNOWN_CONFIGS = List.of(
            "services/ai/src/main/java/com/letsblog/ai/config/AsyncJobConfig.java",
            "services/content/src/main/java/com/letsblog/content/config/AsyncJobConfig.java",
            "services/media/src/main/java/com/letsblog/media/config/AsyncJobConfig.java",
            "services/project/src/main/java/com/letsblog/project/config/AsyncJobConfig.java",
            "services/publishing/src/main/java/com/letsblog/publishing/config/AsyncJobConfig.java");

    @Test
    @DisplayName("ThreadPoolTaskExecutorを作るクラスは既知の5つのAsyncJobConfigだけで、TaskDecoratorを設定している")
    void everyExecutorIsDecoratedAndKnown() throws IOException {
        Path root = findRepoRoot();
        List<String> creators;
        try (Stream<Path> files = Files.walk(root.resolve("services"))) {
            creators = files
                    .filter(p -> p.toString().contains("/src/main/java/") && p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("new ThreadPoolTaskExecutor()"))
                    .map(p -> root.relativize(p).toString())
                    .sorted()
                    .toList();
        }

        assertThat(creators)
                .as("ThreadPoolTaskExecutorを作るクラス。増えたら AsyncExecutorMdcContract の検査対象へ加えること")
                .containsExactlyElementsOf(KNOWN_CONFIGS);
        for (String path : creators) {
            String source = read(root.resolve(path));
            long creations = source.split("new ThreadPoolTaskExecutor\\(\\)", -1).length - 1L;
            long decorations = source.split("setTaskDecorator\\(", -1).length - 1L;
            assertThat(decorations)
                    .as(path + " の setTaskDecorator の数(Executor生成 " + creations + " 件)")
                    .isGreaterThanOrEqualTo(creations);
            assertThat(source).contains("MdcPropagatingTaskDecorator");
        }
    }

    private static String read(Path path) {
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
        throw new IllegalStateException("settings.gradleが見つかりません");
    }
}
