package com.letsblog.platform.testsupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 外部プロセス(mysqldump / mysql / pg_dump / pg_restore)の代役({@code src/test/fake-bin})を操作する(issue #1825)。
 *
 * <p>代役は{@code FAKE_BIN_CONTROL}ディレクトリ(Gradleが設定)の中のファイルで振る舞いを決め、呼び出しを記録する。
 * {@link #reset()}で空にしてから、テストごとに{@link #version}・{@link #fail}・{@link #output}で振る舞いを決める。
 */
public final class FakeBin {

    private FakeBin() {
    }

    private static Path dir() {
        String value = System.getenv("FAKE_BIN_CONTROL");
        if (value == null) {
            throw new IllegalStateException("FAKE_BIN_CONTROL が設定されていません(Gradleのtestタスクから実行してください)");
        }
        return Path.of(value);
    }

    public static void reset() {
        Path dir = dir();
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }
            }
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(String file, String content) {
        try {
            Files.writeString(dir().resolve(file), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code <name> --version}の出力を決める。 */
    public static void version(String name, String text) {
        write(name + ".version", text + "\n");
    }

    /** {@code name}を、標準エラーに{@code stderr}を出して終了コード1で終わらせる。 */
    public static void fail(String name, String stderr) {
        write(name + ".fail", stderr);
    }

    /** {@code npx}(VSCode拡張のビルドの最後の手順)が、成果物を書く前にかける時間。 */
    public static void npxSleepSeconds(String seconds) {
        write("npx.sleep", seconds);
    }

    /** {@code npx}を、成功扱いのまま成果物を書かないようにする。 */
    public static void npxWritesNoOutput() {
        write("npx.nooutput", "");
    }

    /** ダンプ系({@code mysqldump} / {@code pg_dump})の標準出力を決める。 */
    public static void output(String name, String stdout) {
        write(name + ".out", stdout);
    }

    /** 呼び出しの記録。1要素が1回の呼び出しで、{@code [コマンド名, 引数...]}。 */
    public static List<List<String>> calls() {
        Path log = dir().resolve("calls.log");
        List<List<String>> calls = new ArrayList<>();
        try {
            if (!Files.exists(log)) {
                return calls;
            }
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                calls.add(List.of(line.split("\t", -1)));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return calls;
    }

    /** {@code name}の呼び出しだけ(コマンド名を除いた引数のリスト)を、{@code --version}の問い合わせを除いて返す。 */
    public static List<List<String>> invocations(String name) {
        List<List<String>> result = new ArrayList<>();
        for (List<String> call : calls()) {
            if (call.get(0).equals(name) && !(call.size() == 2 && call.get(1).equals("--version"))) {
                result.add(call.subList(1, call.size()));
            }
        }
        return result;
    }

    /** {@code npm}が呼ばれたときの作業ディレクトリの内容(find の出力)。呼び出し順。 */
    public static List<String> npmWorkingDirectories() {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir())) {
            list.filter(p -> p.getFileName().toString().startsWith("npm.cwd.")).forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        files.sort(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().substring(8))));
        List<String> contents = new ArrayList<>();
        for (Path file : files) {
            try {
                contents.add(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return contents;
    }

    /** {@code name}が標準入力で受けた内容(呼び出し順)。 */
    public static List<String> stdins(String name) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir())) {
            list.filter(p -> p.getFileName().toString().startsWith(name + ".stdin.")).forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        files.sort(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().substring(name.length() + 7))));
        List<String> contents = new ArrayList<>();
        for (Path file : files) {
            try {
                contents.add(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return contents;
    }
}
