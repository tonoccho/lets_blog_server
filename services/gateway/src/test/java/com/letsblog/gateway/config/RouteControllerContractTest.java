package com.letsblog.gateway.config;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * {@code services/gateway/src/main/resources/application.yml}のルート表が、実際に移設済みの
 * 各下流サービスのコントローラーの{@code @RequestMapping}/{@code @GetMapping}等の実パスと
 * 食い違っていないかを自動検出するコントラクトテスト(issue #642)。
 *
 * <p><b>背景</b>: このクラスのルート表とコントローラー実パスの不一致は、これまでに
 * サービス抽出のたびに手動で発見されてきた(#573のGeneratedImageController
 * {@code /api/generated-images/**}、#577のSshKeyPairController
 * {@code /api/ssh-key-pairs}等。application.ymlの各所のコメント参照)。レビューでの
 * 手動発見に頼らず、テストとして自動的に検知できるようにする。
 *
 * <p><b>アプローチ</b>: gatewayモジュールは{@code packages:lbs-common}以外の他サービスモジュールへの
 * コンパイル時依存を持たない(services/gateway/build.gradle参照)。7つの抽出済みサービス
 * すべてへ{@code testImplementation project(':services:xxx')}を追加してリフレクションで
 * 検証する方法も検討したが、(1)gatewayが本来担うべきでない大量のコンパイル時結合を
 * 生む、(2)将来サービスが増えるたびに依存を追加し続ける必要がある、(3)各サービスの
 * Spring MVCクラスパス構成(WebFluxかWebMVCか等)がgateway側のテストクラスパスと
 * 衝突するリスクがある、という理由で採用しなかった。
 *
 * <p>代わりに、各サービスのコントローラーソース({@code .java}ファイル)を軽量な正規表現で
 * 走査し、{@code @RequestMapping}(クラスレベル)・{@code @GetMapping}等(メソッドレベル)の
 * パス文字列を抽出する。コンパイル・クラスパス結合が一切不要で、かつ実際の
 * {@code @RequestMapping}の値そのもの(このリポジトリの一次情報)を見るため、
 * OpenAPI定義など二次生成物を経由するより実パスとの乖離が起きにくい。
 *
 * <p>抽出した各エンドポイントの実パスについて、{@link ProxyHandler#resolveRoute(String)}と
 * 全く同じロジック(privateではなくpackage-privateにして直接呼び出している。
 * ProxyHandlerRoutingTestのクラスJavadoc参照)でルート表を評価し、勝ったルートの
 * {@code uri}が、そのコントローラーが属するサービスの環境変数プレースホルダ
 * (例: {@code MEDIA_SERVICE_URI})を指しているかを検証する。
 *
 * <p><b>issue #583でフォールバックを廃止した</b>。以前はどのルートにもマッチしないパスを
 * 暗黙にlegacy-apiへ流していたため、「ルート表に載っていない」ことが表面化しなかった。
 * 現在はマッチしなければgatewayが404を返すので、このテストでも「どのルートにもマッチしない」を
 * 契約違反として扱う。
 *
 * <p>#771では、legacy-apiに残ったまま{@code /api/projects/**}配下にあったエンドポイントが
 * 移設済みサービス向けの広いルート(特に{@code project}ルート)へ先勝ちでマッチし、
 * そのサービスに存在しないパスとして404になっていた
 * ({@code POST /api/projects/{projectId}/ai/generate-image-prompt}と ProjectController の
 * 5エンドポイント)。#583でこれらは所有サービスへ移設し、専用ルートを持つようになっている。
 *
 * <p><b>スキャン対象外</b>: gateway自身、および各コントローラーの
 * {@code /api/internal/**}配下のエンドポイント(サービス間の内部ブリッジ専用で、
 * gatewayを経由しない。各internalXxxControllerの実装参照)。
 *
 * <p>{@link #NON_GATEWAY_ROUTED_PATHS}も参照。{@code /api/internal/**}の命名規則に従わない、
 * gateway非経由のサービス間直接呼び出しエンドポイントが1件だけ存在するため個別に除外している。
 *
 * <p><b>修正済みの実バグ(issue #642実装時に本テストで新規発見、同ブランチで修正済み)</b>:
 * ArticlePlanController(ai-service、実パスは{@code /api/projects/{projectId}/article-plan/**})
 * は、application.ymlのルート表に対応するエントリが無く、より先に評価される{@code project}ルート
 * ({@code /api/projects/**}、PROJECT_SERVICE_URI宛)に先勝ちでマッチし、ai-serviceではなく
 * project-serviceへ誤ってルーティングされていた(apps/web/src/lib/apiClient.tsが実際に
 * {@code /api/projects/{projectId}/article-plan/...}を呼ぶため、記事プラン機能がgateway経由で
 * 到達不能になっていた)。{@code project-ai-models-llm}と同じパターンで
 * {@code project-article-plan}ルート({@code /api/projects/*&#47;article-plan/**} →
 * AI_SERVICE_URI)を広い{@code project}ルートより前に追加して修正した(issue #659に記録)。
 */
class RouteControllerContractTest {

    private static final String INTERNAL_PATH_PREFIX = "/api/internal/";

    /**
     * {@code /api/internal/**}の命名規則には従わないが、実際にはgatewayを経由しない
     * (コンテナ間で直接呼び出される)ことがJavadocで明示されているエンドポイント。
     *
     * <p>ComfyUiCheckpointController(media-service)は、#583以前はlegacy-apiの
     * ComfyUiModelServiceからdocker network越しに直接呼ばれる設計だった(同クラスのJavadoc
     * 「gatewayは経由しない」参照)。#583でComfyUiModelServiceがmedia-service自身へ移り、
     * 同一サービス内の直接呼び出しになったため、この2本は外部から到達する必要がそもそも無い。
     * gatewayのルート表に載っていないのは引き続き意図通り(バグではない)。
     */
    private static final List<String> NON_GATEWAY_ROUTED_PATHS = List.of(
            "/api/comfyui/checkpoints/install",
            "/api/comfyui/checkpoints/delete",
            // RenderController(media-service)。content-service/publishing-serviceの
            // MediaRenderClientがapp.media-service-uriへコンテナ間で直接呼ぶ経路しか無く、
            // web/extensionからの利用は無い。issue #830でgatewayのルート表から外した。
            "/api/render/plantuml",
            "/api/render/recharts",
            "/api/render/penpot/design-file");

    /**
     * サービスモジュール名(services/配下のディレクトリ名) -> application.ymlでそのサービスを
     * 指す環境変数プレースホルダ名。gatewayは含めない(クラスJavadoc参照)。
     */
    private static final Map<String, String> SERVICE_MODULE_TO_ENV_VAR = new LinkedHashMap<>();

    static {
        SERVICE_MODULE_TO_ENV_VAR.put("identity", "IDENTITY_SERVICE_URI");
        SERVICE_MODULE_TO_ENV_VAR.put("media", "MEDIA_SERVICE_URI");
        SERVICE_MODULE_TO_ENV_VAR.put("ai", "AI_SERVICE_URI");
        SERVICE_MODULE_TO_ENV_VAR.put("content", "CONTENT_SERVICE_URI");
        SERVICE_MODULE_TO_ENV_VAR.put("analytics", "ANALYTICS_SERVICE_URI");
        SERVICE_MODULE_TO_ENV_VAR.put("project", "PROJECT_SERVICE_URI");
        SERVICE_MODULE_TO_ENV_VAR.put("log-writer", "LOG_SERVICE_URI");
        // 公開パイプライン(PostController、issue #707)・一括管理/環境間比較・taxonomy解決
        // (BulkManagementController/TaxonomyController、issue #708)をpublishing-serviceが持つ。
        SERVICE_MODULE_TO_ENV_VAR.put("publishing", "PUBLISHING_SERVICE_URI");
        // VscodeExtensionController/SystemSettingController/AppSettingController/DashboardController/
        // BackupController(issue #693/#695/#696、C10)をplatform-serviceが持つ。
        // 新設時(#698)にこのマップへ追加し漏れていた(issue #716)。
        SERVICE_MODULE_TO_ENV_VAR.put("platform", "PLATFORM_SERVICE_URI");
    }

    /** クラス宣言行(トップレベルの public class)を検出する。 */
    private static final Pattern CLASS_DECLARATION =
            Pattern.compile("^\\s*(?:public\\s+)?class\\s+\\w+Controller\\b");

    /**
     * {@code @GetMapping}/{@code @PostMapping}/{@code @PutMapping}/{@code @DeleteMapping}/
     * {@code @PatchMapping}/{@code @RequestMapping}のパス文字列を抽出する。
     * {@code @GetMapping("/foo")}・{@code @GetMapping(value = "/foo", ...)}・
     * 値の無い{@code @GetMapping}(親のパスをそのまま使う)のいずれにも対応する。
     */
    private static final Pattern MAPPING_ANNOTATION = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping"
                    + "(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\")?");

    private record ControllerEndpoint(
            String serviceModule, String expectedEnvVar, String controllerClass, String path) {
    }

    @TestFactory
    Stream<DynamicTest> everyDownstreamControllerEndpointRoutesToItsOwnService() throws IOException {
        Path repoRoot = findRepoRoot();
        RouteProperties routeProperties = loadRouteProperties(repoRoot);
        ProxyHandler proxyHandler = new ProxyHandler(WebClient.builder().build(), routeProperties);

        List<ControllerEndpoint> endpoints = new ArrayList<>();
        for (Map.Entry<String, String> entry : SERVICE_MODULE_TO_ENV_VAR.entrySet()) {
            endpoints.addAll(scanControllerEndpoints(repoRoot, entry.getKey(), entry.getValue()));
        }

        assertFalse(endpoints.isEmpty(),
                "コントローラーのスキャン結果が空です。scanControllerEndpointsのパス解決・"
                        + "正規表現を見直してください(リポジトリ構成が変わった可能性があります)。");

        return endpoints.stream()
                .map(endpoint -> dynamicTest(
                        endpoint.serviceModule() + ": " + endpoint.controllerClass() + " " + endpoint.path(),
                        () -> assertRoutesToExpectedService(proxyHandler, routeProperties, endpoint)));
    }

    /**
     * 逆方向の検証: <b>ルート表の各パスが、転送先サービスの実コントローラで実際に処理される</b>こと
     * (issue #913)。
     *
     * <p>{@link #everyDownstreamControllerEndpointRoutesToItsOwnService()} は
     * 「コントローラ → ルート」しか見ておらず、<b>行き先にハンドラが無いルート</b>を検出できなかった。
     * 実際 #583 で {@code /api/projects/*&#47;css-selector-prefix} を content-service へ向けたのに
     * 受け口のコントローラを作り忘れ、**gateway は正しく転送するが content が404を返す**状態を
     * 作ってしまった(#913 で発見)。#583 でフォールバックを廃止して「ルートが無ければ404」に
     * したのと同じ理由で、「ルートはあるがハンドラが無い」も黙って通してはいけない。
     *
     * <p>パスパターン({@code /api/projects/*&#47;users/**} 等)とコントローラの
     * {@code @RequestMapping}(パス変数を含む)を直接比較できないため、
     * 双方を「セグメント数とワイルドカード位置を無視した比較可能な形」へ正規化して突き合わせる。
     */
    @TestFactory
    Stream<DynamicTest> everyRoutePathIsServedByAControllerInItsTargetService() throws IOException {
        Path repoRoot = findRepoRoot();
        RouteProperties routeProperties = loadRouteProperties(repoRoot);

        Map<String, List<ControllerEndpoint>> byEnvVar = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : SERVICE_MODULE_TO_ENV_VAR.entrySet()) {
            byEnvVar.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .addAll(scanControllerEndpoints(repoRoot, entry.getKey(), entry.getValue()));
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (RouteProperties.Route route : routeProperties.getRoutes()) {
            String envVar = envVarOf(route.getUri());
            for (String pattern : route.getPaths()) {
                tests.add(dynamicTest(
                        "route '" + route.getId() + "' " + pattern,
                        () -> assertPatternIsServed(route, pattern, envVar, byEnvVar.get(envVar))));
            }
        }
        return tests.stream();
    }

    /** {@code ${MEDIA_SERVICE_URI:http://media:8080}} から {@code MEDIA_SERVICE_URI} を取り出す。 */
    private static String envVarOf(String uri) {
        Matcher m = Pattern.compile("\\$\\{([A-Z_]+)").matcher(uri == null ? "" : uri);
        return m.find() ? m.group(1) : null;
    }

    private void assertPatternIsServed(
            RouteProperties.Route route, String pattern, String envVar, List<ControllerEndpoint> candidates) {
        assertNotNull(envVar, () -> "route '" + route.getId() + "' のuriが環境変数プレースホルダの形式ではありません: "
                + route.getUri());
        assertNotNull(candidates, () -> "route '" + route.getId() + "' の転送先 " + envVar
                + " に対応するサービスがSERVICE_MODULE_TO_ENV_VARにありません。");

        String normalizedPattern = normalize(pattern);
        boolean served = candidates.stream().anyMatch(e -> normalize(e.path()).equals(normalizedPattern)
                || normalizedPattern.endsWith("/**") && normalize(e.path()).startsWith(
                        normalizedPattern.substring(0, normalizedPattern.length() - 3)));

        assertTrue(served, () -> String.format(
                "route '%s' のパス %s は %s へ転送されますが、そのサービスに対応するコントローラが"
                        + "ありません。gatewayは正しく転送しますが、転送先が404を返します"
                        + "(issue #913 で /api/projects/*/css-selector-prefix がこの状態でした)。%n"
                        + "ルート表(services/gateway/src/main/resources/application.yml)から%n"
                        + "このパスを削除するか、転送先サービスにコントローラを追加してください。",
                route.getId(), pattern, envVar));
    }

    /**
     * パスパターンとコントローラの実パスを比較可能な形へ正規化する。
     * パス変数({@code {id}})もワイルドカード1セグメント({@code *})も、同じ {@code *} に潰す。
     */
    private static String normalize(String path) {
        return path.replaceAll("\\{[^/}]+}", "*");
    }

    private void assertRoutesToExpectedService(
            ProxyHandler proxyHandler, RouteProperties routeProperties, ControllerEndpoint endpoint) {
        // {id}等のパス変数は、AntPathMatcherが解釈できる具体的な1セグメントの値に置き換える。
        String samplePath = endpoint.path().replaceAll("\\{[^/}]+}", "sample-value");

        RouteProperties.Route matched = proxyHandler.resolveRoute(samplePath);
        // issue #583でフォールバックを廃止した。マッチしなければgatewayが404を返すので、
        // 「どのルートにもマッチしない」は転送先が無い=契約違反として扱う。
        String matchedUri = matched != null ? matched.getUri() : null;
        String matchedDescription = matched != null
                ? "route '" + matched.getId() + "' (uri=" + matchedUri + ")"
                : "どのルートにもマッチせず404";

        boolean ok = matchedUri != null && matchedUri.contains(endpoint.expectedEnvVar());
        assertTrue(ok, () -> String.format(
                "%s(services/%s)の実パス %s (検証用に具体化: %s) は、"
                        + "application.ymlのルート表では %s になっていますが、"
                        + "本来%sへ転送されるべきです。"
                        + "ルート表(services/gateway/src/main/resources/application.yml)に、"
                        + "この実パスをカバーする専用ルートを、より広いパターンのルートより"
                        + "先に追加/修正してください。",
                endpoint.controllerClass(), endpoint.serviceModule(), endpoint.path(), samplePath,
                matchedDescription, endpoint.expectedEnvVar()));
    }

    // ------------------------------------------------------------------
    // ルート表の読み込み(実際にSpring Bootが@ConfigurationPropertiesで束縛するのと
    // 同じBinder/YamlPropertySourceLoaderを使う。手組みのYAMLパーサーによる実装の
    // 乖離を避けるため)。
    // ------------------------------------------------------------------

    private static RouteProperties loadRouteProperties(Path repoRoot) throws IOException {
        Path ymlPath = repoRoot.resolve("services/gateway/src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(ymlPath), () -> "application.ymlが見つかりません: " + ymlPath);

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> propertySources = loader.load("application", new FileSystemResource(ymlPath));
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
        return binder.bind("app.gateway", RouteProperties.class)
                .orElseThrow(() -> new IllegalStateException("app.gatewayの束縛に失敗しました: " + ymlPath));
    }

    // ------------------------------------------------------------------
    // コントローラーソースの走査(コンパイル・リフレクション不要の正規表現ベース)
    // ------------------------------------------------------------------

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

    private static List<ControllerEndpoint> scanControllerEndpoints(
            Path repoRoot, String serviceModule, String expectedEnvVar) throws IOException {
        Path controllerRoot = repoRoot.resolve("services").resolve(serviceModule).resolve("src/main/java");
        if (!Files.isDirectory(controllerRoot)) {
            throw new IllegalStateException("コントローラーディレクトリが見つかりません: " + controllerRoot);
        }

        List<Path> controllerFiles;
        try (Stream<Path> walk = Files.walk(controllerRoot)) {
            controllerFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .collect(Collectors.toList());
        }

        List<ControllerEndpoint> endpoints = new ArrayList<>();
        for (Path file : controllerFiles) {
            endpoints.addAll(scanControllerFile(file, serviceModule, expectedEnvVar));
        }
        return endpoints;
    }

    private static List<ControllerEndpoint> scanControllerFile(
            Path file, String serviceModule, String expectedEnvVar) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException("読み込み失敗: " + file, e);
        }

        String controllerClass = file.getFileName().toString().replace(".java", "");
        ClassLevelMapping classLevel = findClassLevelRequestMapping(lines);
        String classLevelPath = classLevel != null ? classLevel.path() : null;
        int classLevelLineIndex = classLevel != null ? classLevel.lineIndex() : -1;

        List<ControllerEndpoint> endpoints = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            if (lineIndex == classLevelLineIndex) {
                // クラスレベルの@RequestMapping自体はclassLevelPathとして既に使っているため、
                // ここでメソッドレベルのマッピングとして二重に扱わないよう除外する。
                continue;
            }
            String line = lines.get(lineIndex);
            Matcher matcher = MAPPING_ANNOTATION.matcher(line);
            while (matcher.find()) {
                String rawPath = matcher.group(2);
                if (rawPath == null) {
                    // 値の無いアノテーション(例: 素の@GetMapping)はクラスレベルのパスをそのまま使う。
                    if (classLevelPath == null) {
                        continue;
                    }
                    rawPath = "";
                }
                String fullPath = joinPaths(classLevelPath, rawPath);
                if (fullPath.startsWith(INTERNAL_PATH_PREFIX) || NON_GATEWAY_ROUTED_PATHS.contains(fullPath)) {
                    // サービス間の内部ブリッジ専用エンドポイント。gatewayを経由しないためスキップ。
                    continue;
                }
                endpoints.add(new ControllerEndpoint(serviceModule, expectedEnvVar, controllerClass, fullPath));
            }
        }
        return endpoints;
    }

    private record ClassLevelMapping(String path, int lineIndex) {
    }

    /**
     * クラスレベルの{@code @RequestMapping}を検出する。このリポジトリのコントローラーは
     * 一貫して{@code @RequestMapping("...")}をクラス宣言({@code class XxxController})の
     * 直前の行に書くスタイルのため、「クラス宣言行の直前の非空行」を見て、それが
     * {@code @RequestMapping("...")}であればクラスレベルのベースパスとして採用する
     * (完全なJavaパーサーではなく、このリポジトリの実際のスタイルに合わせた軽量な
     * ヒューリスティック。スタイルが変わった場合は本メソッドの見直しが必要)。
     */
    private static ClassLevelMapping findClassLevelRequestMapping(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (CLASS_DECLARATION.matcher(lines.get(i)).find()) {
                for (int j = i - 1; j >= 0; j--) {
                    String candidate = lines.get(j).strip();
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    Matcher matcher = MAPPING_ANNOTATION.matcher(candidate);
                    if (matcher.find() && "Request".equals(matcher.group(1)) && matcher.group(2) != null) {
                        return new ClassLevelMapping(matcher.group(2), j);
                    }
                    // 直前の非空行がRequestMapping以外(@RestController等)であれば、
                    // クラスレベルのRequestMappingは無いと判断する。
                    return null;
                }
                return null;
            }
        }
        return null;
    }

    private static String joinPaths(String base, String sub) {
        String basePath = base == null ? "" : base;
        String subPath = sub == null ? "" : sub;
        String combined;
        if (subPath.isEmpty()) {
            combined = basePath;
        } else if (subPath.startsWith("/")) {
            combined = basePath + subPath;
        } else {
            combined = basePath + "/" + subPath;
        }
        if (combined.isEmpty()) {
            combined = "/";
        }
        return combined.replaceAll("/{2,}", "/");
    }
}
