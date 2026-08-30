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

    /**
     * クラスレベルの {@code @RequestMapping}。クラス宣言の直前にあるものだけを採る。
     *
     * <p>ファイル内で最初に現れる {@code @RequestMapping} を無条件に採ると、
     * メソッドレベルで {@code @RequestMapping} を使っているコントローラが混ざったときに
     * base パスが壊れる。
     */
    private static final Pattern CLASS_MAPPING = Pattern.compile(
            "@RequestMapping\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]*)\"[^)]*\\)"
                    + "(?:\\s*@\\w+(?:\\([^)]*\\))?)*\\s*(?:public\\s+)?(?:final\\s+)?class");

    /**
     * メソッドレベルのマッピング注釈。
     *
     * <p>{@code @RequestMapping(method = ...)} も対象に含める。gateway の
     * {@code RouteControllerContractTest} が既にそうしており、そちらに合わせる。
     */
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b(\\([^)]*\\))?");

    /** {@code value = } のような名前付き属性の先頭。 */
    private static final Pattern NAMED_ATTRIBUTE = Pattern.compile("^(\\w+)\\s*=\\s*");

    /** 走査対象のクラスを判定する注釈。 */
    private static final Pattern CONTROLLER_ANNOTATION =
            Pattern.compile("@(?:Rest)?Controller\\b");

    /** パスを表さない注釈属性。これらだけならパス無し(クラスのパスをそのまま使う)。 */
    private static final Set<String> NON_PATH_ATTRIBUTES =
            Set.of("method", "produces", "consumes", "headers", "params", "name");


    /** {@code @RequestMapping(method = ...)} の属性値。{@code {GET, POST}} の配列も含む。 */
    private static final Pattern MAPPING_METHOD_ATTR =
            Pattern.compile("method\\s*=\\s*(\\{[^}]*\\}|[\\w.]+)");

    /** 上の属性値から個々のHTTPメソッド名を取り出す。 */
    private static final Pattern REQUEST_METHOD_NAME =
            Pattern.compile("(?:RequestMethod\\.)?([A-Z]+)");

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

    /**
     * 走査対象は「{@code @RestController} または {@code @Controller} を持つ .java」。
     *
     * <p>ファイル名({@code *Controller.java})で絞ると、命名から外れたコントローラが
     * <b>丸ごと不可視</b>になる。命名を強制する仕組みはリポジトリに無いので、注釈で判定する。
     */
    private static List<Endpoint> scanControllerEndpoints(Path sourceRoot) {
        List<Endpoint> endpoints = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(p -> {
                String source = stripComments(read(p));
                if (CONTROLLER_ANNOTATION.matcher(source).find()) {
                    endpoints.addAll(parseController(p.getFileName().toString(), source));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("コントローラの走査に失敗しました: " + sourceRoot, e);
        }
        return endpoints;
    }

    /**
     * コメントとJavadocを空白へ置き換える(位置をずらさないため長さは保つ)。
     *
     * <p>本リポジトリはJavadocが厚く、説明のために{@code {@code @GetMapping("/x")}}のような
     * コード例を書く動機が現実にある。除去しないと、それが実在のエンドポイントとして
     * <b>幻の「検証漏れ」</b>に化ける。コメントアウトされた古い注釈も同様。
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < source.length()) {
                    if (source.charAt(end) == '\\') {
                        end += 2;
                        continue;
                    }
                    if (source.charAt(end) == c) {
                        break;
                    }
                    end++;
                }
                end = Math.min(end + 1, source.length());
                out.append(source, i, end);
                i = end;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                end = end < 0 ? source.length() : end;
                out.append(" ".repeat(end - i));
                i = end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                for (int k = i; k < end; k++) {
                    out.append(source.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<Endpoint> parseController(String fileName, String source) {
        String base = "";
        Matcher classMapping = CLASS_MAPPING.matcher(source);
        if (classMapping.find()) {
            base = classMapping.group(1);
        }

        List<Endpoint> endpoints = new ArrayList<>();
        Matcher methodMapping = METHOD_MAPPING.matcher(source);
        while (methodMapping.find()) {
            String annotation = methodMapping.group(1);
            String args = methodMapping.group(2) == null ? "" : methodMapping.group(2);

            // クラスレベルの @RequestMapping はここでは扱わない(baseとして既に採っている)。
            if ("Request".equals(annotation) && isClassLevel(source, methodMapping.end())) {
                continue;
            }

            for (String verb : httpMethods(fileName, annotation, args)) {
                for (String sub : paths(fileName, args)) {
                    String path = join(base, sub);
                    endpoints.add(new Endpoint(verb, path.isEmpty() ? "/" : path));
                }
            }
        }
        return endpoints;
    }

    /** 注釈の直後がクラス宣言なら、それはクラスレベルの {@code @RequestMapping}。 */
    private static boolean isClassLevel(String source, int annotationEnd) {
        String rest = source.substring(annotationEnd, Math.min(source.length(), annotationEnd + 400));
        return rest.matches("(?s)(?:\\s*@\\w+(?:\\([^)]*\\))?)*\\s*(?:public\\s+)?(?:final\\s+)?class\\b.*");
    }

    /**
     * 注釈が表す HTTP メソッド。
     *
     * <p>{@code @RequestMapping} に {@code method} が無い場合、Spring は全メソッドを受ける。
     * 一覧との突き合わせでどう扱うべきか一意に決まらないので、<b>静かに縮退させず落とす</b>。
     * この形が出てきたら走査ロジックの更新が必要という合図。
     *
     * <p>{@code method = {GET, POST}} の配列も展開する。1つしか拾わないと残りが無音で消える。
     */
    private static List<String> httpMethods(String fileName, String annotation, String args) {
        if (!"Request".equals(annotation)) {
            return List.of(annotation.toUpperCase(Locale.ROOT));
        }
        List<String> methods = new ArrayList<>();
        Matcher attr = MAPPING_METHOD_ATTR.matcher(args);
        if (attr.find()) {
            Matcher name = REQUEST_METHOD_NAME.matcher(attr.group(1));
            while (name.find()) {
                methods.add(name.group(1));
            }
        }
        if (methods.isEmpty()) {
            throw new AssertionError(fileName
                    + ": メソッドレベルの @RequestMapping に method 属性がありません。"
                    + "全HTTPメソッドを受けるため一覧との対応が一意に決まりません。"
                    + " @GetMapping 等へ書き換えるか、AuthorizationMatrixContract を拡張してください");
        }
        return methods;
    }

    /**
     * 注釈が表すパス。{@code @GetMapping} のように値が無ければクラスのパスをそのまま使う。
     *
     * <p><b>属性名で厳密に判別する</b>。#805 のQAで、位置引数を「引数中の任意の文字列リテラル」
     * として拾っていたために {@code @GetMapping(produces = "text/plain")} が
     * <b>幻の {@code /text/plain} を報告し、本物のパスを取りこぼす</b>ことが判明した。
     * メッセージに従って幻のパスを一覧に足すと緑になり、実エンドポイントが検証されないまま固定される。
     *
     * <p>また、パス式から文字列リテラルを取り出せない場合(定数参照 {@code @GetMapping(PATH_CONST)}
     * など)は<b>落とす</b>。「値なし」と同一視するとクラスのパスに化け、
     * base が一覧にあれば完全に無音で通ってしまう。
     */
    private static List<String> paths(String fileName, String args) {
        String inner = args.isEmpty() ? "" : args.substring(1, args.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of("");
        }

        String pathExpression = null;
        boolean onlyNonPathAttributes = true;
        List<String> tokens = splitTopLevel(inner);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i).trim();
            Matcher named = NAMED_ATTRIBUTE.matcher(token);
            if (named.find()) {
                String attribute = named.group(1);
                if ("value".equals(attribute) || "path".equals(attribute)) {
                    pathExpression = token.substring(named.end()).trim();
                    onlyNonPathAttributes = false;
                } else if (!NON_PATH_ATTRIBUTES.contains(attribute)) {
                    onlyNonPathAttributes = false;
                }
            } else if (i == 0) {
                // 位置引数。属性名が無いのは先頭のパス指定のときだけ。
                pathExpression = token;
                onlyNonPathAttributes = false;
            }
        }

        if (pathExpression == null) {
            if (onlyNonPathAttributes) {
                return List.of("");
            }
            throw new AssertionError(fileName
                    + ": マッピング注釈の引数を解釈できませんでした: " + args
                    + " AuthorizationMatrixContract の解析を拡張してください");
        }

        List<String> paths = new ArrayList<>();
        Matcher literal = STRING_LITERAL.matcher(pathExpression);
        while (literal.find()) {
            paths.add(literal.group(1));
        }
        if (paths.isEmpty()) {
            throw new AssertionError(fileName
                    + ": マッピング注釈のパスが文字列リテラルではありません: " + pathExpression
                    + " 定数参照は走査できません。リテラルで書くか、"
                    + "AuthorizationMatrixContract の解析を拡張してください");
        }
        return paths;
    }

    /** 注釈の引数を、波括弧と文字列リテラルを尊重してトップレベルのカンマで分割する。 */
    private static List<String> splitTopLevel(String inner) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    current.append(c);
                }
                case '{', '(' -> {
                    depth++;
                    current.append(c);
                }
                case '}', ')' -> {
                    depth--;
                    current.append(c);
                }
                case ',' -> {
                    if (depth == 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                default -> current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** クラスのパスとメソッドのパスを、スラッシュが重複しないように連結する。 */
    private static String join(String base, String sub) {
        if (sub.isEmpty()) {
            return base;
        }
        if (base.endsWith("/") && sub.startsWith("/")) {
            return base + sub.substring(1);
        }
        if (!base.isEmpty() && !base.endsWith("/") && !sub.startsWith("/")) {
            return base + "/" + sub;
        }
        return base + sub;
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
