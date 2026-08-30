package com.letsblog.common.testfixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 各サービスの {@code AuthorizationMatrixIntegrationTest} が列挙するエンドポイント一覧と、
 * コントローラの実マッピングが一致していることを検証する(issue #805)。
 *
 * <p><b>なぜ必要か</b>: 一覧は手書きで、サービス抽出のたびにコントローラが移設されても
 * 追随しない。#731 では legacy-api の一覧に<b>実体の無いパスが107件</b>残っていた。
 * しかも「認証ヘッダーが無ければ401」というテストは、存在しないパスに対しても
 * (404ではなく401が返るため)<b>通ってしまう</b>。つまり陳腐化しても誰も気付かない。
 *
 * <p>逆方向の漏れも起きる。新しいエンドポイントを足しても一覧に追加しなければ、
 * そのエンドポイントは認証ゲートの検証対象から外れたままになる。
 *
 * <p><b>双方向で検証する</b>:
 * <ul>
 *   <li>一覧にあってコントローラに無い(陳腐化)</li>
 *   <li>コントローラにあって一覧に無い(検証漏れ)</li>
 * </ul>
 *
 * <p><b>除外は {@code SecurityConfig} の {@code PUBLIC_PATHS} から導出する</b>。
 * 定数を二重に持つと、そちらがまた陳腐化する。実際の宣言をソースから読む。
 *
 * <p>gateway の {@code RouteControllerContractTest} が同じくソースツリーを走査する先例。
 * あちらは「どのサービスへ転送されるか」を見るので観点が異なる。
 */
public final class AuthorizationMatrixContract {

    private AuthorizationMatrixContract() {
    }

    /** 認証ゲートの検証対象となる1エンドポイント。 */
    public record Endpoint(String method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** クラスレベルの {@code @RequestMapping("...")}。 */
    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"\\s*\\)");

    /** メソッドレベルの {@code @GetMapping} など。値が無い場合はクラスのパスをそのまま使う。 */
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping"
                    + "(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\))?");

    /** {@code SecurityConfig} の {@code PUBLIC_PATHS} 配列。 */
    private static final Pattern PUBLIC_PATHS_BLOCK =
            Pattern.compile("PUBLIC_PATHS\\s*=\\s*\\{([^}]*)\\}", Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /**
     * 一覧とコントローラの実マッピングを突き合わせ、不一致があれば {@link AssertionError} を投げる。
     *
     * @param serviceModule {@code services/} 配下のディレクトリ名(例: {@code "ai"})
     * @param listed        テストが列挙しているエンドポイント。パス変数には具体値が入っていてよい
     *                      (例: {@code /api/projects/1/...})。コントローラ側のテンプレートと
     *                      パターンマッチで突き合わせる
     */
    public static void verifyMatchesControllers(String serviceModule, Collection<Endpoint> listed) {
        Path serviceRoot = findRepoRoot().resolve("services").resolve(serviceModule);
        List<Endpoint> declared = scanControllerEndpoints(serviceRoot.resolve("src/main/java"));
        Set<String> publicPaths = readPublicPaths(serviceRoot.resolve("src/main/java"));

        if (declared.isEmpty()) {
            throw new AssertionError(
                    serviceModule + ": コントローラのマッピングを1件も検出できませんでした。"
                            + "走査対象(" + serviceRoot + ")かパターンを見直してください");
        }

        // PUBLIC_PATHS に該当するものは認証ゲートの対象外なので、一覧に無くてよい。
        List<Endpoint> mustBeListed = declared.stream()
                .filter(e -> publicPaths.stream().noneMatch(p -> matches(p, e.path())))
                .toList();

        List<String> stale = listed.stream()
                .filter(e -> mustBeListed.stream().noneMatch(d -> sameEndpoint(d, e)))
                .map(Endpoint::toString)
                .toList();

        List<String> uncovered = mustBeListed.stream()
                .filter(d -> listed.stream().noneMatch(e -> sameEndpoint(d, e)))
                .map(Endpoint::toString)
                .toList();

        if (stale.isEmpty() && uncovered.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder(serviceModule)
                .append(": エンドポイント一覧がコントローラと一致していません。\n");
        if (!stale.isEmpty()) {
            message.append("\n【一覧にあるがコントローラに無い(陳腐化。移設・削除の追随漏れ)】\n");
            stale.forEach(s -> message.append("  - ").append(s).append('\n'));
        }
        if (!uncovered.isEmpty()) {
            message.append("\n【コントローラにあるが一覧に無い(検証漏れ。認証ゲートが検証されていない)】\n");
            uncovered.forEach(s -> message.append("  - ").append(s).append('\n'));
            message.append("\nPUBLIC_PATHSに入れるべきものなら SecurityConfig を、"
                    + "そうでなければ一覧を更新してください。\n");
        }
        throw new AssertionError(message.toString());
    }

    /**
     * 一覧の具体値(例 {@code /api/projects/1/x})が、コントローラのテンプレート
     * (例 {@code /api/projects/{projectId}/x})に一致するか。
     */
    private static boolean sameEndpoint(Endpoint declared, Endpoint listed) {
        return declared.method().equals(listed.method()) && matches(declared.path(), listed.path());
    }

    /** パステンプレート({@code {var}} と {@code *} / {@code **} を含みうる)を正規表現として当てる。 */
    private static boolean matches(String template, String concrete) {
        StringBuilder regex = new StringBuilder("^");
        Matcher m = Pattern.compile("\\{[^/}]*\\}|\\*\\*|\\*").matcher(template);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(last, m.start())));
            regex.append(switch (m.group()) {
                case "**" -> ".*";
                case "*" -> "[^/]*";
                default -> "[^/]+";
            });
            last = m.end();
        }
        regex.append(Pattern.quote(template.substring(last))).append("$");
        return concrete.matches(regex.toString());
    }

    private static List<Endpoint> scanControllerEndpoints(Path sourceRoot) {
        List<Endpoint> endpoints = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .forEach(p -> endpoints.addAll(parseController(read(p))));
        } catch (IOException e) {
            throw new UncheckedIOException("コントローラの走査に失敗しました: " + sourceRoot, e);
        }
        return endpoints;
    }

    private static List<Endpoint> parseController(String source) {
        String base = "";
        Matcher classMapping = CLASS_MAPPING.matcher(source);
        if (classMapping.find()) {
            base = classMapping.group(1);
        }

        List<Endpoint> endpoints = new ArrayList<>();
        Matcher methodMapping = METHOD_MAPPING.matcher(source);
        while (methodMapping.find()) {
            String verb = methodMapping.group(1).toUpperCase(Locale.ROOT);
            String sub = methodMapping.group(2) == null ? "" : methodMapping.group(2);
            String path = base + sub;
            endpoints.add(new Endpoint(verb, path.isEmpty() ? "/" : path));
        }
        return endpoints;
    }

    /** {@code SecurityConfig} の {@code PUBLIC_PATHS} を読む。見つからなければ空。 */
    private static Set<String> readPublicPaths(Path sourceRoot) {
        Set<String> paths = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.getFileName().toString().equals("SecurityConfig.java"))
                    .forEach(p -> {
                        Matcher block = PUBLIC_PATHS_BLOCK.matcher(read(p));
                        if (block.find()) {
                            Matcher literal = STRING_LITERAL.matcher(block.group(1));
                            while (literal.find()) {
                                paths.add(literal.group(1));
                            }
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("SecurityConfigの走査に失敗しました: " + sourceRoot, e);
        }
        return paths;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("読み込みに失敗しました: " + path, e);
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
        throw new IllegalStateException(
                "settings.gradleが見つからずリポジトリルートを特定できませんでした(起点: "
                        + Paths.get("").toAbsolutePath() + ")");
    }
}
