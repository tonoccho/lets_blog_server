# 02. WordPress著者作成403エラーの原因究明・修正

## 目的

プロジェクトへユーザーを追加した際に発生する

```
APIエラー (502): {"error":"WordPress著者の作成に失敗しました: 403 FORBIDDEN ..."}
```

の原因を特定し、(1) 再発防止(検知・警告)と (2) 発生時のエラーメッセージ改善を行う。

## 原因調査

処理経路: `ProjectUserSyncService.addUserToProject` → `syncToProjectSites` → `siteService.getCredentials(siteKey)` → `WordPressAdapter.provisionAuthor(credentials, request)` → `POST /wp-json/wp/v2/users`(Basic認証、`credentials.username`/`credentials.appPassword`)。

コード自体(認証ヘッダ構築・`roles`配列の形・エンドポイント)は正しく実装されており、リクエスト内容に不備はない(`WordPressAdapter.java` 164-199行目、273-281行目で確認済み)。

**根本原因**: WordPressのREST APIで新規ユーザーを作成する(`POST /wp-json/wp/v2/users`)には、認証しているアカウントが`create_users`権限(既定ではAdministratorロールのみ)を持っている必要がある。この検証が、サイト登録時にもプロジェクトユーザー追加時にも一切行われていなかった。

- 自動構築(managed)サイトは`wp core install --admin_user=...`で作成されるため、常にAdministrator権限を持つ → このパスは問題なし
- **外部の既存WordPressサイトを「既存サイトを登録」で登録する場合**、`SiteService`は`baseUrl`/`username`/`appPassword`が空でないことしか検証しない(`SiteService.java`の`requiredCredentialKeys`/`validateCredentials`)。Editor等、Administrator権限を持たないアカウントの認証情報でも登録・疎通確認(`testConnection`、`GET /wp-json/wp/v2/users/me`は認証さえ通れば200を返す)は成功してしまう
- 結果、権限不足に気づかないまま運用され、実際にプロジェクトへメンバーを追加した瞬間に初めて403が発覚する、という状態になっていた

他の仮説(`roles`配列の形式不正、Basic認証ヘッダの構築ミス、HTTP/HTTPS判定によるApplication Passwords無効化)はコードの実装内容から否定的(いずれも発生した場合は400/401になり、報告された403とは一致しない)。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 権限チェックの方式 | `GET /wp-json/wp/v2/users/me?context=edit`のレスポンスに含まれる`capabilities.create_users`を確認する新規メソッドを`WordPressAdapter`に追加 |
| チェックのインターフェース化 | `CmsAdapter`にデフォルトメソッド`hasAuthorProvisioningCapability(CmsCredentials): boolean`を追加(既定`true`)、`WordPressAdapter`のみオーバーライド(microCMSは著者の概念がないため常に`true`) |
| チェックのタイミング(1) | 01で追加した`POST /api/sites/{id}/test-connection`のレスポンスに`hasAdminCapability`(WordPressサイトのみ判定、それ以外は`null`)を追加し、疎通確認と同時に警告できるようにする |
| チェックのタイミング(2) | `ProjectUserSyncService.syncToProjectSites`で、各サイトに対して`provisionAuthor`を呼ぶ**前**に`hasAuthorProvisioningCapability`を確認し、権限がなければ明確な日本語メッセージで即座に失敗させる(WordPress側への実際のPOSTを試みてから403を受け取るより速く、原因も明確) |
| 登録をブロックするか | しない。権限不足は警告に留め、登録自体は成功させる(既存の「疎通確認失敗でも登録は完了する」という方針を踏襲) |
| エラーメッセージ改善 | `provisionAuthor`/`updateAuthor`が403を受け取った場合、原因(登録済み認証情報に管理者権限がない可能性)を明記したメッセージに変更する |
| 既存の壊れたサイトの復旧手段 | 01で追加した「サイト編集」機能で、そのサイトの認証情報をAdministrator権限を持つアカウントのApplication Passwordに更新する。データ移行やマイグレーションは不要、運用手順のみで解決する |

## アーキテクチャ・実装詳細

### `CmsAdapter.java`

```java
public interface CmsAdapter {
    // ...既存メソッド...

    /**
     * このサイトの登録済み認証情報が、著者(WordPressユーザー)の作成・更新を
     * 行うのに十分な権限を持っているかどうかを判定する。
     * CMSによっては著者という概念自体がないため既定はtrue。
     */
    default boolean hasAuthorProvisioningCapability(CmsCredentials credentials) {
        return true;
    }
}
```

### `WordPressAdapter.java`

```java
@Override
public boolean hasAuthorProvisioningCapability(CmsCredentials credentials) {
    CmsCredentials.WordPressCredentials creds = (CmsCredentials.WordPressCredentials) credentials;
    RestClient client = buildClient(creds);
    try {
        JsonNode me = client.get()
                .uri("/wp-json/wp/v2/users/me?context=edit")
                .retrieve()
                .body(JsonNode.class);
        JsonNode capabilities = me != null ? me.get("capabilities") : null;
        return capabilities != null && capabilities.path("create_users").asBoolean(false);
    } catch (RestClientResponseException | ResourceAccessException e) {
        return false;
    }
}
```

`provisionAuthor`/`updateAuthor`の403時のメッセージ改善:

```java
// provisionAuthor / updateAuthor 共通の catch (RestClientResponseException e) 内、
// 409分岐の後・汎用throwの前に追加
if (e.getStatusCode().value() == 403) {
    throw new CmsApiException(
        "WordPress著者の作成に失敗しました: サイトに登録されている認証情報のWordPressアカウントに" +
        "ユーザー作成権限(Administrator)がない可能性があります。" +
        "サイト管理画面の編集機能で、管理者権限を持つアカウントのアプリケーションパスワードに更新してください。" +
        "(詳細: " + e.getStatusCode() + " " + e.getResponseBodyAsString() + ")", e);
}
```

### `ProjectUserSyncService.java`

```java
private void syncToProjectSites(Project project, User user, String wpRole) {
    AuthorProvisioningRequest request = new AuthorProvisioningRequest(...);

    for (Site site : getProjectSites(project)) {
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter adapter = cmsAdapterFactory.resolve(site.getCmsType());
        if (!adapter.hasAuthorProvisioningCapability(credentials)) {
            throw new CmsApiException(
                "サイト '" + site.getSiteKey() + "' の登録済み認証情報に、ユーザー作成に必要な管理者権限がありません。" +
                "サイト管理画面から認証情報を更新してください。");
        }
        adapter.provisionAuthor(credentials, request);
    }
}
```

### `SiteService.checkConnection` の拡張(01からの変更)

01で追加した`checkConnection(Long id): boolean`を、`SiteConnectionCheckResult(boolean connectionOk, Boolean hasAdminCapability)`(新規record)を返すように拡張する。`hasAdminCapability`は接続に成功しWordPressサイトの場合のみ判定、それ以外は`null`。

```java
public record SiteConnectionCheckResult(boolean connectionOk, Boolean hasAdminCapability) {}

@Transactional(readOnly = true)
public SiteConnectionCheckResult checkConnection(Long id) {
    Site site = siteRepository.findById(id)
            .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
    CmsCredentials credentials = getCredentials(site.getSiteKey());
    CmsAdapter adapter = cmsAdapterFactory.resolve(site.getCmsType());
    boolean ok;
    try {
        ok = adapter.testConnection(credentials);
    } catch (Exception e) {
        ok = false;
    }
    Boolean hasAdminCapability = (ok && site.getCmsType() == CmsType.WORDPRESS)
            ? adapter.hasAuthorProvisioningCapability(credentials) : null;
    return new SiteConnectionCheckResult(ok, hasAdminCapability);
}
```

`SiteController.testConnection`のレスポンスも`{connectionCheckStatus, hasAdminCapability}`に変更。

### フロントエンド

- `apiClient.ts`: `checkSiteConnection`の戻り値型に`hasAdminCapability: boolean | null`を追加
- `CheckConnectionButton.tsx`(01で追加したもの): `hasAdminCapability === false`の場合、「⚠ このサイトの認証情報には管理者権限がありません」という警告文を追加表示

## スコープ・実装項目

実装対象:

- [x] `CmsAdapter.java`: `hasAuthorProvisioningCapability` デフォルトメソッド追加
- [x] `WordPressAdapter.java`: `hasAuthorProvisioningCapability` 実装、`provisionAuthor`/`updateAuthor`の403メッセージ改善
- [x] `ProjectUserSyncService.java`: `syncToProjectSites`に事前権限チェック追加
- [x] `SiteService.java`: `checkConnection`を`SiteConnectionCheckResult`返却に変更(01からの拡張)
- [x] `SiteController.java`: `test-connection`レスポンスに`hasAdminCapability`追加
- [x] `web/src/lib/apiClient.ts`: 型拡張
- [x] `web/src/app/sites/CheckConnectionButton.tsx`: 警告表示追加

対象外・スコープ外:

- サイト登録・編集時に権限不足を理由に保存自体をブロックすること
- microCMS等、WordPress以外のCMSでの著者権限チェック(著者概念がないため対象外)
- WordPress側の権限(ロール)自体を自動で変更・付与する機能

## 実装順序

1. `CmsAdapter`/`WordPressAdapter`に権限チェックメソッド追加
2. `ProjectUserSyncService`の事前チェック追加(即効性のある再発防止)
3. `provisionAuthor`/`updateAuthor`のエラーメッセージ改善
4. `SiteService.checkConnection`/`SiteController`/フロントの拡張(01の疎通確認機能への統合)
5. テスト整備・実機検証

## テスト整備

- `WordPressAdapterTest`: `hasAuthorProvisioningCapability`が`capabilities.create_users`の有無で正しくtrue/falseを返すこと、403時のエラーメッセージに権限不足の案内文が含まれること
- `ProjectUserSyncServiceTest`: 権限がないサイトに対して`provisionAuthor`を呼ぶ前に例外がスローされること(実際にWordPress APIへリクエストが飛ばないことをmockの呼び出し回数で確認)
- `SiteServiceTest`: `checkConnection`が`hasAdminCapability`を接続成功時のみ・WordPressサイトのみ判定すること

## 実機検証

1. 実WordPress環境でEditor権限のApplication Passwordを発行し、外部サイトとして登録
2. サイト一覧で疎通確認 → `SUCCESS`だが`hasAdminCapability`警告が表示されることを確認
3. このサイトが環境に紐づくプロジェクトへユーザーを追加 → 明確な日本語エラー(WordPress側への実POST前に検知されたもの)が返ることを確認
4. サイト編集機能でAdministrator権限のApplication Passwordに更新 → 疎通確認で警告が消えることを確認
5. 同じプロジェクトへ再度ユーザーを追加 → 正常に成功することを確認
