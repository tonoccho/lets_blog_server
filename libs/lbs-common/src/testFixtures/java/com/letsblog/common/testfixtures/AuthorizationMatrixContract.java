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
            "@RequestMapping\\s*\\([^)]*\\)"
                    + "(?:\\s*@\\w+(?:\\([^)]*\\))?)*\\s*(?:public\\s+)?(?:abstract\\s+)?"
                    + "(?:final\\s+)?class");

    /**
     * メソッドレベルのマッピング注釈。
     *
     * <p>{@code @RequestMapping(method = ...)} も対象に含める。gateway の
     * {@code RouteControllerContractTest} が既にそうしており、そちらに合わせる。
     */
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b");

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
                String source = read(p);
                String masked = maskLiterals(source);
                if (CONTROLLER_ANNOTATION.matcher(masked).find()) {
                    endpoints.addAll(parseController(p.getFileName().toString(), source, masked));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("コントローラの走査に失敗しました: " + sourceRoot, e);
        }
        return endpoints;
    }

    /**
     * ソースから「コード視点」を作る。コメント・Javadoc・文字列リテラルの中身を空白へ置き換え、
     * <b>長さは保つ</b>ので元ソースとオフセットが一致する。
     *
     * <p>注釈の位置と引数の範囲はこの視点で探し、<b>引数の中身は元ソースから取り出す</b>。
     * こうすると次の3つが同時に塞がる。
     *
     * <ul>
     *   <li>Javadocのコード例({@code {@code @GetMapping("/x")}})やコメントアウトされた注釈が
     *       実在のエンドポイントとして<b>幻の「検証漏れ」</b>に化ける</li>
     *   <li>テキストブロック({@code """..."""})に書いたコード例が同様に化ける。
     *       本リポジトリはLLMプロンプトにテキストブロックを7ファイルで使っており、
     *       そこにSpringのコード例が入る動機は現実にある</li>
     *   <li>文字列の中の{@code )}が注釈の引数を途中で切る。
     *       {@code @GetMapping(params = "a=(b)", value = "/x")}のような形で
     *       「paramsだけ」に見えてクラスのパスへ<b>無音で縮退</b>していた</li>
     * </ul>
     */
    private static String maskLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("\"\"\"", i)) {
                // 終端探索では \""" のエスケープを飛ばす。見落とすと早期終了して
                // 以降のクラス本体がまるごと文字列として飲まれ、そのクラスの
                // 実エンドポイントが全部見えなくなる。
                int end = i + 3;
                while (end < source.length()) {
                    int candidate = source.indexOf("\"\"\"", end);
                    if (candidate < 0) {
                        end = source.length();
                        break;
                    }
                    if (candidate > 0 && source.charAt(candidate - 1) == '\\') {
                        end = candidate + 1;
                        continue;
                    }
                    end = candidate + 3;
                    break;
                }
                out.append("\"\"\"");
                for (int k = i + 3; k < Math.max(i + 3, end - 3); k++) {
                    out.append(source.charAt(k) == '\n' ? '\n' : ' ');
                }
                if (end - 3 >= i + 3) {
                    out.append("\"\"\"");
                }
                i = end;
                continue;
            }
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != c) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                out.append(c).append(" ".repeat(Math.max(0, Math.min(end, source.length()) - i - 1)));
                if (end < source.length()) {
                    out.append(c);
                }
                i = Math.min(end + 1, source.length());
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

    /** {@code (} から対応する {@code )} までの範囲。マスク済みの視点で数えるので文字列は邪魔しない。 */
    private static int matchingParen(String masked, int open) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            if (masked.charAt(i) == '(') {
                depth++;
            } else if (masked.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<Endpoint> parseController(String fileName, String source, String masked) {
        String base = "";
        Matcher classMapping = CLASS_MAPPING.matcher(masked);
        if (classMapping.find()) {
            // パスは元ソースから取る(マスク視点では中身が空白になっている)。
            base = pathOfClassMapping(fileName, source, masked, classMapping.start());
        }

        List<Endpoint> endpoints = new ArrayList<>();
        Matcher methodMapping = METHOD_MAPPING.matcher(masked);
        while (methodMapping.find()) {
            String annotation = methodMapping.group(1);
            int argsStart = masked.indexOf('(', methodMapping.end() - 1);
            String args = "";
            String argsMasked = "";
            int annotationEnd = methodMapping.end();
            // 注釈名の直後が '(' のときだけ引数とみなす(次の注釈の '(' を拾わない)。
            if (argsStart >= 0 && masked.substring(methodMapping.end(), argsStart).isBlank()) {
                int argsEnd = matchingParen(masked, argsStart);
                if (argsEnd < 0) {
                    throw new AssertionError(fileName + ": 注釈の括弧が閉じていません: " + annotation);
                }
                args = source.substring(argsStart, argsEnd + 1);
                argsMasked = masked.substring(argsStart, argsEnd + 1);
                annotationEnd = argsEnd + 1;
            }

            // クラスレベルの @RequestMapping はここでは扱わない(baseとして既に採っている)。
            if ("Request".equals(annotation) && isClassLevel(masked, annotationEnd)) {
                continue;
            }

            for (String verb : httpMethods(fileName, annotation, argsMasked)) {
                for (String sub : paths(fileName, args, argsMasked)) {
                    String path = join(base, sub);
                    endpoints.add(new Endpoint(verb, path.isEmpty() ? "/" : path));
                }
            }
        }
        return endpoints;
    }

    /** クラスレベル {@code @RequestMapping} のパス。配列や定数参照はここでも落とす。 */
    private static String pathOfClassMapping(String fileName, String source, String masked, int start) {
        int open = masked.indexOf('(', start);
        int close = matchingParen(masked, open);
        if (open < 0 || close < 0) {
            throw new AssertionError(fileName + ": クラスレベル @RequestMapping の括弧が閉じていません");
        }
        List<String> paths = paths(fileName, source.substring(open, close + 1),
                masked.substring(open, close + 1));
        if (paths.size() != 1) {
            throw new AssertionError(fileName
                    + ": クラスレベル @RequestMapping が複数パスを持ちます: " + paths
                    + " AuthorizationMatrixContract の解析を拡張してください");
        }
        return paths.get(0);
    }

    /** 注釈の直後がクラス宣言なら、それはクラスレベルの {@code @RequestMapping}。 */
    private static boolean isClassLevel(String source, int annotationEnd) {
        String rest = source.substring(annotationEnd, Math.min(source.length(), annotationEnd + 400));
        return rest.matches("(?s)(?:\\s*@\\w+(?:\\([^)]*\\))?)*\\s*(?:public\\s+)?(?:abstract\\s+)?"
                + "(?:final\\s+)?class\\b.*");
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
     * <p><b>属性名で厳密に判別する</b>。位置引数を「引数中の任意の文字列リテラル」として拾うと、
     * {@code @GetMapping(produces = "text/plain")} が幻の {@code /text/plain} を報告し、
     * 本物のパスを取りこぼす。メッセージに従って幻を一覧に足すと緑になり、
     * 実エンドポイントが検証されないまま固定される。
     *
     * <p><b>判定はマスク視点、値は元ソース</b>。引数の内側にもこの原則を適用しないと、
     * 引数内の {@code //} コメントがトークンの先頭に来て属性名の判定を外し、
     * <b>そのエンドポイントが無音で消える</b>。コメント内の文字列が幻のパスにもなる。
     *
     * <p>パス式から文字列リテラルを取り出せない場合(定数参照など)は<b>落とす</b>。
     * 「値なし」と同一視するとクラスのパスに化け、base が一覧にあれば完全に無音で通ってしまう。
     */
    private static List<String> paths(String fileName, String args, String argsMasked) {
        if (args.isEmpty()) {
            return List.of("");
        }
        String inner = args.substring(1, args.length() - 1);
        String innerMasked = argsMasked.substring(1, argsMasked.length() - 1);
        if (innerMasked.isBlank()) {
            return List.of("");
        }

        int[] pathSpan = null;
        boolean onlyNonPathAttributes = true;
        List<int[]> tokens = splitTopLevel(innerMasked);
        for (int i = 0; i < tokens.size(); i++) {
            int[] span = tokens.get(i);
            String masked = innerMasked.substring(span[0], span[1]);
            if (masked.isBlank()) {
                continue;
            }
            int lead = masked.length() - masked.stripLeading().length();
            Matcher named = NAMED_ATTRIBUTE.matcher(masked.strip());
            if (named.find()) {
                String attribute = named.group(1);
                if ("value".equals(attribute) || "path".equals(attribute)) {
                    pathSpan = new int[] {span[0] + lead + named.end(), span[1]};
                    onlyNonPathAttributes = false;
                } else if (!NON_PATH_ATTRIBUTES.contains(attribute)) {
                    onlyNonPathAttributes = false;
                }
            } else if (i == 0) {
                // 位置引数。属性名が無いのは先頭のパス指定のときだけ。
                pathSpan = span;
                onlyNonPathAttributes = false;
            }
        }

        if (pathSpan == null) {
            if (onlyNonPathAttributes) {
                return List.of("");
            }
            throw new AssertionError(fileName
                    + ": マッピング注釈の引数を解釈できませんでした: " + args
                    + " AuthorizationMatrixContract の解析を拡張してください");
        }

        // 値は元ソースから取るが、マスク側で二重引用符になっている位置のものだけを採る
        // (コメントの中の文字列を幻のパスとして拾わないため)。
        List<String> paths = new ArrayList<>();
        Matcher literal = STRING_LITERAL.matcher(inner);
        literal.region(pathSpan[0], pathSpan[1]);
        while (literal.find()) {
            if (innerMasked.charAt(literal.start()) == '"') {
                paths.add(literal.group(1));
            }
        }
        if (paths.isEmpty()) {
            throw new AssertionError(fileName
                    + ": マッピング注釈のパスが文字列リテラルではありません: "
                    + inner.substring(pathSpan[0], pathSpan[1]).strip()
                    + " 定数参照は走査できません。リテラルで書くか、"
                    + "AuthorizationMatrixContract の解析を拡張してください");
        }
        return paths;
    }

    /**
     * 注釈の引数を、波括弧を尊重してトップレベルのカンマで分割し、各トークンの範囲を返す。
     *
     * <p>マスク済みの視点を渡す前提。文字列の中身もコメントも空白になっているので、
     * それらの中のカンマで誤って分割することがない。
     */
    private static List<int[]> splitTopLevel(String innerMasked) {
        List<int[]> tokens = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < innerMasked.length(); i++) {
            char c = innerMasked.charAt(i);
            if (c == '{' || c == '(') {
                depth++;
            } else if (c == '}' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                tokens.add(new int[] {start, i});
                start = i + 1;
            }
        }
        tokens.add(new int[] {start, innerMasked.length()});
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

    /**
     * {@code SecurityConfig} の {@code PUBLIC_PATHS} を読む。見つからなければ空。
     *
     * <p>ブロックの範囲は<b>マスク視点</b>で探し、値は<b>元ソース</b>から取る。
     * マスクを通さないとコメントアウトされた行が有効な公開パスとして読まれ、
     * 実在する認証必須エンドポイントが必須リストから黙って除外される。
     * 「{@code PUBLIC_PATHS} の行をコメントアウトして認証必須にする」は、
     * まさにこのテストが守るべき変更なので、素通しすると無音の穴になる。
     */
    private static Set<String> readPublicPaths(Path sourceRoot) {
        Set<String> paths = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.getFileName().toString().equals("SecurityConfig.java"))
                    .forEach(p -> {
                        String source = read(p);
                        Matcher block = PUBLIC_PATHS_BLOCK.matcher(maskLiterals(source));
                        if (!block.find()) {
                            return;
                        }
                        // 元ソースから読むが、コメント内の文字列は採らない。
                        // maskLiterals は長さを保つので、同じ位置がマスク側でも二重引用符なら
                        // 「コードとして生きている文字列」、空白なら「コメントの中」と判別できる。
                        String masked = maskLiterals(source);
                        Matcher literal = STRING_LITERAL.matcher(source);
                        literal.region(block.start(1), block.end(1));
                        while (literal.find()) {
                            if (masked.charAt(literal.start()) == '"') {
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
