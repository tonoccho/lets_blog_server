# 02. WordPress アダプタの修正・テスト整備

## 目的

01-domain-model.md による ID型 String化に対応させ、同時に **`WordPressAdapter` の自動テスト(回帰テスト)** を新規実装する。既存のWordPress投稿機能を自動テストで守るとともに、将来のCmsAdapter拡張時に既存機能を壊さないよう保険をかける。

Phase2完了時点でユニットテストが皆無(手動実機検証のみ)だったため、ここでテスト基盤を整備することは重要。

## 前提・決定事項

- `MockRestServiceServer.bindTo(RestClient.Builder)` を使い、Spring Boot 3.3.4 組み込みの `RestClient` テストサポートで MockHTTPサーバーを実装する。追加依存なし。
- テストは `api/src/test/java/com/letsblog/api/cms/WordPressAdapterTest.java` に配置。JUnit 5 (build.gradle に `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'` 既記載)。
- テストケースは: **投稿作成** / **投稿更新(existingPostId指定)** / **メディアアップロード** / **カテゴリ解決(既存/新規作成)** / **タグ解決** / **エラーハンドリング(4xx/5xx)** の6パターン。
- `RestClient.Builder` は Spring Boot がBeanとして自動設定する(`RestClientBuilderConfigurer`経由)ため、`@SpringBootTest` で DI可能。ただしテストでは `MockRestServiceServer.bindTo(builder)` で intercept するため、MockHttpServer + 実装側の `buildClient()` 呼び出しが整合するよう設計する。
- 本Taskが完了してから次のCmsAdapterFactory導入(03タスク)に進むこと。この投稿前置きは既存動作の安全弁。

## コンポーネント構成・変更内容

### `WordPressAdapter.java` (修正)

1. **ID型の String化**:
   - `createOrUpdatePost(..., String existingPostId)` に変更
   - `resolveCategories`/`resolveTags` の戻り値を `List<Long>` → `List<String>` に
   - レスポンス解析時 `.get("id").asLong()` → `.get("id").asText()` に
   - カテゴリ/タグID配列構築時、String IDを `Integer.parseInt(id)` で数値に変換(WP APIが数値のID配列を要求)

2. **RestClient.Builder依存性注入**:
   - 現在: `buildClient()` 内で毎回 `RestClient.builder()` を呼び出している(テスト困難)
   - 改修後: コンストラクタで `RestClient.Builder` を注入、保存
   - `buildClient()` 内で `builder.clone()` してカスタマイズ

3. **`supportedType()` メソッド実装**:
   ```java
   @Override
   public CmsType supportedType() {
       return CmsType.WORDPRESS;
   }
   ```

### `WordPressAdapterTest.java` (新規)

```java
package com.letsblog.api.cms;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
class WordPressAdapterTest {

    @Autowired
    private RestClient.Builder restClientBuilder;

    private WordPressAdapter adapter;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        adapter = new WordPressAdapter(restClientBuilder);
    }

    @Test
    void testSupportedType() {
        assertEquals(CmsType.WORDPRESS, adapter.supportedType());
    }

    @Test
    void testCreatePost() {
        // Mock: POST /wp-json/wp/v2/posts → 201 Created
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts"))
               .andExpect(method(POST))
               .andRespond(withSuccess(
                   "{\"id\":123,\"link\":\"http://example.com/posts/test\",\"status\":\"draft\"}",
                   "application/json"));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null);

        PostResult result = adapter.createOrUpdatePost(creds, content, null);

        assertEquals("123", result.id());  // String型
        assertEquals("http://example.com/posts/test", result.link());
        assertEquals("draft", result.status());
        server.verify();
    }

    @Test
    void testUpdatePost() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts/123"))
               .andExpect(method(POST))
               .andRespond(withSuccess(
                   "{\"id\":123,\"link\":\"http://example.com/posts/test\",\"status\":\"published\"}",
                   "application/json"));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent("Updated Title", "test-slug", "<p>Updated</p>", "publish", null, null);

        PostResult result = adapter.createOrUpdatePost(creds, content, "123");

        assertEquals("123", result.id());
        assertEquals("publish", result.status());
        server.verify();
    }

    @Test
    void testUploadMedia() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/media"))
               .andExpect(method(POST))
               .andRespond(withSuccess(
                   "{\"id\":456,\"source_url\":\"http://example.com/wp-content/uploads/image.png\"}",
                   "application/json"));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        byte[] imageData = new byte[]{1, 2, 3};

        MediaUploadResult result = adapter.uploadMedia(creds, "image.png", "image/png", imageData);

        assertEquals("456", result.id());
        assertEquals("http://example.com/wp-content/uploads/image.png", result.url());
        server.verify();
    }

    @Test
    void testResolveCategoriesExisting() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
               .andRespond(withSuccess(
                   "[{\"id\":1,\"name\":\"Technology\"}]",
                   "application/json"));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<String> ids = adapter.resolveCategories(creds, List.of("Technology"));

        assertEquals(1, ids.size());
        assertEquals("1", ids.get(0));
        server.verify();
    }

    @Test
    void testResolveCategoriesCreateNew() {
        // 検索: 該当なし
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
               .andRespond(withSuccess("[]", "application/json"));
        // 作成: POST /categories
        server.expect(requestTo("http://example.com/wp-json/wp/v2/categories"))
               .andExpect(method(POST))
               .andRespond(withSuccess(
                   "{\"id\":2,\"name\":\"NewCategory\"}",
                   "application/json"));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<String> ids = adapter.resolveCategories(creds, List.of("NewCategory"));

        assertEquals(1, ids.size());
        assertEquals("2", ids.get(0));
        server.verify();
    }

    @Test
    void testApiError() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts"))
               .andRespond(withServerError());

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent("Test", null, "<p>Test</p>", "draft", null, null);

        assertThrows(CmsApiException.class,
                () -> adapter.createOrUpdatePost(creds, content, null));
        server.verify();
    }
}
```

テストポイント:
- ID が String型で返されることを明示的に確認 (`assertEquals("123", result.id())`)
- カテゴリ/タグの「検索→ヒット/新規作成」の両パスをカバー
- REST呼び出しの HTTP method・URL・ヘッダー(Basic 認証)を MockServer で検証
- 異常系(`CmsApiException`)も確認

## タスクチェックリスト

- [x] `CmsType.java` の `WORDPRESS` enum を利用できることを確認
- [x] `WordPressAdapter.java`:
  - [x] コンストラクタで `RestClient.Builder` を注入受け取る (`private final RestClient.Builder builder`)
  - [x] `buildClient()` を修正(`builder.clone()` 使用)
  - [x] `createOrUpdatePost` メソッドシグネチャを `Long existingPostId` → `String existingPostId` に変更
  - [x] ID取得時 `.asLong()` → `.asText()` に変更
  - [x] カテゴリ/タグID配列構築時、String IDを数値変換(`Integer.parseInt(id)`)
  - [x] `resolveCategories`/`resolveTags` の戻り値型を `List<Long>` → `List<String>` に
  - [x] `supportedType()` メソッド実装
- [x] `WordPressAdapterTest.java` 実装(6テストケース + タグ解決テストを追加した計7テストケース)
- [x] `./gradlew test` で WordPressAdapterTest が **全て PASS** することを確認
- [x] WordPressAdapter の既存ユースケースを一時WordPressコンテナに対する実機検証で確認(最終タスクでまとめて実施、[00-overview](00-overview.md)参照)
- [x] Spring Boot 起動時に `WordPressAdapter` Bean が問題なく load される(アラートログなし)ことを確認

## 実装状況

計画からの変更点: `@SpringBootTest` + `@Autowired RestClient.Builder` ではなく、`RestClient.builder()` を直接テスト内で生成する純粋なユニットテストとした。理由は、`@SpringBootTest` だとJPA/Flyway/MySQL接続や `APP_ENCRYPTION_KEY` 等の環境変数が必要になり、CIやサンドボックス環境でDBなしでは実行できなくなるため。`WordPressAdapter` はDIコンテナに依存しない設計のため、Spring非依存の軽量テストで十分だった。

## 未決事項

- MockHttpServer の response validation の詳細レベル(本計画では最小限のHTTP method/URIのみ検証。リクエストボディの詳細JSON検証は必要に応じて追加)
