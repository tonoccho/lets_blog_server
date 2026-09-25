package com.letsblog.common.testfixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 認可チェックを持たないエンドポイントが<b>増えていない</b>ことを保証する契約テスト(issue #830)。
 *
 * <p>#830 の調査で、認可チェック({@code requireAdmin} 等)を一切持たないエンドポイントが
 * 内部ブリッジを除いて99件あることが分かった。個々について「認証のみでよいか、認可が必要か」を
 * 決めるのは製品判断を伴うため一度には片付かない。一方で、その間にも新しい無認可エンドポイントが
 * 増え続けると差は開く一方になる。
 *
 * <p>そこで<b>ラチェット</b>を掛ける。現時点の無認可エンドポイントを許可リストとして固定し、
 *
 * <ul>
 *   <li>許可リストに<b>無い</b>無認可エンドポイントが現れたら失敗する(新規の付け忘れを検知)</li>
 *   <li>許可リストにあるのに<b>もう無認可でない</b>ものがあれば失敗する(解消したら
 *       リストから消させ、リストが実態から乖離しないようにする)</li>
 * </ul>
 *
 * <p>これは #824(web の Server Action)と同型の問題に対する、バックエンド側の再発防止である。
 * 先例は同パッケージの {@link AuthorizationMatrixContract}(認証ゲートの後退検知)。
 *
 * <p><b>この契約テストは「認可が正しいか」を判定しない。</b>認可呼び出しが<b>書かれているか</b>
 * だけを見る。呼んでいる認可が適切かどうかはレビューの仕事である。
 *
 * <p><b>既知の限界(issue #1057): 委譲先サービスの判定はファイル単位で、メソッド単位ではない。</b>
 * {@code authorizingServicesIn} はコントローラが委譲するサービスの<b>ファイル全体</b>を走査し、
 * {@link #AUTHORIZATION_CALL}が<b>どこかに1箇所でも</b>あれば、そのファイルが持つ
 * <b>すべての</b>コントローラメソッドの委譲を「認可あり」とみなす。実際にどのメソッドが
 * その呼び出しを持つかは見ない。そのため、同じサービスクラス内で一部のメソッド
 * (例: {@code create}/{@code update}/{@code delete})だけが{@code requireAdmin()}を呼び、
 * 別のメソッド(例: {@code list}/{@code buildCssBundle})が呼んでいなくても、後者は
 * 誤って「認可あり」と判定され、許可リストにも載らないまま見過ごされる。
 * {@code CustomTagService}がまさにこの形で、{@code list}/{@code buildCssBundle}に
 * プロジェクトメンバー判定が無いまま(#1057で追加)このコントラクトを素通りしていた。
 * メソッド単位の判定への書き換えは本クラスの責務を超えるため対象外とし、別Issueで扱う。
 */
public final class AuthorizationCoverageContract {

    /** 認可チェックとみなす呼び出し。サービスごとに名前が揃っている(legacy-apiからの移設のため)。 */
    private static final Pattern AUTHORIZATION_CALL = Pattern.compile(
            "\\b(requireAdmin|requireSelfOrAdmin|requireAdminAndNotSelf|requirePermission"
                    + "|requireProjectMemberOrAdmin|requireNotSelfDemotion|requireAuthenticated"
                    + "|requireActorId|requireCurrentActorId)"
                    // 対象を絞った派生(例: requireProjectMemberOrAdminForSite)も認可呼び出しとして数える。
                    // 接尾辞を許さないと、派生名を作った瞬間に「認可が無い」と誤判定される(issue #830)。
                    // ここに列挙した名前で始まるものだけが対象なので、Objects.requireNonNull は拾わない。
                    + "[A-Za-z]*\\s*\\(");

    /**
     * 認可を意図的に付けない場合に、その理由とともに置くマーカー。
     * これがメソッドのJavadoc/コメントにあれば「判断済み」とみなし、許可リストに載せなくてよい。
     */
    private static final Pattern INTENTIONAL_MARKER = Pattern.compile("認可不要:");

    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping");

    private AuthorizationCoverageContract() {
    }

    /** {@code <Controller>#<method>} 形式の識別子。許可リストのキーになる。 */
    public record Unauthorized(String controller, String method) {
        @Override
        public String toString() {
            return controller + "#" + method;
        }
    }

    /**
     * @param serviceModule {@code services/} 配下のディレクトリ名(例: {@code "publishing"})
     * @param allowed       現時点で認可チェックを持たないと分かっているもの。
     *                      {@code "PostController#publish"} の形式で列挙する
     */
    public static void verifyNoNewUnauthorizedEndpoints(String serviceModule, Set<String> allowed) {
        compare(serviceModule, currentUnauthorized(serviceModule), allowed);
    }

    /**
     * 実際の走査結果と許可リストを突き合わせる本体。
     *
     * <p>走査から切り離してあるのは、<b>ラチェット自身の挙動を実サービスの状態に依存せず</b>
     * 検証できるようにするため(issue #830)。すべてのサービスで無認可がゼロになると、
     * 「現状から1件取り除いて失敗を確かめる」形のテストは材料が無くなって成立しない。
     */
    static void compare(String serviceModule, Set<String> actual, Set<String> allowed) {
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(allowed);

        Set<String> resolved = new TreeSet<>(allowed);
        resolved.removeAll(actual);

        if (added.isEmpty() && resolved.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder(serviceModule)
                .append(": 認可チェックを持たないエンドポイントの一覧が許可リストと一致しません。\n");
        if (!added.isEmpty()) {
            message.append("\n【新たに認可チェックの無いエンドポイントが増えました(issue #830)】\n");
            added.forEach(s -> message.append("  - ").append(s).append('\n'));
            message.append("\n認可(requireAdmin/requireProjectMemberOrAdmin等)を追加するか、"
                    + "意図的に認証のみでよいなら\nそのメソッドのコメントへ理由を "
                    + "「認可不要: <理由>」の形で書いてください。\n");
        }
        if (!resolved.isEmpty()) {
            message.append("\n【許可リストにあるが、もう認可チェックが無い状態ではありません】\n");
            resolved.forEach(s -> message.append("  - ").append(s).append('\n'));
            message.append("\n解消済みです。テストの許可リストから削除してください"
                    + "(リストが実態から乖離しないため)。\n");
        }
        throw new AssertionError(message.toString());
    }

    static List<Unauthorized> scanUnauthorized(Path sourceRoot) {
        List<Unauthorized> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .sorted()
                    .forEach(p -> result.addAll(scanFile(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static List<Unauthorized> scanFile(Path file) {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String controller = file.getFileName().toString().replace(".java", "");
        List<Unauthorized> result = new ArrayList<>();

        // 内部ブリッジ(/api/internal/**)はサービス間呼び出し専用で、gatewayからは到達しない。
        // 認可はトークンを転送する呼び出し元が担うため、ここでは対象にしない。
        if (source.contains("/api/internal/")) {
            return result;
        }

        // 委譲先のサービスが認可している場合も「認可あり」とみなす(issue #830)。
        // 例: legacy-api の ProjectApiKeyController は素通しに見えるが、
        // ProjectApiKeyService が全 public メソッドで requireProjectMemberOrAdmin を呼んでいる。
        // コントローラだけを見ると偽陽性になる。
        Set<String> authorizingDelegates = authorizingServicesIn(file);

        // 同じコントローラ内の private ヘルパが認可している場合も「認可あり」とみなす(issue #830)。
        // 「IDで引いて、その所属プロジェクトのメンバーか確かめる」処理はハンドラ間で共通化するのが
        // 自然で(media の DiagramController#findAuthorized 等)、ヘルパを見ないと
        // 実際には守られているエンドポイントを未認可と誤判定する。
        Set<String> authorizingHelpers = authorizingHelpersIn(source);

        Matcher mapping = MAPPING.matcher(source);
        List<Integer> starts = new ArrayList<>();
        while (mapping.find()) {
            starts.add(mapping.start());
        }
        // ブロックの先頭は @XxxMapping そのものではなく、その直前に置かれた Javadoc/コメントまで
        // 遡る(issue #830)。マーカーは注釈の上に書くのが自然だが、単純に @Mapping 区切りで
        // 分割すると、そのコメントが「直前のエンドポイントのブロック末尾」に入ってしまい、
        // 除外理由が1つ手前のメソッドに帰属する。認可の除外が別のメソッドへずれて効くのは
        // セキュリティ上そのままにできない誤りなので、境界のほうを直す。
        List<Integer> blockStarts = new ArrayList<>();
        for (int start : starts) {
            blockStarts.add(docCommentStart(source, start));
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = blockStarts.get(i);
            int to = i + 1 < starts.size() ? blockStarts.get(i + 1) : source.length();
            String block = source.substring(from, to);
            if (AUTHORIZATION_CALL.matcher(block).find() || INTENTIONAL_MARKER.matcher(block).find()) {
                continue;
            }
            if (delegatesToAuthorizingService(block, authorizingDelegates)) {
                continue;
            }
            if (callsAuthorizingHelper(block, authorizingHelpers)) {
                continue;
            }
            String method = methodNameOf(block);
            if (method != null) {
                result.add(new Unauthorized(controller, method));
            }
        }
        return result;
    }

    /**
     * 同じサービスモジュールの {@code service/} 配下で、認可呼び出しを含むクラスの
     * フィールド名候補(先頭小文字のクラス名)を集める。
     *
     * <p>認可をサービス層に置く実装は珍しくない(legacy-api の {@code ProjectApiKeyService} は
     * 全 public メソッドで {@code requireProjectMemberOrAdmin} を呼ぶ)。コントローラの
     * メソッド本体だけを見ると、そうしたエンドポイントを「認可なし」と誤判定する。
     *
     * <p>これは呼び出しグラフを追う代わりの近似である。「認可を呼ぶサービスへ委譲していれば
     * 認可済みとみなす」ため、そのサービスの<b>一部の</b>メソッドだけが認可している場合は
     * 見逃しうる。ラチェット(増やさないこと)の用途にはこの精度で足りると判断した。
     */

    /**
     * {@code index} の直前にある Javadoc / 行コメントの開始位置を返す(無ければ {@code index} のまま)。
     * 空白・改行だけを挟んで連続するコメント行はすべて取り込む。
     */
    private static int docCommentStart(String source, int index) {
        int cursor = index;
        while (true) {
            int scan = cursor - 1;
            while (scan >= 0 && Character.isWhitespace(source.charAt(scan))) {
                scan--;
            }
            if (scan < 1) {
                return cursor;
            }
            if (source.charAt(scan) == '/' && source.charAt(scan - 1) == '*') {
                int open = blockCommentOpen(source, scan - 1);
                if (open < 0) {
                    return cursor;
                }
                cursor = open;
                continue;
            }
            int lineStart = source.lastIndexOf('\n', scan) + 1;
            String line = source.substring(lineStart, scan + 1).trim();
            if (line.startsWith("//")) {
                cursor = lineStart;
                continue;
            }
            return cursor;
        }
    }


    /**
     * {@code from} 以前にある「行頭の」ブロックコメント開始 {@code /*} を返す(無ければ -1)。
     *
     * <p>単純な {@code lastIndexOf("/*")} では、コメント本文に含まれるパス(例: {@code /api/render/**})
     * の中の {@code /*} を拾ってしまい、コメントの途中を開始位置と誤認する。そうなると
     * 「認可不要:」マーカーがブロックの外へ落ち、除外が効かない(issue #830)。
     */
    private static int blockCommentOpen(String source, int from) {
        int search = from;
        while (search >= 0) {
            int open = source.lastIndexOf("/*", search);
            if (open < 0) {
                return -1;
            }
            int lineStart = source.lastIndexOf('\n', open) + 1;
            if (source.substring(lineStart, open).isBlank()) {
                return open;
            }
            search = open - 1;
        }
        return -1;
    }

    private static Set<String> authorizingServicesIn(Path controllerFile) {
        Path serviceDir = controllerFile.getParent().getParent().resolve("service");
        Set<String> names = new LinkedHashSet<>();
        if (!Files.isDirectory(serviceDir)) {
            return names;
        }
        try (Stream<Path> files = Files.list(serviceDir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String body = Files.readString(p, StandardCharsets.UTF_8);
                if (!AUTHORIZATION_CALL.matcher(body).find()) {
                    continue;
                }
                String cls = p.getFileName().toString().replace(".java", "");
                names.add(Character.toLowerCase(cls.charAt(0)) + cls.substring(1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    /** ハンドラ本体が、認可を行うサービスのメソッドを呼んでいるか。 */
    private static boolean delegatesToAuthorizingService(String block, Set<String> authorizingDelegates) {
        for (String field : authorizingDelegates) {
            if (block.contains(field + ".")) {
                return true;
            }
        }
        return false;
    }


    /**
     * 同じファイル内で定義されている private メソッドのうち、本体に認可呼び出しを含むものの名前を返す。
     *
     * <p>{@link #authorizingServicesIn} のコントローラ内版。ハンドラが直接 {@code requireXxx} を
     * 呼ばず、共通ヘルパへ切り出しているケースを拾う(issue #830)。
     */
    private static Set<String> authorizingHelpersIn(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher declaration = Pattern.compile(
                "\\bprivate\\s+(?:static\\s+)?[\\w<>\\[\\],.?\\s]+?\\b(\\w+)\\s*\\([^)]*\\)\\s*\\{").matcher(source);
        while (declaration.find()) {
            int bodyStart = source.indexOf('{', declaration.end() - 1);
            int end = matchingBrace(source, bodyStart);
            if (end < 0) {
                continue;
            }
            String body = source.substring(bodyStart, end);
            if (AUTHORIZATION_CALL.matcher(body).find()) {
                names.add(declaration.group(1));
            }
        }
        return names;
    }

    /** {@code open} 位置の {@code &#123;} に対応する閉じ括弧の位置を返す(見つからなければ -1)。 */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** ブロックが、認可を行う同一クラス内のヘルパを呼んでいるか。 */
    private static boolean callsAuthorizingHelper(String block, Set<String> authorizingHelpers) {
        for (String name : authorizingHelpers) {
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(block).find()) {
                return true;
            }
        }
        return false;
    }

    /** マッピング注釈の直後にあるハンドラメソッド名を取り出す。 */
    private static String methodNameOf(String block) {
        Matcher m = Pattern.compile(
                "(?:public|protected|private)\\s+[^;{]*?\\b(\\w+)\\s*\\(", Pattern.DOTALL).matcher(block);
        return m.find() ? m.group(1) : null;
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("リポジトリルート(settings.gradleのある場所)を特定できませんでした");
        }
        return current;
    }

    /** 許可リストを作るときの補助。現状の一覧をコピーしやすい形で出力する。 */
    public static Set<String> currentUnauthorized(String serviceModule) {
        Path serviceRoot = findRepoRoot().resolve("services").resolve(serviceModule);
        Set<String> result = new LinkedHashSet<>();
        scanUnauthorized(serviceRoot.resolve("src/main/java")).forEach(u -> result.add(u.toString()));
        return result;
    }
}
