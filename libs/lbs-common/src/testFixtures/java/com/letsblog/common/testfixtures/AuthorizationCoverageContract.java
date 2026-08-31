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
 */
public final class AuthorizationCoverageContract {

    /** 認可チェックとみなす呼び出し。サービスごとに名前が揃っている(legacy-apiからの移設のため)。 */
    private static final Pattern AUTHORIZATION_CALL = Pattern.compile(
            "\\b(requireAdmin|requireSelfOrAdmin|requireAdminAndNotSelf|requirePermission"
                    + "|requireProjectMemberOrAdmin|requireNotSelfDemotion|requireAuthenticated"
                    + "|requireActorId|requireCurrentActorId)\\s*\\(");

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
        Path serviceRoot = findRepoRoot().resolve("services").resolve(serviceModule);
        List<Unauthorized> found = scanUnauthorized(serviceRoot.resolve("src/main/java"));

        Set<String> actual = new TreeSet<>();
        found.forEach(u -> actual.add(u.toString()));

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

        Matcher mapping = MAPPING.matcher(source);
        List<Integer> starts = new ArrayList<>();
        while (mapping.find()) {
            starts.add(mapping.start());
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
            String block = source.substring(from, to);
            if (AUTHORIZATION_CALL.matcher(block).find() || INTENTIONAL_MARKER.matcher(block).find()) {
                continue;
            }
            String method = methodNameOf(block);
            if (method != null) {
                result.add(new Unauthorized(controller, method));
            }
        }
        return result;
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
