# 05. microCMS アダプタの実装・テスト・実機検証

> **廃止済み(issue #374)**: 本ドキュメントが記述するmicroCMS対応(`MicroCmsAdapter`・`CmsType.MICROCMS`・関連UI/テスト)は、
> WordPress専業化のためissue #374で削除された。以下は実装当時の記録として残す。

## 目的

WordPress に続く2番目のCMS実装として、**microCMS** 向けの `CmsAdapter` 実装を追加する。microCMS は日本製ヘッドレスCMS で、WordPress と異なり以下の特徴を持つ:

- **コンテンツAPI**: `https://{serviceId}.microcms.io/api/v1/{endpoint}` で独自エンドポイントへアクセス(ユーザーが自分で定義)
- **認証**: APIキー(リクエストヘッダ `X-MICROCMS-API-KEY: {apiKey}`)
- **画像アップロード**: 別立ての **管理API** (`https://{serviceId}.microcms-management.io/api/v1/media`)でアップロード。専用の `managementApiKey` を要求
- **ID形式**: 投稿IDが数値ではなく英数字の文字列
- **タクソノミー**: WordPress のように固定タクソノミーが無く、ユーザーが事前に用意したlist型APIコンテンツで「カテゴリ/タグ」を管理

本タスクでは `MicroCmsAdapter` を実装し、テスト・実機検証まで完結させる。

## 前提・決定事項

- microCMS のコンテンツAPI は**ユーザーが事前に手動で用意する必要がある**。特に `categoriesEndpoint` / `tagsEndpoint` は、name フィールドを持つ list型API として用意されていることが前提条件。ドキュメント化は Phase3 後の 運用ドキュメント作成時に対応。
- 投稿の新規作成時は `POST {postsEndpoint}`、更新時は `PATCH {postsEndpoint}/{id}`。
- カテゴリ/タグの「存在確認→無ければ作成」ロジックは WordPress と同じ契約。ただし microCMS の list API はフルテキスト検索パラメータが異なるため、名前による部分一致検索を行う。
- エラーハンドリング: `RestClientResponseException` を捕捉し `CmsApiException` にラップする(WordPressAdapter と同じ方針)。
- テスト: `MicroCmsAdapterTest` で同じ MockRestServiceServer ベースのテストを実装。

## コンポーネント構成・変更内容

### `MicroCmsAdapter.java` (新規)

```java
package com.letsblog.api.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * microCMS (日本製ヘッドレスCMS) 向けの CmsAdapter 実装。
 * Content API + Management API (画像アップロード専用) を使い分ける。
 */
@Component
public class MicroCmsAdapter implements CmsAdapter {

    private final RestClient.Builder restClientBuilder;

    public MicroCmsAdapter(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public CmsType supportedType() {
        return CmsType.MICROCMS;
    }

    @Override
    public PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;

        ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("title", content.title());
        body.put("body", content.htmlContent());  // microCMS では body フィールドを使う
        if (content.slug() != null && !content.slug().isBlank()) {
            body.put("slug", content.slug());
        }
        if (content.categoryIds() != null && !content.categoryIds().isEmpty()) {
            body.put("category", content.categoryIds().get(0));  // microCMS は1つの category のみ(例)
        }
        if (content.tagIds() != null && !content.tagIds().isEmpty()) {
            ArrayNode tags = body.putArray("tags");
            content.tagIds().forEach(tags::add);
        }

        RestClient client = buildClient(creds, creds.apiKey());
        String endpoint = creds.postsEndpoint();
        String url = endpoint.startsWith("http") ? endpoint : "https://" + creds.serviceId() + ".microcms.io/api/v1/" + endpoint;

        try {
            JsonNode response;
            if (existingPostId == null) {
                // 新規作成
                response = client.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } else {
                // 更新
                response = client.patch()
                        .uri(url + "/" + existingPostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            }

            return new PostResult(
                    response.get("id").asText(),
                    creds.baseUrl() != null ? creds.baseUrl() + "/posts/" + response.get("id").asText() : url + "/" + response.get("id").asText(),
                    "published"  // microCMS は公開/下書きの区別が明示的ではない場合あり
            );
        } catch (RestClientResponseException e) {
            throw new CmsApiException("microCMS 投稿の作成/更新に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public MediaUploadResult uploadMedia(CmsCredentials credentials, String filename, String contentType, byte[] data) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;

        // 画像アップロードは Management API を使う
        RestClient client = buildClient(creds, creds.managementApiKey());

        try {
            JsonNode response = client.post()
                    .uri("https://" + creds.serviceId() + ".microcms-management.io/api/v1/media")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data)
                    .retrieve()
                    .body(JsonNode.class);

            return new MediaUploadResult(
                    response.get("id").asText(),
                    response.get("url").asText()
            );
        } catch (RestClientResponseException e) {
            throw new CmsApiException("microCMS メディアのアップロードに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    public List<String> resolveCategories(CmsCredentials credentials, List<String> names) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;
        return resolveTerms(creds, creds.categoriesEndpoint(), names);
    }

    @Override
    public List<String> resolveTags(CmsCredentials credentials, List<String> names) {
        CmsCredentials.MicroCmsCredentials creds = (CmsCredentials.MicroCmsCredentials) credentials;
        return resolveTerms(creds, creds.tagsEndpoint(), names);
    }

    private List<String> resolveTerms(CmsCredentials.MicroCmsCredentials creds, String endpoint, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        RestClient client = buildClient(creds, creds.apiKey());
        String url = endpoint.startsWith("http") ? endpoint : "https://" + creds.serviceId() + ".microcms.io/api/v1/" + endpoint;
        List<String> ids = new ArrayList<>();

        for (String name : names) {
            ids.add(findOrCreateTerm(client, url, name, creds));
        }
        return ids;
    }

    private String findOrCreateTerm(RestClient client, String endpoint, String name, CmsCredentials.MicroCmsCredentials creds) {
        try {
            // microCMS list API は通常 ?q=query パラメータで検索(例)
            // 実装時に microCMS の実際の API 仕様確認が必要
            JsonNode searchResult = client.get()
                    .uri(uriBuilder -> uriBuilder.path(endpoint).queryParam("q", name).build())
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode contents = searchResult.get("contents");
            if (contents != null && contents.isArray()) {
                for (JsonNode term : contents) {
                    if (term.get("name").asText().equalsIgnoreCase(name)) {
                        return term.get("id").asText();
                    }
                }
            }

            // 無ければ作成
            ObjectNode createBody = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            createBody.put("name", name);

            JsonNode created = client.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createBody)
                    .retrieve()
                    .body(JsonNode.class);

            return created.get("id").asText();
        } catch (RestClientResponseException e) {
            throw new CmsApiException("microCMS カテゴリ/タグ '" + name + "' の解決に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    private RestClient buildClient(CmsCredentials.MicroCmsCredentials credentials, String apiKey) {
        return restClientBuilder.clone()
                .defaultHeader("X-MICROCMS-API-KEY", apiKey)
                .build();
    }
}
```

実装上の注意点:
- 投稿フィールド名が WordPress (`content`) と異なり microCMS では `body` を使う想定(実装時に実際のAPIスキーマで調整)
- Management API のURLが `https://{serviceId}.microcms-management.io/api/v1/media` と異なる
- list API への検索は microCMS の仕様により異なる(上記は例; 実装段階で実際のパラメータ・レスポンス構造を確認)

### `MicroCmsAdapterTest.java` (新規)

```java
package com.letsblog.api.cms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@SpringBootTest
class MicroCmsAdapterTest {

    @Autowired
    private RestClient.Builder restClientBuilder;

    private MicroCmsAdapter adapter;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        adapter = new MicroCmsAdapter(restClientBuilder);
    }

    @Test
    void testSupportedType() {
        assertEquals(CmsType.MICROCMS, adapter.supportedType());
    }

    @Test
    void testCreatePost() {
        server.expect(requestTo("https://myservice.microcms.io/api/v1/posts"))
               .andExpect(method(POST))
               .andRespond(withSuccess(
                   "{\"id\":\"post-abc123\",\"title\":\"Test\",\"body\":\"<p>Test</p>\"}",
                   "application/json"));

        CmsCredentials.MicroCmsCredentials creds = new CmsCredentials.MicroCmsCredentials(
                "myservice", "api-key-123", "mgmt-key-456", "posts", "categories", "tags");
        PostContent content = new PostContent("Test", null, "<p>Test</p>", "draft", null, null);

        PostResult result = adapter.createOrUpdatePost(creds, content, null);

        assertEquals("post-abc123", result.id());
        assertTrue(result.link().contains("post-abc123"));
        server.verify();
    }

    @Test
    void testUploadMedia() {
        server.expect(requestTo("https://myservice.microcms-management.io/api/v1/media"))
               .andExpect(method(POST))
               .andRespond(withSuccess(
                   "{\"id\":\"media-xyz789\",\"url\":\"https://images.microcms-assets.io/media-xyz789.png\"}",
                   "application/json"));

        CmsCredentials.MicroCmsCredentials creds = new CmsCredentials.MicroCmsCredentials(
                "myservice", "api-key-123", "mgmt-key-456", "posts", "categories", "tags");
        byte[] imageData = new byte[]{1, 2, 3};

        MediaUploadResult result = adapter.uploadMedia(creds, "image.png", "image/png", imageData);

        assertEquals("media-xyz789", result.id());
        assertTrue(result.url().contains("media-xyz789"));
        server.verify();
    }

    @Test
    void testResolveCategories() {
        // 検索: 既存カテゴリ
        server.expect(requestTo(containsString("/categories")))
               .andRespond(withSuccess(
                   "{\"contents\":[{\"id\":\"cat-1\",\"name\":\"Tech\"}]}",
                   "application/json"));

        CmsCredentials.MicroCmsCredentials creds = new CmsCredentials.MicroCmsCredentials(
                "myservice", "api-key-123", "mgmt-key-456", "posts", "categories", "tags");

        List<String> ids = adapter.resolveCategories(creds, List.of("Tech"));

        assertEquals(1, ids.size());
        assertEquals("cat-1", ids.get(0));
        server.verify();
    }

    @Test
    void testApiError() {
        server.expect(requestTo("https://myservice.microcms.io/api/v1/posts"))
               .andRespond(withServerError());

        CmsCredentials.MicroCmsCredentials creds = new CmsCredentials.MicroCmsCredentials(
                "myservice", "api-key-123", "mgmt-key-456", "posts", "categories", "tags");
        PostContent content = new PostContent("Test", null, "<p>Test</p>", "draft", null, null);

        assertThrows(CmsApiException.class,
                () -> adapter.createOrUpdatePost(creds, content, null));
        server.verify();
    }
}
```

## 実機検証手順(Phase3後の運用)

1. **microCMS 無料アカウント作成**: https://microcms.io で登録
2. **API エンドポイント手動設定**:
   - `posts` (投稿コンテンツ)
   - `categories` (カテゴリ、name フィールド必須)
   - `tags` (タグ、name フィールド必須)
3. **APIキー取得**: Settings → API Key で `apiKey` と `managementApiKey` を取得
4. **Web UI からサイト登録**: CMS種別を「microCMS」選択、各種情報入力
5. **Markdown投稿テスト**: VSCode拡張経由で新規投稿・更新・画像アップロード・カテゴリ/タグ自動作成を一通り実行
6. **microCMS ダッシュボード確認**: コンテンツが正しく反映されたか確認
7. テスト完了後、`spec/todo.md` の該当項目にチェック(ユーザー承認の上)

## タスクチェックリスト

- [x] `MicroCmsAdapter.java` 実装(Content API + Management API)
- [x] `MicroCmsAdapter.supportedType()` 実装 → `CmsType.MICROCMS` 返却
- [x] `createOrUpdatePost()` 実装(POST新規作成 / PATCH更新)
- [x] `uploadMedia()` 実装(Management API経由)
- [x] `resolveCategories()` / `resolveTags()` 実装
- [x] `MicroCmsAdapterTest.java` 実装(8テストケース: supportedType・作成・更新・画像アップロード・カテゴリ解決2種・タグ解決・エラー)
- [x] `./gradlew test` で MicroCmsAdapterTest が全て PASS
- [x] `CmsAdapterFactory.resolve(CmsType.MICROCMS)` が MicroCmsAdapter を返すことを確認(CmsAdapterFactoryTest更新)
- [x] `./gradlew bootRun` で Spring Boot 起動、`MicroCmsAdapter` Bean load エラーなし(WordPressAdapterと2つのCmsAdapter Beanが共存できることを確認)
- [ ] 実機検証(microCMS無料アカウント準備)
  - [ ] サイト登録(microCMS)
  - [ ] 投稿作成・更新
  - [ ] 画像アップロード
  - [ ] カテゴリ/タグ自動作成
  - [ ] microCMS ダッシュボード確認

## 実装状況

コード実装・単体テスト・起動確認は完了。ただし実際のmicroCMSアカウントを用いたエンドツーエンド実機検証は本セッションでは実施していない(microCMSアカウント作成が必要なため運用開始後に別途実施)。WordPress側は一時WordPressコンテナでの実機検証を完了済み([00-overview](00-overview.md)参照)。

`MicroCmsAdapter`は`CmsCredentials.MicroCmsCredentials`に`baseUrl`フィールドが無いため、投稿の`link`はContent APIのURL(`https://{serviceId}.microcms.io/api/v1/{postsEndpoint}/{id}`)をそのまま返す設計とした(microCMSは汎用的な公開URLパターンを持たないヘッドレスCMSのため)。

## 未決事項

- microCMS の実際のAPI仕様(フィールド名・検索パラメータ・レスポンス構造)の詳細確認 → 実装は`filters=name[equals]{name}`によるフィルタ検索・`contents`配列のレスポンス形式を前提に行った。実際のmicroCMSサービスでの実機検証時に必要なら調整する。
- list API の検索パラメータ仕様(WordPressの `search` パラメータと異なる場合あり)
- マルチテナント対応(複数の microCMS サービスIDを切り替える場合の実装パターン)
