# 01. サイト管理: 疎通確認・編集機能

## 目的

サイト管理画面(`/sites`)から、登録済みサイトに対して(1) いつでも再度疎通確認を実行できる、(2) 表示名・認証情報を編集できる、の2機能を追加する。

## 現状確認(調査結果)

- `Site`エンティティに`connectionCheckStatus`という列は存在しない。`SiteResponse.connectionCheckStatus`は**登録直後の1回のみ**、`SiteService.register()`内で`testConnection(...)`(private)を呼んで計算される、その場限りの値(DB非保存)。`list()`/`getBySiteKey()`は常に`connectionCheckStatus: null`を返す
- `CmsAdapter`インターフェースには既に`testConnection(CmsCredentials): boolean`が定義されており、`WordPressAdapter`(`GET /wp-json/wp/v2/users/me`)・`MicroCmsAdapter`(`GET {postsEndpoint}?limit=1`)双方に実装済み。**再チェック機能はこれをそのまま再利用できる**
- `SiteService.getCredentials(String siteKey)`が既に存在し、`credentialsEncrypted`(汎用JSON暗号化列)と、レガシーな`wpUsername`/`wpAppPasswordEncrypted`(Phase2以前のWordPress専用列)の両方に対応して復号する
- `SiteController`には`GET /api/sites/{id}`、`PUT /api/sites/{id}`、疎通確認系のエンドポイントは一切存在しない。`POST /api/sites/{id}/reprovision`(カテゴリ/タグ/著者の再プロビジョニング)は存在するが、フロント側に対応するボタン・アクションがなく現状未使用
- 認証情報は`CmsCredentials`(sealed record: `WordPressCredentials(baseUrl, username, appPassword)` / `MicroCmsCredentials(serviceId, apiKey, managementApiKey, postsEndpoint, categoriesEndpoint, tagsEndpoint)`)としてJSON化した上でAES-256-GCM(`CredentialCipher`)で1つのblobとして`credentialsEncrypted`に保存されている。列単位の部分更新はできないため、編集は「復号→フィールド上書き→再暗号化」で行う
- フロントの`Site`型・`apiClient.ts`には`updateSite`/`checkSiteConnection`/`getSite`のいずれも存在しない。サイト一覧(`sites/page.tsx`)には削除ボタンのみで、編集・再疎通確認のUIは皆無

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 疎通確認エンドポイント | `POST /api/sites/{id}/test-connection`。admin不問(読み取り専用・非破壊的なため、一般ユーザーも実行可) |
| 疎通確認の結果保存 | 保存しない(既存設計を踏襲、リクエストの都度計算して返すのみ) |
| 編集エンドポイント | `PUT /api/sites/{id}`。admin限定(既存の削除・reprovisionと同じ権限方針) |
| 編集可能フィールド | `name`(常時)、`credentials`(非managedサイトのみ、部分指定可・未指定フィールドは既存値を保持) |
| 編集不可フィールド | `siteKey`(様々な箇所でlookup keyとして使われるため)、`cmsType`(CMS種別変更は認証情報の形が全く変わるため非対応、変更したい場合は新規サイト登録を案内)、managedサイトの`credentials`/`baseUrl`(インフラ側で自動管理されているため) |
| レガシー認証情報の統合 | 編集時、対象サイトが`credentialsEncrypted`を持たず旧列のみの場合、この編集を機に`credentialsEncrypted`へ統合して保存する(以後`wpUsername`/`wpAppPasswordEncrypted`は参照しなくなる) |
| 編集後の疎通確認 | `credentials`を変更した場合、保存後に自動で`testConnection`を実行し、結果をレスポンスに含める(登録時と同じ体験) |

## アーキテクチャ・実装詳細

### バックエンド

#### 新規DTO: `SiteUpdateRequest.java`

```java
public record SiteUpdateRequest(
        String name,                        // null許容(未指定なら変更しない)
        Map<String, String> credentials     // null許容。非managedサイトのみ有効。指定されたキーのみ上書き
) {
}
```

#### `SiteService.java` に追加

```java
@Transactional
public SiteResponse update(Long id, SiteUpdateRequest request) {
    Site site = siteRepository.findById(id)
            .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));

    if (request.name() != null && !request.name().isBlank()) {
        site.setName(request.name());
    }

    Boolean connectionOk = null;
    if (request.credentials() != null && !request.credentials().isEmpty()) {
        if (site.isManagedWordpress()) {
            throw new IllegalArgumentException("自動構築されたWordPressサイトの認証情報は編集できません");
        }
        Map<String, String> merged = new HashMap<>(readCredentialsJson(site)); // 既存値(レガシー列含む)を復号
        merged.putAll(request.credentials()); // 指定されたフィールドのみ上書き
        validateCredentials(site.getCmsType(), merged);
        writeCredentialsJson(site, merged); // credentialsEncrypted列へ統合保存(レガシー列は以後未使用)
        connectionOk = testConnection(site.getCmsType(), merged);
    }

    Site saved = siteRepository.save(site);
    return connectionOk != null ? SiteResponse.from(saved, connectionOk) : SiteResponse.from(saved);
}

@Transactional(readOnly = true)
public boolean checkConnection(Long id) {
    Site site = siteRepository.findById(id)
            .orElseThrow(() -> new SiteNotFoundException("id " + id + " のサイトは登録されていません"));
    CmsCredentials credentials = getCredentials(site.getSiteKey());
    CmsAdapter adapter = cmsAdapterFactory.resolve(site.getCmsType());
    try {
        return adapter.testConnection(credentials);
    } catch (Exception e) {
        return false;
    }
}
```

既存の`readCredentialsJson`/`writeCredentialsJson`/`validateCredentials`/`testConnection(CmsType, Map)`は private のため、上記から呼べるよう private のままクラス内で共有する(新規publicメソッドを追加するだけで、既存privateメソッドのシグネチャ変更は不要)。

#### `SiteController.java` に追加

```java
@PutMapping("/{id}")
public SiteResponse update(@PathVariable Long id, @RequestBody SiteUpdateRequest request) {
    adminAuthorizationService.requireAdmin();
    return siteService.update(id, request);
}

@PostMapping("/{id}/test-connection")
public Map<String, Object> testConnection(@PathVariable Long id) {
    boolean ok = siteService.checkConnection(id);
    return Map.of("connectionCheckStatus", ok ? "SUCCESS" : "FAILED");
}
```

### フロントエンド

#### `apiClient.ts`

```typescript
export function updateSite(
  id: number,
  input: { name?: string; credentials?: Record<string, string> },
  actor?: ActorInfo
): Promise<Site> {
  return apiFetch<Site>(`/api/sites/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function checkSiteConnection(id: number): Promise<{ connectionCheckStatus: "SUCCESS" | "FAILED" }> {
  return apiFetch(`/api/sites/${id}/test-connection`, { method: 'POST' });
}
```

#### サイト一覧(`sites/page.tsx`)

「プロジェクト」列の隣に「疎通確認」列を追加(初期値は`-`、クリックで実行しその場で結果表示。既存の`DeleteSiteButton`と同様の`"use client"`ボタンコンポーネント`CheckConnectionButton.tsx`を新規追加し、`useTransition`+`useState`でSUCCESS/FAILEDを表示)。管理者行にのみ「編集」リンクを追加し`/sites/{id}/edit`へ遷移。

#### 新規ページ `web/src/app/sites/[id]/edit/page.tsx` + `SiteEditForm.tsx`

`users/[id]/edit`と同様のServer Component + Client Formパターンを踏襲。`site.managedWordpress`が`true`の場合は`name`のみ編集可能なフォームにし、認証情報欄自体を表示しない(誤操作防止)。`false`の場合は`cmsType`に応じた既存`SiteForm.tsx`の認証情報フィールド定義を再利用し、各値は初期状態では空欄(既存値は暗号化されており平文で画面に出さない方針。空欄のまま保存すれば変更なし、入力すればそのフィールドのみ上書き)。

## スコープ・実装項目

実装対象:

- [x] `SiteUpdateRequest.java`(新規)
- [x] `SiteService.java`: `update()`, `checkConnection()` 追加
- [x] `SiteController.java`: `PUT /api/sites/{id}`, `POST /api/sites/{id}/test-connection` 追加
- [x] `web/src/lib/apiClient.ts`: `updateSite()`, `checkSiteConnection()` 追加
- [x] `web/src/app/sites/CheckConnectionButton.tsx`(新規)
- [x] `web/src/app/sites/page.tsx`: 疎通確認列・編集リンク追加
- [x] `web/src/app/sites/[id]/edit/page.tsx`, `SiteEditForm.tsx`(新規)
- [x] `web/src/app/sites/[id]/edit/actions.ts`(新規、`updateSiteAction`)

対象外・スコープ外:

- `siteKey`/`cmsType`の変更
- managedサイトの`credentials`/`baseUrl`編集
- 疎通確認結果のDB永続化・履歴表示
- 既存の`POST /api/sites/{id}/reprovision`のUI導線追加(本フェーズのスコープ外、別要望が出た場合に対応)

## 実装順序

1. バックエンド: DTO・`SiteService`・`SiteController`
2. フロント: `apiClient.ts`
3. フロント: 疎通確認ボタン・一覧列追加
4. フロント: 編集ページ・フォーム・Server Action
5. テスト整備・実機検証

## テスト整備

- `SiteServiceTest`: `update()`が非managed/managedそれぞれで正しく動く・拒否すること、`credentials`部分更新後に既存フィールドが保持されること、レガシー列からの統合、`checkConnection()`が成功/失敗を正しく返すこと
- `SiteControllerTest`: `PUT`がadmin限定であること、`POST /test-connection`がadmin不問で呼べること

## 実機検証

1. 外部登録済み(非managed)サイトの表示名を編集→保存→一覧に反映されることを確認
2. 同サイトの`appPassword`を誤った値に変更して保存→疎通確認が`FAILED`になることを確認、正しい値に戻して`SUCCESS`に復帰することを確認
3. managedサイトの編集画面で認証情報欄が表示されず、名前のみ編集できることを確認
4. サイト一覧の「疎通確認」ボタンを押下→結果が即時表示されることを確認
