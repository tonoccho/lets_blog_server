# 01. ドメインモデルの汎用化

## 目的

`CmsAdapter` インターフェースとそれに関連する値オブジェクト(`CmsCredentials`, `PostResult`, `MediaUploadResult`)を、WordPress固有の型/フィールドから汎用型へ段階的に移行する。特に **ID型を `Long` → `String` に統一** する(microCMSのコンテンツIDが数値ではなく英数字のため)。

## 前提・決定事項

- ID型変更は全レイヤー(CmsAdapter→PostResult→Entity→DTO)で統一して行わないと、型安全性が崩れコンパイルエラーが多発する。そのため「この01タスクでドメイン層の変更を完結させ、その後WordPressAdapterの実装修正→CmsAdapterFactory導入」という順序にする。
- `CmsCredentials` を sealed interface にすることで、各CMS実装が必要なフィールドを型安全に定義できる(Java 17+ sealed classes の恩恵)。
- `CmsType` enum は、DB(`sites.cms_type`)・factory キー・runtime dispatching で共通利用する。

## コンポーネント構成・変更内容

### `CmsType.java` (新規)

```java
package com.letsblog.api.cms;

public enum CmsType {
    WORDPRESS("WordPress"),
    MICROCMS("microCMS");

    private final String displayName;
    CmsType(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}
```

### `CmsCredentials.java` (修正)

既存の単純レコードから sealed interface に変更:

```java
package com.letsblog.api.cms;

public sealed interface CmsCredentials {
    CmsType cmsType();

    record WordPressCredentials(
            String baseUrl,
            String username,
            String appPassword
    ) implements CmsCredentials {
        @Override public CmsType cmsType() { return CmsType.WORDPRESS; }
    }

    record MicroCmsCredentials(
            String serviceId,
            String apiKey,
            String managementApiKey,
            String postsEndpoint,
            String categoriesEndpoint,
            String tagsEndpoint
    ) implements CmsCredentials {
        @Override public CmsType cmsType() { return CmsType.MICROCMS; }
    }
}
```

### `PostResult.java` (修正)

```java
// 変更前
public record PostResult(Long id, String link, String status) { }

// 変更後
public record PostResult(String id, String link, String status) { }
```

### `MediaUploadResult.java` (修正)

```java
// 変更前
public record MediaUploadResult(Long id, String url) { }

// 変更後
public record MediaUploadResult(String id, String url) { }
```

### `CmsAdapter.java` (修正)

```java
package com.letsblog.api.cms;

import java.util.List;

public interface CmsAdapter {
    /**
     * このアダプタが対応するCMS種別を返す。
     * CmsAdapterFactory で実装を選択する際に使われる。
     */
    CmsType supportedType();

    /**
     * 投稿を新規作成、または既存投稿(existingPostId指定時)を更新する。
     *
     * @param credentials CMS接続情報
     * @param content     投稿内容
     * @param existingPostId 既存投稿ID(null=新規作成、非null=更新)
     * @return 作成/更新後の投稿情報(id/link/status)
     */
    PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId);

    /**
     * メディアライブラリへ画像をアップロードする。
     *
     * @param credentials CMS接続情報
     * @param filename    ファイル名
     * @param contentType MIME type
     * @param data        バイナリ
     * @return アップロード後のメディア情報(id/url)
     */
    MediaUploadResult uploadMedia(CmsCredentials credentials, String filename, String contentType, byte[] data);

    /**
     * カテゴリ名のリストをID解決する。存在しなければ作成する。
     *
     * @param credentials CMS接続情報
     * @param names       カテゴリ名リスト
     * @return カテゴリID リスト(String型)
     */
    List<String> resolveCategories(CmsCredentials credentials, List<String> names);

    /**
     * タグ名のリストをID解決する。存在しなければ作成する。
     *
     * @param credentials CMS接続情報
     * @param names       タグ名リスト
     * @return タグID リスト(String型)
     */
    List<String> resolveTags(CmsCredentials credentials, List<String> names);
}
```

## タスクチェックリスト

- [x] `CmsType.java` 新規実装
- [x] `CmsCredentials.java` を sealed interface に変更、`WordPressCredentials`/`MicroCmsCredentials`実装クラスを定義
- [x] `PostResult.java` ID型を `Long` → `String` に変更
- [x] `MediaUploadResult.java` ID型を `Long` → `String` に変更
- [x] `CmsAdapter.java` にシグネチャ変更を反映(resolveCategories/resolveTags の戻り値型 `List<Long>` → `List<String>`, createOrUpdatePost の existingPostId 型 `Long?` → `String?`)、`supportedType()` メソッド追加
- [x] `CmsApiException.java` はそのまま(エラーメッセージのみ使う)
- [x] `PostContent.java` の `categoryIds`/`tagIds` も `List<Long>` → `List<String>` に変更(計画時点で見落としていたが、resolveCategories/resolveTagsの戻り値をそのまま渡すため必須の変更として追加実施)

## 実装状況

計画通り実装。`MicroCmsCredentials` には当初案どおり `baseUrl` フィールドを含めず(microCMSはサービスIDから動的にURLを組み立てるため)、Site表示用の `baseUrl` は `SiteService` 側で別途算出する設計とした([04-database-schema](04-database-schema.md)参照)。

## 未決事項

- sealed interface の Jackson シリアライズ時の型情報ハンドリング → `SiteService` で `Map<String,String>` として直接シリアライズ/デシリアライズする方式に決定し解消済み([04-database-schema](04-database-schema.md)参照)
