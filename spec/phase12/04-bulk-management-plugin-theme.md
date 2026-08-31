# 04. 一括管理: プラグイン・テーマの比較テーブル化

## 目的

一括管理パネルの「プラグイン」「テーマ」タブを、[03-bulk-management-category-tag.md](03-bulk-management-category-tag.md)と同じ比較テーブル方式へ置き換える。3環境それぞれの状態(未インストール/無効/有効)をドロップダウンで表示し、選択を変えて「反映」ボタンを押すとその環境へ即時反映する。項目(プラグイン/テーマ)単位の「削除」は3環境すべてから取り除く。zipアップロードによる一括インストール(Phase11-01、全managed環境へ同時インストール)は既存のまま維持する。

## 現状確認

- 現在のプラグイン/テーマタブは、slugと操作種別(インストール/有効化/無効化/削除)を選び、**全managed環境**へ同一操作を同時実行するフォーム方式(`BulkManagementService.execute()`)
- プラグイン/テーマの一覧取得エンドポイントは現状存在しない。既存のインストール処理内部で`wp plugin list --field=name --format=json`による存在確認は行っているが、これはAPIとして公開されていない
- [03-bulk-management-category-tag.md](03-bulk-management-category-tag.md)で新設する`BulkManagementService.applyToEnvironment()`(単一環境への単一操作適用+ログ保存)は、プラグイン/テーマの個別セル操作にそのまま使える。03の完了(`applyToEnvironment()`の実装)が本タスクの前提

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 比較テーブルの行の範囲 | プロジェクトに紐づくmanaged環境に存在するプラグイン(またはテーマ)の**slugの和集合**を1行として表示する。プラグイン/テーマのslug(wp-cliの`name`フィールド、ディレクトリ名相当)はwordpress.orgのインストールslugと同一のため、環境間の一致判定は単純な文字列一致でよい(カテゴリ/タグと異なり名寄せの曖昧さはない) |
| 状態の種類 | プラグイン: 「未インストール」「無効」「有効」の3状態。テーマ: 同じく3状態で表現するが、WordPressは1環境につき常に1テーマのみ有効という制約があるため、後述の通り「有効→無効」への直接遷移は提供しない |
| 状態セルの操作方法 | ドロップダウンで希望する状態を選択しても即時実行はしない。行ごとに「反映」ボタンを配置し、押下時点でその行の3環境のうち**現在の状態とドロップダウンの選択値が異なる環境だけ**に対して、下記の状態遷移表に従いwp-cliコマンドを順に実行する |
| プラグインの状態遷移 | 未インストール→有効: `PLUGIN_INSTALL`→`PLUGIN_ACTIVATE`を順に実行(前者が失敗したら後者は実行しない)。未インストール→無効: `PLUGIN_INSTALL`のみ。無効→有効: `PLUGIN_ACTIVATE`のみ。有効→無効: `PLUGIN_DEACTIVATE`のみ。(有効/無効)→未インストールはドロップダウンでは選択不可とし、「削除」ボタンで行う |
| テーマの状態遷移 | 未インストール→有効: `THEME_INSTALL`→`THEME_ACTIVATE`。未インストール→無効: `THEME_INSTALL`のみ。無効→有効: `THEME_ACTIVATE`のみ。**有効→無効はドロップダウンの選択肢自体をグレーアウトし選択不可にする**(WordPressは他のテーマを有効化することでしか間接的に切り替えられないため)。(有効/無効)→未インストールは選択不可、「削除」ボタンで行う |
| 削除 | 項目単位。押下で3環境(managedかつ現在インストール済み)すべてから`PLUGIN_DELETE`/`THEME_DELETE`を実行する(有効化中のテーマの削除はwp-cliが自然にエラーを返す、Phase11の既存方針を踏襲。他テーマへの自動切替は行わない) |
| マスターとの差分表示 | [03](03-bulk-management-category-tag.md)のカテゴリ/タグと同じ視覚方針を踏襲し、マスター環境と状態が異なるセルは赤字で表示する(要望のサンプル表には赤字表示の明記はないが、一貫性のため拡張する。実装時に不要と判断されれば省略してよい任意強化項目とする) |
| ページネーション | カテゴリ/タグと同じく20件/ページとする(要望のサンプルには明記がないが、一貫性のための拡張。プラグイン/テーマ数が20件を超えないプロジェクトでは実質的に影響しない) |
| 新規インストール(まだどの環境にも存在しないslugを追加する) | テーブル上部に「新規インストール」フォーム(slug入力+環境選択の`<select>`)を設け、`applyToEnvironment(environment=選択した1環境, type=PLUGIN_INSTALL/THEME_INSTALL, value=slug)`を実行する。全環境への同時インストールはzipアップロード機能(後述)でのみ提供し、SLUG指定は常に単一環境ずつとする(誤操作時の影響範囲を小さくするため) |
| zipアップロードの扱い | Phase11-01の`executeFromUpload()`(全managed環境への同時インストール)は**変更せず維持**する。比較テーブルの行(dropdown+反映)とは独立した操作として、プラグイン/テーマタブの比較テーブル下部に既存のzipアップロードフォームをそのまま配置する(単一環境への絞り込みは行わない。用途が「同一のカスタムビルドを全環境に配る」ことであり、環境間の状態比較・差分という概念になじまないため) |
| 反映時の一部失敗 | 行の「反映」1回の中で複数環境・複数コマンドを実行する場合、1つが失敗しても残りは実行を続ける(Phase11の「1環境の失敗が他環境を止めない」方針を踏襲)。結果はコマンド単位で作業ログに記録され、実行後にインラインで環境ごとの結果を表示する |
| 権限・確認UX | 権限は既存と同じく管理者のみ。「反映」は`window.confirm`(「{slug}について、変更した環境の状態を反映します。よろしいですか?」)、「削除」も`window.confirm` |

## アーキテクチャ・実装詳細

### 全体フロー(比較テーブルの表示)

```
プロジェクト詳細画面(BulkManagementPanel.tsx、プラグイン/テーマタブ)
  ↓ ページロード時にfetch
apiClient.ts: listPluginComparison(projectId, page) / listThemeComparison(projectId, page)
  ↓ HTTP GET /api/projects/{id}/bulk-management/plugins/comparison?page=0 (or /themes/comparison)
ProjectController → PluginThemeComparisonService.listPluginComparison(projectId, page, size=20)
  1. managed環境ごとにWordPressBulkManagementClient.listPlugins(slug)を呼び出す(最大3回)
  2. slugをキーに結果をマージ(未インストールの環境はstatus=NOT_INSTALLED)
  3. マスター環境の状態と異なるセルにdiffフラグを立てる
  4. 20件単位でページングして返す
  ↓
レスポンス: { items: PluginComparisonRow[], totalCount, page, size, masterEnvironment }
```

### 全体フロー(反映・削除)

```
反映(行の「反映」ボタン)
  ↓ フロント側で、行の現在状態と選択中ドロップダウン値を比較し、環境ごとに必要なコマンド列を算出
  ↓ Server Action: reconcilePluginStateAction(projectId, slug, changes: { environment, desiredStatus }[])
apiClient.ts: reconcilePluginState(projectId, { slug, changes }, actor)
  ↓ HTTP POST /api/projects/{id}/bulk-management/plugins/reconcile (or /themes/reconcile)
ProjectController.reconcilePluginState(id, request)
PluginThemeComparisonService.reconcilePlugin(projectId, slug, changes, actorId)
  for each change in changes:
    現在状態→希望状態の遷移表に従い、1〜2個のBulkManagementService.applyToEnvironment(environment, PLUGIN_INSTALL/ACTIVATE/DEACTIVATE, value=slug, ...)を順に呼ぶ
  ↓
レスポンス: 実行した全コマンドの結果一覧(BulkOperationLogResponse[])

削除
  ↓ Server Action: deletePluginEverywhereAction(projectId, slug)
apiClient.ts: deletePluginEverywhere(projectId, slug, actor)
  ↓ HTTP POST /api/projects/{id}/bulk-management/plugins/delete-all (or /themes/delete-all)
PluginThemeComparisonService.deletePluginEverywhere(projectId, slug, actorId)
  → 現在インストール済みの各managed環境について BulkManagementService.applyToEnvironment(environment, PLUGIN_DELETE, value=slug, ...)
  ↓
レスポンス: 環境ごとの結果一覧
```

### `wordpress/provision-agent/index.php`の変更

```php
if ($path === '/plugins' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    if (!isValidSlug($slug)) { respond(400, ['error' => 'パラメータが不正です']); }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) { respond(404, ['error' => 'サイトが見つかりません']); }
    [$code, $out] = runWp(['plugin', 'list', '--format=json', '--fields=name,status', "--path=$sitePath", '--allow-root']);
    respond(200, ['plugins' => $code === 0 ? (json_decode($out, true) ?: []) : []]);
}

if ($path === '/themes' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    // 同様に wp theme list --format=json --fields=name,status
    respond(200, ['themes' => $code === 0 ? (json_decode($out, true) ?: []) : []]);
}
```

(既存の`plugin_install`/`plugin_activate`/`plugin_deactivate`/`plugin_delete`/`theme_install`/`theme_activate`/`theme_delete`ハンドラは変更不要。本タスクはこれらを単一環境ずつ`applyToEnvironment()`経由で呼び出すだけであり、provision-agent側の実行ロジック自体は03までの実装がそのまま使える)

### バックエンド(Spring Boot)

`WordPressBulkManagementClient.java`に追加:

```java
public List<PluginThemeInfo> listPlugins(String slug) { /* POST /plugins、name+statusをマッピング */ }
public List<PluginThemeInfo> listThemes(String slug) { /* POST /themes */ }
public record PluginThemeInfo(String name, String status) {} // status: "active" | それ以外(未インストールはリストに現れない)
```

`PluginThemeComparisonService.java`(新規):

```java
@Service
public class PluginThemeComparisonService {
    public PluginComparisonPage listPluginComparison(Long projectId, int page, int size) { ... }
    public PluginComparisonPage listThemeComparison(Long projectId, int page, int size) { ... }

    public List<BulkOperationLog> reconcilePlugin(Long projectId, String slug, List<StateChange> changes, Long actorId) { ... }
    public List<BulkOperationLog> reconcileTheme(Long projectId, String slug, List<StateChange> changes, Long actorId) { ... }

    public List<BulkOperationLog> deletePluginEverywhere(Long projectId, String slug, Long actorId) { ... }
    public List<BulkOperationLog> deleteThemeEverywhere(Long projectId, String slug, Long actorId) { ... }

    public record StateChange(String environment, String desiredStatus) {} // desiredStatus: NOT_INSTALLED/INACTIVE/ACTIVE
}
```

`reconcilePlugin`の状態遷移判定(要点):

```java
private List<BulkOperationType> stepsFor(String currentStatus, String desiredStatus, boolean isTheme) {
    if (currentStatus.equals(desiredStatus)) return List.of(); // 反映不要
    if (currentStatus.equals("NOT_INSTALLED") && desiredStatus.equals("ACTIVE"))
        return isTheme ? List.of(THEME_INSTALL, THEME_ACTIVATE) : List.of(PLUGIN_INSTALL, PLUGIN_ACTIVATE);
    if (currentStatus.equals("NOT_INSTALLED") && desiredStatus.equals("INACTIVE"))
        return List.of(isTheme ? THEME_INSTALL : PLUGIN_INSTALL);
    if (currentStatus.equals("INACTIVE") && desiredStatus.equals("ACTIVE"))
        return List.of(isTheme ? THEME_ACTIVATE : PLUGIN_ACTIVATE);
    if (currentStatus.equals("ACTIVE") && desiredStatus.equals("INACTIVE") && !isTheme)
        return List.of(PLUGIN_DEACTIVATE);
    throw new IllegalArgumentException("この状態遷移はサポートされていません: " + currentStatus + " → " + desiredStatus);
}
```

`dto/PluginComparisonRow.java`・`ThemeComparisonRow.java`(新規): `record PluginComparisonRow(String slug, PluginEnvironmentValue local, PluginEnvironmentValue test, PluginEnvironmentValue production)`
`dto/PluginEnvironmentValue.java`(新規): `record PluginEnvironmentValue(boolean available, String status, boolean matchesMaster)`(`status`: `NOT_INSTALLED`/`INACTIVE`/`ACTIVE`)
`dto/PluginComparisonPage.java`・`ThemeComparisonPage.java`(新規、03の`TermComparisonPage`と同じ形)
`dto/ReconcileStateRequest.java`(新規): `record ReconcileStateRequest(@NotBlank String slug, @NotEmpty List<StateChangeRequest> changes) {}`、`record StateChangeRequest(@NotBlank String environment, @NotBlank String desiredStatus) {}`
`dto/DeleteSlugRequest.java`(新規): `record DeleteSlugRequest(@NotBlank String slug) {}`

`ProjectController.java`に追加:

```java
@GetMapping("/{id}/bulk-management/plugins/comparison")
public PluginComparisonPage pluginComparison(@PathVariable Long id, @RequestParam(defaultValue = "0") int page) { ... }

@GetMapping("/{id}/bulk-management/themes/comparison")
public ThemeComparisonPage themeComparison(@PathVariable Long id, @RequestParam(defaultValue = "0") int page) { ... }

@PostMapping("/{id}/bulk-management/plugins/reconcile")
public List<BulkOperationLogResponse> reconcilePlugin(@PathVariable Long id, @Valid @RequestBody ReconcileStateRequest request) { ... }

@PostMapping("/{id}/bulk-management/themes/reconcile")
public List<BulkOperationLogResponse> reconcileTheme(@PathVariable Long id, @Valid @RequestBody ReconcileStateRequest request) { ... }

@PostMapping("/{id}/bulk-management/plugins/delete-all")
public List<BulkOperationLogResponse> deletePluginEverywhere(@PathVariable Long id, @Valid @RequestBody DeleteSlugRequest request) { ... }

@PostMapping("/{id}/bulk-management/themes/delete-all")
public List<BulkOperationLogResponse> deleteThemeEverywhere(@PathVariable Long id, @Valid @RequestBody DeleteSlugRequest request) { ... }
```

### フロントエンド

`web/src/app/projects/[id]/BulkManagementPanel.tsx`のプラグイン/テーマタブに`PluginThemeComparisonTable`コンポーネント(新規、`kind: "plugin" | "theme"`)を追加:

- 「新規インストール」フォーム(slug入力+環境`<select>`、1環境のみ選択)
- テーブル本体: 1行=1slug、列=ローカル/テスト/本番のステータス`<select>`(未インストール/無効/有効。テーマの場合、現在「有効」なセルは他の値へ変更不可・現在「未インストール」または「無効」のセルには「有効」がグレーアウトされない)+「反映」ボタン+「削除」ボタン
  - `<select>`の選択値はローカルstateで保持し、初期値=現在の状態
  - 「反映」押下時、初期値と異なるセルのみを`changes`としてServer Actionへ渡す。マスター以外で差分があるセルは赤字表示(オプション)
- 既存のzipアップロードフォーム(Phase11-01のまま)をテーブル下部に配置
- ページネーション: 03と同じ20件/ページ方式

`web/src/lib/apiClient.ts`: `PluginComparisonRow`/`ThemeComparisonRow`等の型、`listPluginComparison()`/`listThemeComparison()`/`reconcilePluginState()`/`reconcileThemeState()`/`deletePluginEverywhere()`/`deleteThemeEverywhere()`を追加。

`web/src/app/projects/[id]/actions.ts`: `reconcilePluginStateAction`/`reconcileThemeStateAction`/`deletePluginEverywhereAction`/`deleteThemeEverywhereAction`を追加。

`web/src/app/projects/[id]/page.tsx`: `listPluginComparison`/`listThemeComparison`の初期ページ取得を`Promise.all`に追加。

## スコープ・実装項目

実装対象:

- [ ] `wordpress/provision-agent/index.php`: `/plugins`・`/themes`エンドポイント(新規)
- [ ] `api/src/main/java/com/letsblog/api/provisioning/WordPressBulkManagementClient.java`: `listPlugins()`・`listThemes()`
- [ ] `api/src/main/java/com/letsblog/api/service/PluginThemeComparisonService.java`(新規)
- [ ] `api/src/main/java/com/letsblog/api/dto/PluginComparisonRow.java`・`ThemeComparisonRow.java`・`PluginEnvironmentValue.java`・`PluginComparisonPage.java`・`ThemeComparisonPage.java`・`ReconcileStateRequest.java`・`DeleteSlugRequest.java`(新規)
- [ ] `ProjectController.java`: `/plugins/comparison`・`/themes/comparison`・`/plugins/reconcile`・`/themes/reconcile`・`/plugins/delete-all`・`/themes/delete-all`
- [ ] `web/src/lib/apiClient.ts`: 型・関数追加
- [ ] `web/src/app/projects/[id]/PluginThemeComparisonTable.tsx`(新規)
- [ ] `web/src/app/projects/[id]/BulkManagementPanel.tsx`: プラグイン/テーマタブの差し替え
- [ ] `web/src/app/projects/[id]/page.tsx`: データ取得追加
- [ ] `web/src/app/projects/[id]/actions.ts`: Server Action追加

対象外・スコープ外:

- zipアップロードによるインストールの単一環境への絞り込み(全managed環境への同時インストールのまま維持)
- 有効テーマを直接無効化する操作(他テーマの有効化による間接的な切替のみ)
- プラグイン/テーマのバージョン管理・更新通知

## 実装順序

(前提: [03-bulk-management-category-tag.md](03-bulk-management-category-tag.md)の`BulkManagementService.applyToEnvironment()`が実装済みであること)

1. provision-agent: `/plugins`・`/themes`エンドポイント
2. `WordPressBulkManagementClient.listPlugins()`・`listThemes()`
3. `PluginThemeComparisonService`・DTO群 → `ProjectController`エンドポイント追加
4. フロント: `apiClient.ts` → `PluginThemeComparisonTable.tsx` → `BulkManagementPanel.tsx` → `page.tsx` → `actions.ts`
5. テスト整備・実機検証

## テスト整備

- `PluginThemeComparisonServiceTest`(新規): 3環境のslug一覧を正しくマージしステータスを判定すること、`stepsFor()`の全遷移パターン(未インストール→有効/無効、無効→有効、有効→無効(プラグインのみ)、サポート外遷移で例外)、`reconcilePlugin`が変更のあった環境のみに対してコマンドを実行すること、1コマンドの失敗が後続の環境への実行を止めないこと、`deletePluginEverywhere`が現在インストール済みの環境のみを対象にすること
- `ProjectControllerTest`: 各新規エンドポイントのadmin権限チェック、テーマの「有効→無効」への遷移リクエストが400になること

## 実機検証

1. プラグインタブで、あるプラグインが未インストールのローカル環境のドロップダウンを「有効」に変更し「反映」を押すと、インストール+有効化が実行されることを確認
2. 有効なプラグインを「無効」に変更・反映すると無効化されることを確認
3. テーマタブで、現在有効なテーマのドロップダウンから「無効」が選択できない(グレーアウトされている)ことを確認
4. 「削除」で3環境すべてから該当プラグイン/テーマが削除されることを確認(有効化中のテーマ削除はエラーになりログにFAILEDとして記録されることも確認)
5. zipアップロードによる一括インストールが引き続き全managed環境へ同時インストールされることを確認(Phase11からの回帰がないこと)
6. 21件以上のプラグインを用意し、ページネーションが正しく機能することを確認
