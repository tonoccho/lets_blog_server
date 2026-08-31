# 02. WordPress新規構築の堅牢化(エラークリーンアップ・管理者ピッカー・言語選択)

## 目的

Phase 7 で実装された WordPress 自動構築機能(常駐コンテナ上へのサブディレクトリ設置型インストール)について、3 つの改善を一括で行う:

1. **構築失敗時の自動クリーンアップ**: 途中エラーで部分的なディレクトリ・DB が残置される問題を解決
2. **管理者ユーザーピッカー**: サーバー登録済みのユーザーから管理者を選択可能にし、メール・ユーザー名の手入力を削減
3. **言語選択・インストール**: WordPress インストール時に言語を指定可能にし、翻訳済みコアを自動取得

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| クリーンアップ戦略 | エージェント側(`index.php`)の自己クリーンアップ + サービス側の保険呼び出し、両者冪等性確保 |
| クリーンアップ対象 | `rm -rf /var/www/html/sites/{slug}` + `DROP DATABASE IF EXISTS wp_{slug}` |
| デバッグ情報保持 | エラーレスポンスの `detail` フィールドに詳細を含める(部分生成物残置はしない) |
| 管理者ユーザー決定方法 | サーバー登録ユーザーから`<select>`で選択(メール・ユーザー名は自動入力、パスワードは手入力のまま) |
| WP ユーザー名生成ルール | メールのローカルパートから英数字・`._-` のみ抽出(`[^a-zA-Z0-9._-]` を除去) |
| 言語選択方式 | `wp core download --locale=$locale` でコア取得(wp-cli 別途`language core install` コマンド不要) |
| 対応言語 | ja(日本語), en_US, en_GB, zh_CN, zh_TW, ko_KR, fr_FR, de_DE, es_ES, pt_BR(計10言語) |
| デフォルト言語 | `ja`(未指定時) |
| ロケール検証 | `index.php` 側で許可リストチェック、許可リスト外は 400 reject |

## アーキテクチャ・実装詳細

### 全体フロー

```
ユーザー操作
  ↓
ManagedWordPressForm.tsx
  (新: 管理者ピッカー <select>、言語選択 <select>)
  ↓
SiteCreationPanel.tsx
  (新: listUsers() fetch してプロップ渡し)
  ↓
actions.ts: createManagedWordPressSiteAction
  (新: formData から locale 読み取り、API payload に含める)
  ↓
apiClient.ts: createManagedWordPressSite(...)
  (新: locale パラメータ追加)
  ↓ HTTP POST
APIサーバー: SiteController.createManagedWordPress(request)
  ↓
WordPressSiteProvisioningService.createManagedSite(request, actorId)
  (新: request.locale() をデフォルト"ja"で読み取り、ProvisionCommand に渡す)
  (新: provision() 呼び出し自体を try/catch、失敗時 deprovision() 呼出)
  ↓
WordPressProvisioningClient.provision(ProvisionCommand)
  (新: locale フィールド追加)
  ↓ HTTP POST http://wordpress:9000/provision
WordPress provisioning agent: index.php /provision handler
  (新: locale パラメータ受け取り、許可リスト検証)
  (新: core download にコマンドラインで --locale=$locale 付与)
  (新: core download 〜 application-password create の各失敗時に
       cleanupAndRespond() ヘルパー呼出、冪等な rm -rf + DROP DATABASE IF EXISTS 実行)
  ↓ レスポンス
成功/失敗情報をSpring Boot側に返却
```

### B-1: 失敗時の自動クリーンアップ

#### 現状の gap

`wordpress/provision-agent/index.php`(確認済み):
- Line 87-99: `mkdir` 成功後、`CREATE DATABASE` 失敗時のみ `rm -rf $sitePath` を実行(cleanup あり)
- Line 102-182: `wp core download`, `wp config create`, `wp core install`, `wp rewrite structure`, `wp user application-password create` の各失敗時は 500 応答するだけ(cleanup **なし**) → ディレクトリ・DB が残置

`WordPressSiteProvisioningService.createManagedSite`(確認済み):
- Line 51-54: `provisioningClient.provision(...)` 呼び出し
- Line 69-75: `siteService.register()` 失敗時のみ `deprovision()` 呼出(この段階では WP インストール成功だが DB 登録失敗)
- **Gap**: `provision()` 呼び出し自体の失敗(ネットワーク断など)に対するcleanup **なし**

#### 対応方針

**エージェント側(`index.php`):**
1. 新規ヘルパー関数 `cleanupAndRespond(int $status, array $body, string $sitePath, string $dbName, string $dbHost, string $rootPassword)` を定義(行番号 TBD、大体 line 55 付近)
   ```php
   function cleanupAndRespond(int $status, array $body, string $sitePath, string $dbName, 
                              string $dbHost, string $rootPassword): void {
       // mkdir 後・CREATE DATABASE 後に呼ばれることを想定
       // 既に作成されたディレクトリ・DB をクリーンアップ
       runCommand(['rm', '-rf', $sitePath]);
       runCommand(['mysql', '--skip-ssl', '-h', $dbHost, '-uroot', "-p$rootPassword", 
                   '-e', "DROP DATABASE IF EXISTS `$dbName`;"]);
       respond($status, $body);
   }
   ```
2. `core download` 以降の失敗分岐(line 102-182 の各 respond() 呼び出し)を `cleanupAndRespond()` 経由に置き換え
   - 既存の `mkdir` 失敗時(line 88-90)は "rm -rf はまだ不要"なので `respond()` のまま
   - `CREATE DATABASE` 失敗時(line 97-99)は既存の `rm -rf` のみなので **そのまま**
   - `core download`〜`application-password create` 失敗時(line 102-182 の~10箇所)を `cleanupAndRespond()` で置換

**サービス側(`WordPressSiteProvisioningService.java`):**
1. `provisioningClient.provision(...)` 呼び出し(line 51-54)をtry/catchでラップ:
   ```java
   WordPressProvisioningClient.ProvisionResult result;
   try {
       result = provisioningClient.provision(new ProvisionCommand(...));
   } catch (ProvisioningException e) {
       // エージェント側の自己クリーンアップが走ったか不明なケース(接続断など)の保険
       provisioningClient.deprovision(slug, dbName);
       throw e;
   }
   ```
2. 既存の「`siteService.register()` 失敗時の deprovision」(line 69-75)はそのまま(こちらは WP インストール成功・DB 登録失敗のケース、役割が異なる)

#### 冪等性について

既存の `/deprovision` エンドポイント(`index.php` line 194-206):
```php
if ($path === '/deprovision' && ...) {
    ...
    runCommand(['rm', '-rf', $sitePath]);  // 既に存在しなくても rm -rf はエラーにならない
    runCommand(['mysql', ..., "DROP DATABASE IF EXISTS `$dbName`;"]);  // IF EXISTS により冪等
    respond(200, ...);
}
```

→ `rm -rf` は対象が存在しなくてもエラーにならず、`DROP DATABASE IF EXISTS` も既存のまま。よって、エージェント側で既に cleanup → サービス側で重複 cleanup という二重実行シナリオでも**壊れない**ため、安心して両方実装できる。

### B-2: 管理者ユーザーピッカー

#### バックエンド(変更不要)

`CreateManagedWordPressSiteRequest`, `WordPressProvisioningClient.ProvisionCommand`, `index.php /provision` handler は、`adminUser`, `adminEmail`, `adminPassword` を単なる**自由入力文字列3つ**として受け付けるインターフェースのまま。バックエンド側は「どの server user から取得したのか」を知らない(フロント側の補助ロジックに過ぎない)。

#### フロントエンド(`ManagedWordPressForm.tsx`)

現状(確認済み):
```tsx
export function ManagedWordPressForm() {
  return (
    <form>
      <Field name="managedAdminUser" label="管理者ユーザー名" placeholder="admin" />
      <Field name="managedAdminEmail" label="管理者メールアドレス" placeholder="admin@example.com" type="email" />
      <Field name="managedAdminPassword" label="管理者パスワード" placeholder="8文字以上" type="password" />
    </form>
  );
}
```

修正内容:
1. Props で `users: AppUser[]` を受け取る(親 `SiteCreationPanel.tsx` から渡される)
2. 新規 `<select>` 追加(ラベル「サーバー登録ユーザーから選択(任意)」):
   ```tsx
   <label>
     <span>サーバー登録ユーザーから選択(任意)</span>
     <select onChange={(e) => {
       const userId = e.target.value;
       if (!userId) return;
       const user = users.find(u => u.id === parseInt(userId));
       if (user) {
         // adminUser, adminEmail を自動入力(手動調整可能)
         document.querySelector('input[name="managedAdminUser"]').value = 
           deriveWpUsername(user.email);  // メールから生成
         document.querySelector('input[name="managedAdminEmail"]').value = user.email;
       }
     }}>
       <option value="">選択してください</option>
       {users.map(u => <option key={u.id} value={u.id}>{u.email} ({u.displayName})</option>)}
     </select>
   </label>
   ```
3. `deriveWpUsername(email: string)` ヘルパー関数(同じファイル内):
   ```tsx
   function deriveWpUsername(email: string): string {
     // メールのローカルパート(@ の前)を取得
     const localPart = email.split('@')[0];
     // WP ユーザー名は英数字・`._-` のみ対応
     return localPart.replace(/[^a-zA-Z0-9._-]/g, '');
   }
   ```
   - 例: `seiji@example.com` → `seiji`
   - 例: `山田.太郎@example.com` → `..` (全角除去) → 空文字列の可能性 → フロント側で補助メッセージ(「WP ユーザー名に使える文字がありません」等)を表示
4. パスワード欄は変更**なし**(手入力のまま)

#### 親コンポーネント(`SiteCreationPanel.tsx`)

`ManagedWordPressForm` を呼び出す箇所(確認済み line 9-37):
```tsx
// Before
<ManagedWordPressForm />

// After (useEffect で listUsers() 取得)
const [users, setUsers] = useState<AppUser[]>([]);
useEffect(() => {
  listUsers().then(setUsers).catch(() => {});
}, []);
<ManagedWordPressForm users={users} />
```

#### スコープ外の明記

この管理者ピッカーは `wp core install --admin_user` の **ブートストラップ専用**。Phase 8 で実装された `ProjectUserSyncService.addUserToProject()` (プロジェクト参加時に WP 上に著者登録する仕組み)とは**無関係**。
- 選択したサーバーユーザーをそのサイトのプロジェクトメンバーとして自動リンク: **行わない**
- feedback に当該要求もなし

### B-3: 言語選択・インストール

#### Database & DTO 変更

`CreateManagedWordPressSiteRequest.java`:
```java
public record CreateManagedWordPressSiteRequest(
    @NotBlank String name,
    @NotBlank String siteKey,
    @NotBlank String title,
    @NotBlank String adminUser,
    @NotBlank @Email String adminEmail,
    @NotBlank String adminPassword,
    String locale  // 新規フィールド、nullable(未指定時はサービス層でデフォルト "ja" 適用)
) { }
```

`WordPressProvisioningClient.java`:
```java
public record ProvisionCommand(
    String slug, String dbName, String title,
    String adminUser, String adminEmail, String adminPassword,
    String locale  // 新規フィールド
) { }
```

#### サービス層

`WordPressSiteProvisioningService.createManagedSite()`:
```java
String slug = normalizeSlug(request.siteKey());
String locale = (request.locale() != null && !request.locale().isBlank()) 
    ? request.locale() : "ja";  // デフォルト ja
String dbName = "wp_" + slug;

var result = provisioningClient.provision(
    new ProvisionCommand(slug, dbName, request.title(), 
        request.adminUser(), request.adminEmail(), request.adminPassword(), 
        locale));  // 新規
```

#### エージェント側

`wordpress/provision-agent/index.php` /provision handler:

1. 許可リスト定義(line TBD, 大体 30-40行目):
   ```php
   $ALLOWED_LOCALES = ['ja', 'en_US', 'en_GB', 'zh_CN', 'zh_TW', 'ko_KR', 'fr_FR', 'de_DE', 'es_ES', 'pt_BR'];
   ```

2. Input 処理(line 68-74):
   ```php
   $locale = (string) ($input['locale'] ?? 'ja');
   if (!in_array($locale, $ALLOWED_LOCALES, true)) {
       respond(400, ['error' => 'ロケールが無効です']);
   }
   ```

3. `wp core download` 呼び出し(line 102):
   ```php
   // Before
   [$code, $out] = runWp(['core', 'download', "--path=$sitePath", '--allow-root']);
   
   // After
   [$code, $out] = runWp(['core', 'download', "--path=$sitePath", "--locale=$locale", '--allow-root']);
   ```

#### フロント側

`ManagedWordPressForm.tsx`:
```tsx
// 新規 field を追加(line TBD, 大体 29行目付近)
<Field 
  name="managedLocale" 
  label="WordPress言語" 
  isSelect={true}  // 新規プロップ、<select> にする
  defaultValue="ja"
  options={[
    { value: 'ja', label: '日本語' },
    { value: 'en_US', label: 'English (US)' },
    { value: 'en_GB', label: 'English (UK)' },
    { value: 'zh_CN', label: '中文(简体)' },
    { value: 'zh_TW', label: '中文(繁體)' },
    { value: 'ko_KR', label: '한국어' },
    { value: 'fr_FR', label: 'Français' },
    { value: 'de_DE', label: 'Deutsch' },
    { value: 'es_ES', label: 'Español' },
    { value: 'pt_BR', label: 'Português' },
  ]}
/>
```

`Field` コンポーネント修正(TBD):
```tsx
function Field({ name, label, isSelect, options, defaultValue, ... }) {
  if (isSelect) {
    return (
      <label>
        <span>{label}</span>
        <select name={name} defaultValue={defaultValue} required ...>
          {options?.map(opt => 
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          )}
        </select>
      </label>
    );
  }
  // 既存のテキスト input ロジック
}
```

`actions.ts: createManagedWordPressSiteAction`:
```typescript
// formData から locale を読み取り、API payload に含める
const locale = formData.get('managedLocale') as string;
const response = await createManagedWordPressSite({
  name, siteKey, title, adminUser, adminEmail, adminPassword, locale
});
```

`apiClient.ts`:
```typescript
export async function createManagedWordPressSite(params: {
  name: string;
  siteKey: string;
  title: string;
  adminUser: string;
  adminEmail: string;
  adminPassword: string;
  locale: string;  // 新規
}): Promise<SiteResponse> { ... }
```

#### リスク・注意点

- **翻訳取得失敗**: `wp core download --locale=$locale` が wordpress.org の翻訳 API に到達できない、または指定 locale の翻訳パッケージが存在しない場合、エラーになる可能性がある。この場合は素直にエラーを返す(自動フォールバック: 失敗時に en_US で retry 等は実装しない、エラーを握りつぶさない方針に合致)。B-1 のクリーンアップ機構でこのエラーもハンドル済み
- **ロケール文字列の検証**: `index.php` の許可リスト + `escapeshellarg()` 組み合わせにより、コマンドインジェクション対策は既に完全(許可リスト外の文字列は400で拒否)
- **バージョンの言語パック互換性**: WordPress core version とロケール翻訳の互換性は wordpress.org が担保するため、我々側で確認する必要なし

## スコープ・実装項目

実装対象:

- [x] `wordpress/provision-agent/index.php`: cleanupAndRespond() ヘルパー追加、失敗分岐の置換(B-1)、locale パラメータ受け取り・許可リスト検証(B-3)、`wp core download --locale=$locale` 追加
- [x] `api/src/main/java/com/letsblog/api/dto/CreateManagedWordPressSiteRequest.java`: locale フィールド追加
- [x] `api/src/main/java/com/letsblog/api/provisioning/WordPressProvisioningClient.java`: ProvisionCommand record に locale フィールド追加
- [x] `api/src/main/java/com/letsblog/api/service/WordPressSiteProvisioningService.java`: provision() 呼び出し try/catch 追加、request.locale() デフォルト処理
- [x] `web/src/app/sites/ManagedWordPressForm.tsx`: 管理者ピッカー <select> 追加、locale <select> 追加(B-2, B-3)
- [x] `web/src/app/sites/SiteCreationPanel.tsx`: ManagedWordPressForm への users props 受け渡し追加(B-2)
- [x] `web/src/app/sites/actions.ts`: formData から locale 読み取り、API 呼び出しに含める(B-3)
- [x] `web/src/lib/apiClient.ts`: createManagedWordPressSite() 関数に locale パラメータ追加
- [x] `WordPressSiteProvisioningServiceTest`: deprovision 呼び出し検証(B-1), locale 伝搬確認(B-3)

対象外・スコープ外:

- WordPress 言語パックの事前キャッシング・バージョン管理
- 翻訳取得失敗時の自動フォールバック(en_US retry など)
- 選択ユーザーのプロジェクトメンバー自動リンク(B-2, スコープ外)
- WordPress 側への管理者ユーザー情報のプロフィール同期(Phase 8 未決事項、保留)

## 実装順序

1. エージェント側(`index.php`)の cleanupAndRespond() ヘルパー実装、失敗分岐置換(B-1)
2. サービス側 try/catch 追加(B-1)
3. DTO/ProvisionCommand に locale フィールド追加(B-3)
4. エージェント側 locale 処理追加(B-3)
5. フロント側: 言語セレクト実装(B-3)
6. フロント側: 管理者ピッカー実装(B-2)
7. テスト整備・手動検証

## テスト整備

- `WordPressSiteProvisioningServiceTest`:
  - `provision()` 成功時の既存テスト継承(回帰確認)
  - `provision() 呼び出し失敗時に deprovision() が呼ばれることを検証(mock client.deprovision() の呼び出し確認)`
  - locale='en_US' で provision() 呼び出し時、ProvisionCommand に locale が正しく渡されることを検証
- PHPエージェント(`index.php`):
  - 手動テスト: core download 失敗時に cleanupAndRespond() で rm -rf + DROP DATABASE が実行されることを確認(コンテナログ確認 `docker logs lbs-wordpress`)
  - 手動テスト: locale 許可リスト外('invalid_locale')で 400 レスポンスを確認
  - 手動テスト: locale='ja' で `wp core download --locale=ja` が実行されることを確認

## 実機検証

### B-1 クリーンアップ

1. WP 新規構築フォームで各フィールド入力
2.構築中、WordPressコンテナのプロセスを Kill するか、MySQL を一時的に止める等して provision 失敗を誘発
3. エラーメッセージがフロントに返却されることを確認
4. `docker exec lbs-wordpress ls /var/www/html/sites/{slug}/` で該当ディレクトリが削除されていることを確認
5. `docker exec lbs-mysql mysql -u root -p... -e "SHOW DATABASES;"` で `wp_{slug}` DB が削除されていることを確認

### B-2 管理者ピッカー

1. WP 新規構築フォーム内「サーバー登録ユーザーから選択」ドロップダウンを操作
2. ユーザーを選択すると、管理者ユーザー名・メールが自動入力されることを確認
3. 自動入力された値は編集可能(フィールドは disabled ではなく通常入力)であることを確認
4. パスワードフィールドは変わらず手入力であることを確認

### B-3 言語選択

1. WP 新規構築フォーム内「WordPress言語」ドロップダウンから言語を選択
2. 「構築する」を押すと、選択言語でコアがダウンロードされることを確認:
   - `locale='ja'` で構築 → `https://localhost/sites/{slug}/wp-admin/` で日本語 UI が表示される
   - `locale='en_US'` で構築 → 英語 UI が表示される
3. ロケール取得失敗(ネットワーク遮断等)を誘発した場合、エラーメッセージが返却されることを確認

