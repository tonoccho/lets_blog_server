package com.letsblog.gateway.config;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * gateway側のupload-endpointバケット判定({@link RateLimitWebFilter})と、
 * {@code apps/web/e2e/support/endpoints.ts#isUploadBucketPath}が同じ集合を判定していることを
 * 検証する(issue #999 受入基準4)。
 *
 * <p>両者は言語をまたいで独立に同じ定義を持つ(二重管理。issue #999 Background)。
 * {@link RouteControllerContractTest}がJavaのコントローラーソースを正規表現で走査して
 * application.ymlのルート表と突き合わせるのと同じ手口で、ここでは逆に
 * TypeScript側のソース({@code endpoints.ts})を正規表現で走査して定義を抽出し、
 * 実際に動かした{@link RateLimitWebFilter}の挙動(ブラックボックス。upload-endpointバケットを
 * 枯渇させてから対象パスが巻き添えで429になるかを見る)と突き合わせる。
 *
 * <p>{@code RateLimitWebFilter}側に新しいテスト専用のpublic/package-privateなAPIを
 * 追加していない。既存の{@link RateLimitWebFilterTest}と同じく、{@link RateLimitWebFilter#filter}
 * という本来の公開経路だけを使って判定結果を観測する。
 */
class RateLimitUploadBucketSyncTest {

    private static final Path ENDPOINTS_TS_RELATIVE_PATH =
            Paths.get("apps", "web", "e2e", "support", "endpoints.ts");

    /**
     * 判定の食い違いを検出するための候補パス。issue #999で明示的にupload-endpointへ残すと
     * 決めた4本(実装判断の理由はendpoints.tsのコメント参照)、issue #999で新たに
     * api-globalへ解放した画像関連メタデータ、および判定ロジックの境界を突く近似パス
     * (末尾一致・部分一致では誤って巻き込まれてしまうもの)を含む。
     */
    private static final List<String> CANDIDATE_PATHS = List.of(
            // upload-endpointに残す(実アップロード・実生成)
            "/api/media/upload",
            "/api/ai/image",
            "/api/projects/1/asset-images/2/upload",
            "/api/projects/1/bulk-management/upload",
            // #999で新たにapi-globalへ解放した画像関連メタデータ/設定
            "/api/ai/image-options",
            "/api/projects/1/image-settings",
            "/api/projects/1/image-generation-prompt-defaults",
            "/api/projects/1/image-generation-size-defaults",
            "/api/projects/1/article-image-resize-default",
            "/api/projects/1/image-content-filter-settings",
            "/api/projects/1/ai-models/image/provider",
            "/api/projects/1/ai-models/image/provider/selection",
            "/api/generated-images",
            "/api/generated-images/1",
            "/api/generated-images/1/tags",
            "/api/generated-images/1/file",
            "/api/projects/1/ai/generate-image-prompt",
            // 近似パス(部分一致・末尾一致だと誤ってupload-endpointに巻き込まれるもの)
            "/api/projects/1/bulk-management/uploads",
            "/api/ai/images",
            "/api/media/upload/extra",
            "/api/projects/1/asset-images/2/uploaded");

    @TestFactory
    Stream<DynamicTest> classificationMatchesEndpointsTs() throws IOException {
        UploadBucketDefinition tsDefinition = parseEndpointsTs();

        return CANDIDATE_PATHS.stream().map(path -> dynamicTest(path, () -> {
            boolean expectedByTs = tsDefinition.isUploadBucketPath(path);
            boolean actualInGateway = actuallyUsesUploadBucket(path);
            assertEquals(expectedByTs, actualInGateway, () -> String.format(
                    "%s の判定がgateway(RateLimitWebFilter)とendpoints.ts#isUploadBucketPathで"
                            + "食い違っています(endpoints.ts側の期待=%s)。"
                            + "両者は同じ集合を持つ必要があります(issue #999 受入基準4)。",
                    path, expectedByTs));
        }));
    }

    /**
     * upload-endpointバケットを既知のアップロードパス({@code /api/media/upload})1回だけで
     * 枯渇させ(上限1)、対象パスへの要求が巻き添えで429になるかどうかで
     * 「そのパスがupload-endpointバケットを使っているか」をブラックボックスに観測する。
     */
    private boolean actuallyUsesUploadBucket(String path) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setUploadEndpoint(new RateLimitProperties.Bucket(1, Duration.ofMinutes(1)));
        // upload-endpoint以外は十分な枠を与え、誤検出(他バケットの枯渇による429)を防ぐ。
        properties.setApiGlobal(new RateLimitProperties.Bucket(1000, Duration.ofMinutes(1)));
        properties.setApiInternal(new RateLimitProperties.Bucket(1000, Duration.ofMinutes(1)));
        RateLimitWebFilter filter = new RateLimitWebFilter(properties);
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchangeFor("/api/media/upload"), chain)).verifyComplete();

        ServerWebExchange exchange = exchangeFor(path);
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        return exchange.getResponse().getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
    }

    private ServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    // ------------------------------------------------------------------
    // endpoints.ts の走査(コンパイル不要の正規表現ベース。RouteControllerContractTestと同じ手口)
    // ------------------------------------------------------------------

    private record UploadBucketDefinition(
            List<String> exactPaths, Pattern assetImagePattern, Pattern bulkManagementPattern) {
        boolean isUploadBucketPath(String requestPath) {
            return exactPaths.contains(requestPath)
                    || assetImagePattern.matcher(requestPath).matches()
                    || bulkManagementPattern.matcher(requestPath).matches();
        }
    }

    private static UploadBucketDefinition parseEndpointsTs() throws IOException {
        Path repoRoot = findRepoRoot();
        Path endpointsTs = repoRoot.resolve(ENDPOINTS_TS_RELATIVE_PATH);
        String source = Files.readString(endpointsTs);

        List<String> exactPaths = extractExactPaths(source);
        Pattern assetImagePattern = extractPattern(source, "ASSET_IMAGE_UPLOAD_PATTERN");
        Pattern bulkManagementPattern = extractPattern(source, "BULK_MANAGEMENT_UPLOAD_PATTERN");
        return new UploadBucketDefinition(exactPaths, assetImagePattern, bulkManagementPattern);
    }

    private static List<String> extractExactPaths(String source) {
        Matcher arrayMatcher = Pattern.compile("UPLOAD_BUCKET_EXACT_PATHS\\s*=\\s*\\[([^\\]]*)]").matcher(source);
        if (!arrayMatcher.find()) {
            throw new IllegalStateException(
                    "endpoints.ts にUPLOAD_BUCKET_EXACT_PATHSが見つかりません。走査規約が"
                            + "実装のスタイルと食い違っている可能性があります。");
        }
        List<String> paths = new ArrayList<>();
        Matcher stringMatcher = Pattern.compile("'([^']*)'").matcher(arrayMatcher.group(1));
        while (stringMatcher.find()) {
            paths.add(stringMatcher.group(1));
        }
        if (paths.isEmpty()) {
            throw new IllegalStateException("UPLOAD_BUCKET_EXACT_PATHSの中身を抽出できませんでした。");
        }
        return paths;
    }

    private static Pattern extractPattern(String source, String constantName) {
        Matcher matcher = Pattern.compile(constantName + "\\s*=\\s*new RegExp\\('([^']*)'\\)").matcher(source);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "endpoints.ts に" + constantName + "が見つかりません。走査規約が実装のスタイルと"
                            + "食い違っている可能性があります。");
        }
        return Pattern.compile(matcher.group(1));
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
