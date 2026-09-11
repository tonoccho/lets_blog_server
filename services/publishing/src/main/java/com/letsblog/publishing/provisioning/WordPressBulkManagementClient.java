package com.letsblog.publishing.provisioning;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.letsblog.common.util.StackTraceUtil;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 常駐wordpressコンテナ内の内部限定プロビジョニングエージェント(ポート9000)へ、
 * カテゴリ作成/編集/削除・プラグイン/テーマのインストール(SLUG指定・zipアップロード)/
 * 有効化/無効化/削除を依頼するクライアント。環境同期(WordPressSyncClient)と異なり
 * 「1環境の失敗が他環境の実行を止めない」という一括管理の方針に合わせ、
 * apply系メソッドは例外を投げず常に結果(BulkApplyResult)を返す。
 *
 * <p>legacy-apiの{@code com.letsblog.api.provisioning.WordPressBulkManagementClient}を
 * publishing-serviceへ移設したもの(issue #708、Epic #551 C6-2)。
 *
 * <p><b>タイムアウト(issue #1123)。</b>接続/リードタイムアウトを設定せずに{@link RestClient}を
 * 組み立てると、provision-agentが固着(issue #1122)して応答を返さなくなったとき、JDK
 * {@link HttpClient}は応答を無期限に待つ。この呼び出しは
 * {@code TermComparisonService}の{@code @Transactional(readOnly = true)}の内側で行われるため、
 * 待っているスレッドはJDBCコネクションを握ったままになり、HikariCPのプール(既定10)が
 * 10並行リクエストで枯渇し、publishing-service全体のDBアクセスが道連れで失敗する
 * (2026-09-06/07に実際に発生)。platform-serviceの{@code ConnectedServiceStatusService}が
 * 同じ依存先に対して既に行っている、{@code HttpClient.connectTimeout}+
 * {@code JdkClientHttpRequestFactory.setReadTimeout}によるタイムアウト設定と同じ手法を使う。
 *
 * <p>一覧取得系({@link #listCategories}/{@link #listTags}/{@link #listPlugins}/
 * {@link #listThemes})とapply系({@link #apply}/{@link #applyZip})とでは所要時間の桁が
 * 大きく異なる(zipアップロードは長時間処理になりうる)ため、タイムアウト値は用途別に
 * 分けて設定できるようにする(要件1)。値はapplication.yml経由で環境変数から上書きできる
 * (要件2)。タイムアウトによる打ち切りとエラー応答(HTTPエラーステータス)は、
 * {@link ResourceAccessException}(タイムアウト・接続不可)と
 * {@link RestClientResponseException}(エラー応答)とで別々に捕捉し、ログと
 * (apply系では){@link BulkApplyResult#errorMessage()}の文言を区別する(要件3)。
 */
@Component
@Slf4j
public class WordPressBulkManagementClient {

    private final RestClient listingClient;
    private final RestClient applyClient;
    private final String provisionToken;

    @Autowired
    public WordPressBulkManagementClient(
            @Value("${app.wordpress-provision-base-url}") String baseUrl,
            @Value("${app.wordpress-provision-token}") String provisionToken,
            @Value("${app.wordpress-provision-listing-timeout-seconds}") long listingTimeoutSeconds,
            @Value("${app.wordpress-provision-apply-timeout-seconds}") long applyTimeoutSeconds) {
        this(baseUrl, provisionToken,
                Duration.ofSeconds(listingTimeoutSeconds), Duration.ofSeconds(applyTimeoutSeconds));
    }

    /**
     * テスト専用: タイムアウト値を{@link Duration}で直接指定して検証するためのコンストラクタ
     * (LlmClientの同種のテスト専用コンストラクタと同じ位置付け)。
     */
    WordPressBulkManagementClient(
            String baseUrl, String provisionToken, Duration listingTimeout, Duration applyTimeout) {
        this.listingClient = buildClient(baseUrl, listingTimeout);
        this.applyClient = buildClient(baseUrl, applyTimeout);
        this.provisionToken = provisionToken;
    }

    private static RestClient buildClient(String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public BulkApplyResult apply(BulkApplyCommand command) {
        try {
            Map<String, String> body = applyClient.post()
                    .uri("/bulk-management")
                    .header("X-Provision-Token", provisionToken)
                    .body(command)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {
                    });
            return resultOf(body);
        } catch (ResourceAccessException e) {
            log.warn("provision-agentへの接続がタイムアウトしました (action={}): {}",
                    command.action(), e.getMessage());
            return BulkApplyResult.failed(new RestClientException(
                    "provision-agentへの接続がタイムアウトしました: " + e.getMessage(), e));
        } catch (RestClientResponseException e) {
            log.warn("provision-agentがエラー応答を返しました (action={}, status={})",
                    command.action(), e.getStatusCode());
            return BulkApplyResult.failed(new RestClientException(
                    "provision-agentがエラー応答を返しました (status=" + e.getStatusCode() + "): " + e.getMessage(), e));
        } catch (RestClientException e) {
            return BulkApplyResult.failed(e);
        }
    }

    public BulkApplyResult applyZip(String slug, String action, byte[] zipBytes, String filename) {
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("slug", slug);
            form.add("action", action);
            form.add("file", new ByteArrayResource(zipBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
            Map<String, String> body = applyClient.post()
                    .uri("/bulk-management/upload")
                    .header("X-Provision-Token", provisionToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {
                    });
            return resultOf(body);
        } catch (ResourceAccessException e) {
            log.warn("provision-agentへの接続がタイムアウトしました (action={}): {}", action, e.getMessage());
            return BulkApplyResult.failed(new RestClientException(
                    "provision-agentへの接続がタイムアウトしました: " + e.getMessage(), e));
        } catch (RestClientResponseException e) {
            log.warn("provision-agentがエラー応答を返しました (action={}, status={})", action, e.getStatusCode());
            return BulkApplyResult.failed(new RestClientException(
                    "provision-agentがエラー応答を返しました (status=" + e.getStatusCode() + "): " + e.getMessage(), e));
        } catch (RestClientException e) {
            return BulkApplyResult.failed(e);
        }
    }

    /**
     * 1環境分のカテゴリ一覧を取得する(比較テーブル・親カテゴリ解決に使用)。
     * 取得に失敗した場合は空リストを返す(呼び出し元でエラーとして扱わず、単に該当なしとする)。
     */
    public List<CategoryInfo> listCategories(String slug) {
        return listTerms("/categories", "categories", slug);
    }

    /**
     * 1環境分のタグ一覧を取得する(比較テーブルに使用)。タグは階層を持たないため
     * CategoryInfo.parentSlug()は常にnullになる。
     */
    public List<CategoryInfo> listTags(String slug) {
        return listTerms("/tags", "tags", slug);
    }

    private List<CategoryInfo> listTerms(String uri, String bodyKey, String slug) {
        try {
            Map<String, Object> body = listingClient.post()
                    .uri(uri)
                    .header("X-Provision-Token", provisionToken)
                    .body(Map.of("slug", slug))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (body == null || !(body.get(bodyKey) instanceof List<?> rawList)) {
                return List.of();
            }
            return rawList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> term = (Map<String, Object>) item;
                        return new CategoryInfo(
                                asString(term.get("name")),
                                asString(term.get("slug")),
                                asString(term.get("parentSlug")),
                                asString(term.get("description")));
                    })
                    .toList();
        } catch (ResourceAccessException e) {
            log.warn("provision-agentへの接続がタイムアウトしました (uri={}, slug={}): {}", uri, slug, e.getMessage());
            return List.of();
        } catch (RestClientResponseException e) {
            log.warn("provision-agentがエラー応答を返しました (uri={}, slug={}, status={})", uri, slug, e.getStatusCode());
            return List.of();
        } catch (RestClientException e) {
            return List.of();
        }
    }

    /**
     * 1環境分の、インストール済みプラグイン一覧(name+status)を取得する(比較テーブルに使用)。
     * 未インストールのプラグインはこの一覧に含まれない(呼び出し元で「一覧に無ければ未インストール」と判定する)。
     * 取得に失敗した場合は空リストを返す。
     */
    public List<PluginThemeInfo> listPlugins(String slug) {
        return listPluginsOrThemes("/plugins", "plugins", slug);
    }

    /**
     * 1環境分の、インストール済みテーマ一覧(name+status)を取得する(比較テーブルに使用)。
     */
    public List<PluginThemeInfo> listThemes(String slug) {
        return listPluginsOrThemes("/themes", "themes", slug);
    }

    private List<PluginThemeInfo> listPluginsOrThemes(String uri, String bodyKey, String slug) {
        try {
            Map<String, Object> body = listingClient.post()
                    .uri(uri)
                    .header("X-Provision-Token", provisionToken)
                    .body(Map.of("slug", slug))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (body == null || !(body.get(bodyKey) instanceof List<?> rawList)) {
                return List.of();
            }
            return rawList.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = (Map<String, Object>) item;
                        return new PluginThemeInfo(asString(entry.get("name")), asString(entry.get("status")));
                    })
                    .toList();
        } catch (ResourceAccessException e) {
            log.warn("provision-agentへの接続がタイムアウトしました (uri={}, slug={}): {}", uri, slug, e.getMessage());
            return List.of();
        } catch (RestClientResponseException e) {
            log.warn("provision-agentがエラー応答を返しました (uri={}, slug={}, status={})", uri, slug, e.getStatusCode());
            return List.of();
        } catch (RestClientException e) {
            return List.of();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private BulkApplyResult resultOf(Map<String, String> body) {
        String status = body != null ? body.get("status") : null;
        return "skipped".equals(status) ? BulkApplyResult.skipped() : BulkApplyResult.success();
    }

    /**
     * value/categorySlug/categoryParentSlug/categoryDescription/categoryTargetSlugの意味は
     * actionによって変わる(ApplyToEnvironmentRequestのフィールドコメントを参照)。
     */
    public record BulkApplyCommand(
            String slug, String action, String value,
            String categorySlug, String categoryParentSlug, String categoryDescription,
            String categoryTargetSlug) {
    }

    public record CategoryInfo(String name, String slug, String parentSlug, String description) {
    }

    /**
     * statusは"active"(有効)またはそれ以外(インストール済みだが無効、例:"inactive")。
     * 一覧に含まれないslugは「未インストール」を意味する(呼び出し元で判定)。
     */
    public record PluginThemeInfo(String name, String status) {
    }

    public record BulkApplyResult(String status, String errorMessage, String stackTrace) {
        public static BulkApplyResult success() {
            return new BulkApplyResult("SUCCESS", null, null);
        }

        public static BulkApplyResult skipped() {
            return new BulkApplyResult("SKIPPED", null, null);
        }

        public static BulkApplyResult failed(Throwable cause) {
            return new BulkApplyResult("FAILED", cause.getMessage(), StackTraceUtil.toString(cause));
        }
    }
}
