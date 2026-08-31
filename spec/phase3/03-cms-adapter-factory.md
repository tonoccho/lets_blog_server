# 03. CmsAdapterFactory の導入と依存性注入の統合

## 目的

`CmsAdapter` の実装が WordPress (phase 1) + microCMS (phase 3 新規) の複数になると、既存の「単一 `@Component` Bean の直接注入」方式では Spring が `NoUniqueBeanDefinitionException` を発生させる。

このタスクでは、`CmsAdapterFactory` を新設し、CMS種別に応じた適切な実装を runtime に選択・返すようにする。あわせて既存の4つのコンポーネント(`PostPublishService` / `MediaController` / `TaxonomyController` / `PlantUmlEmbedService`)の `CmsAdapter` 直接注入を、**Factory経由のlazy解決** に変更する。

## 前提・決定事項

- `CmsAdapter` 実装Bean が複数ある場合、直接注入では曖昧性が発生するため、Factory パターンを使う。
- Factory は `@Component` Bean として登録、`List<CmsAdapter>` をコンストラクタ注入受け取り、`supportedType()` でMap化しておく。
- 呼び出し元は、credentials の `cmsType()` を key に Factory で解決: `factory.resolve(credentials.cmsType())`。
- Factory での失敗(未対応なCMS種別)は明確なメッセージの `CmsApiException` を投げる。

## コンポーネント構成・変更内容

### `CmsAdapterFactory.java` (新規)

```java
package com.letsblog.api.cms;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CmsAdapterFactory {

    private final Map<CmsType, CmsAdapter> adapters = new HashMap<>();

    public CmsAdapterFactory(List<CmsAdapter> adapterList) {
        for (CmsAdapter adapter : adapterList) {
            adapters.put(adapter.supportedType(), adapter);
        }
    }

    public CmsAdapter resolve(CmsType cmsType) {
        CmsAdapter adapter = adapters.get(cmsType);
        if (adapter == null) {
            throw new CmsApiException("未対応のCMS種別です: " + cmsType);
        }
        return adapter;
    }
}
```

動作:
- Spring が `List<CmsAdapter>` として全ての `@Component` 実装 Bean を注入(将来 WordPress/microCMS/... が登録されると自動でリストに含まれる)
- コンストラクタで各 adapter の `supportedType()` を キーに HashMap に格納
- `resolve(CmsType)` で該当 adapter を返す

### `PostPublishService.java` (修正)

変更前:
```java
private final CmsAdapter cmsAdapter;

public PostPublishService(..., CmsAdapter cmsAdapter, ...) {
    this.cmsAdapter = cmsAdapter;
    ...
}

public PostPublishResponse publish(PostPublishCommand command) {
    ...
    CmsCredentials credentials = siteService.getCredentials(command.siteKey());
    ...
    List<Long> categoryIds = cmsAdapter.resolveCategories(credentials, command.categories());
    List<Long> tagIds = cmsAdapter.resolveTags(credentials, command.tags());
    ...
    PostResult result = cmsAdapter.createOrUpdatePost(credentials, content, command.wpPostId());
    ...
}
```

変更後:
```java
private final CmsAdapterFactory cmsAdapterFactory;

public PostPublishService(..., CmsAdapterFactory cmsAdapterFactory, ...) {
    this.cmsAdapterFactory = cmsAdapterFactory;
    ...
}

public PostPublishResponse publish(PostPublishCommand command) {
    ...
    CmsCredentials credentials = siteService.getCredentials(command.siteKey());
    CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
    ...
    List<String> categoryIds = cmsAdapter.resolveCategories(credentials, command.categories());
    List<String> tagIds = cmsAdapter.resolveTags(credentials, command.tags());
    ...
    PostResult result = cmsAdapter.createOrUpdatePost(credentials, content, command.wpPostId());
    ...
}
```

ポイント:
- 呼び出し前に `cmsAdapterFactory.resolve()` で CMS種別に応じた adapter を取得
- 戻り値型も `List<Long>` → `List<String>` に(01-domain-model.md による)

### `MediaController.java` (修正)

変更前:
```java
private final CmsAdapter cmsAdapter;

public MediaController(SiteService siteService, CmsAdapter cmsAdapter) {
    this.siteService = siteService;
    this.cmsAdapter = cmsAdapter;
}

@PostMapping("/api/media/upload")
public MediaUploadResult upload(@RequestParam("site") String site, @RequestPart("file") MultipartFile file) {
    return cmsAdapter.uploadMedia(...);
}
```

変更後:
```java
private final CmsAdapterFactory cmsAdapterFactory;

public MediaController(SiteService siteService, CmsAdapterFactory cmsAdapterFactory) {
    this.siteService = siteService;
    this.cmsAdapterFactory = cmsAdapterFactory;
}

@PostMapping("/api/media/upload")
public MediaUploadResult upload(@RequestParam("site") String site, @RequestPart("file") MultipartFile file) {
    CmsCredentials credentials = siteService.getCredentials(site);
    CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
    return cmsAdapter.uploadMedia(credentials, file.getOriginalFilename(), file.getContentType(), file.getBytes());
}
```

### `TaxonomyController.java` (修正)

変更前:
```java
private final CmsAdapter cmsAdapter;

public TaxonomyController(SiteService siteService, CmsAdapter cmsAdapter) {...}

@PostMapping("/api/taxonomy/resolve")
public TaxonomyResolveResponse resolve(@RequestBody TaxonomyResolveRequest request) {
    CmsCredentials credentials = siteService.getCredentials(request.site());
    return new TaxonomyResolveResponse(
        cmsAdapter.resolveCategories(credentials, request.categories()),
        cmsAdapter.resolveTags(credentials, request.tags())
    );
}
```

変更後:
```java
private final CmsAdapterFactory cmsAdapterFactory;

public TaxonomyController(SiteService siteService, CmsAdapterFactory cmsAdapterFactory) {...}

@PostMapping("/api/taxonomy/resolve")
public TaxonomyResolveResponse resolve(@RequestBody TaxonomyResolveRequest request) {
    CmsCredentials credentials = siteService.getCredentials(request.site());
    CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
    return new TaxonomyResolveResponse(
        cmsAdapter.resolveCategories(credentials, request.categories()),
        cmsAdapter.resolveTags(credentials, request.tags())
    );
}
```

ただし、戻り値型も `List<Long>` → `List<String>` に変更する必要あり。

### `TaxonomyResolveResponse.java` (修正)

```java
// 変更前
public record TaxonomyResolveResponse(List<Long> categoryIds, List<Long> tagIds) { }

// 変更後
public record TaxonomyResolveResponse(List<String> categoryIds, List<String> tagIds) { }
```

### `PlantUmlEmbedService.java` (修正)

変更前:
```java
private final CmsAdapter cmsAdapter;

public PlantUmlEmbedService(PlantUmlClient plantUmlClient, CmsAdapter cmsAdapter) {...}

public String embedDiagrams(CmsCredentials credentials, String markdown) {
    ...
    MediaUploadResult uploaded = cmsAdapter.uploadMedia(credentials, fileName, "image/png", png);
    ...
}
```

変更後:
```java
private final CmsAdapterFactory cmsAdapterFactory;

public PlantUmlEmbedService(PlantUmlClient plantUmlClient, CmsAdapterFactory cmsAdapterFactory) {...}

public String embedDiagrams(CmsCredentials credentials, String markdown) {
    CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
    ...
    MediaUploadResult uploaded = cmsAdapter.uploadMedia(credentials, fileName, "image/png", png);
    ...
}
```

### `CmsAdapterFactoryTest.java` (新規・簡易)

```java
package com.letsblog.api.cms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CmsAdapterFactoryTest {

    @Autowired
    private CmsAdapterFactory factory;

    @Test
    void testResolveWordPress() {
        CmsAdapter adapter = factory.resolve(CmsType.WORDPRESS);
        assertNotNull(adapter);
        assertEquals(CmsType.WORDPRESS, adapter.supportedType());
    }

    @Test
    void testResolveUnsupported() {
        // microCMS adapter がまだ実装されていない段階では、これは失敗する
        // (microCmsAdapter.java 実装後に、CmsType.MICROCMS の resolve テストに変わる)
        assertThrows(CmsApiException.class, () -> factory.resolve(CmsType.MICROCMS));
    }
}
```

第1段階(WordPressAdapterTest+Factory until microCMS実装)では、MICROCMS resolve は失敗テストでOK。microCMS adapter実装後に Pass に変わる。

## タスクチェックリスト

- [x] `CmsAdapterFactory.java` 実装
- [x] `PostPublishService.java` 修正(CmsAdapter 直接注入 → Factory経由、戻り値型変更)※Post/PostPublishCommand等のString ID化と合わせて[04-database-schema](04-database-schema.md)のコミットで実施
- [x] `MediaController.java` 修正(CmsAdapter 直接注入 → Factory経由)
- [x] `TaxonomyController.java` 修正(CmsAdapter 直接注入 → Factory経由)
- [x] `TaxonomyResolveResponse.java` 戻り値型変更 (`List<Long>` → `List<String>`)
- [x] `PlantUmlEmbedService.java` 修正(CmsAdapter 直接注入 → Factory経由)
- [x] `CmsAdapterFactoryTest.java` 実装・実行確認
- [x] `./gradlew test` で全テスト PASS(WordPressAdapterTest + CmsAdapterFactoryTest)
- [x] `./gradlew bootRun` で Spring Boot 起動、`CmsAdapterFactory` Bean の load エラーがないこと確認(Dockerコンテナで実施)

## 実装状況

`PostPublishService.java` は `Post`/`PostPublishCommand` 等のID型変更([04-database-schema](04-database-schema.md))と密結合していたため、当タスクの時点ではコミットに含めず、Factory経由の他3コンポーネント(MediaController/TaxonomyController/PlantUmlEmbedService)のみを先にコミットした。`CmsAdapterFactoryTest`もSpring非依存の純粋ユニットテストとして実装([02-wordpress-adapter-tests](02-wordpress-adapter-tests.md)と同じ方針)。

## 未決事項

- Factory での未対応CMS種別のエラーメッセージの詳細化(404 vs 400 vs 500のHTTPステータス振り分け)。現状は runtime 例外 throw で OK。
