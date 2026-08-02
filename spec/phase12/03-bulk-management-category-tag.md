# 03. 一括管理: カテゴリ・タグの比較テーブル化

## 目的

一括管理パネルの「カテゴリ」タブを、Phase11で実装した「操作(作成/編集/削除)を選び値を入力して全managed環境へ同時実行する」フォーム方式から、**3環境(ローカル/テスト/本番)の値を横並びで比較するテーブル**方式へ置き換える。マスター環境([02-master-environment-setting.md](02-master-environment-setting.md)で設定)と異なる値は赤字で表示し、項目単位で「編集」「削除」「マスターへの同期」ができるようにする。同じ構造で「タグ」タブ(WordPressの`post_tag`タクソノミー)を新設する。20件ごとにページネーションする。作業ログ一覧・ロールフォワード機能は既存のまま維持する。

## 現状確認

- 現在の[BulkManagementPanel.tsx](../../web/src/app/projects/%5Bid%5D/BulkManagementPanel.tsx)は「対象(カテゴリ/プラグイン/テーマ)→操作(作成/編集/削除等)」の二階層タブ+フォーム方式で、1回の実行につき1件の値を**全managed環境**へ同時適用する(`BulkManagementService.execute()`)
- カテゴリの一覧取得は`GET /bulk-management/categories`(`BulkManagementService.listReferenceCategories()`)のみで、**1つの参照環境(local→test→production優先順で最初に見つかったもの)**のカテゴリしか返さない。3環境を横断した比較はできない
- タグ(WordPressの`post_tag`タクソノミー)に対応する操作は現状皆無。カテゴリと同じくprovision-agent経由のwp-cli(`wp term create/update/delete/list`のtaxonomy引数を`post_tag`にする)で実現でき、タグは階層を持たないため「親」の概念がない
- [BulkOperationLog.java](../../api/src/main/java/com/letsblog/api/domain/BulkOperationLog.java)は`category_slug`/`category_parent_slug`/`category_target_slug`/`category_description`の4カラムを持つ。これらはカラム名こそ`category_*`だが、実体は「タクソノミー項目の識別・属性を保持する汎用カラム」であり、タグに転用してもデータ上の矛盾はない(名称のリネームは行わず、コメントで用途を明記する)
- `BulkManagementService.replay()`は、過去のログ1件ずつを**指定した1環境のみ**へ再適用するロジックを既に持っている(`applyFromHistory()` → `bulkManagementClient.apply(...)`を1環境分だけ呼ぶ)。これは本タスクで必要な「単一環境への適用」とまったく同じ形であり、そのまま再利用・共通化できる

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 比較テーブルの行(=項目)の同一性判定 | **名前(大文字小文字を無視した完全一致)**で同一項目とみなす(Phase11-01の初版の判定条件と同じ)。スラッグは判定キーではなく「比較・編集対象の属性の1つ」として扱う(要望のサンプル表でスラッグが環境ごとに異なりうる列として描かれているため)。名前自体が環境間で異なる場合は別項目として扱われ、和集合上は別行になる(名前を変更したい場合は対象環境を直接編集する運用とし、リネームを追跡する自動同期は本フェーズでは対象外) |
| 比較テーブルの行の範囲 | プロジェクトに紐づく3環境スロットのうち、**managedWordpressな環境**に存在するカテゴリ/タグの**名前の和集合**を1行として表示する。非managed・未紐付けの環境スロットは列として表示するが値は常に「対象外」とし、diff判定・編集・削除・同期の対象から除く |
| 表示列 | カテゴリ: 名前・スラッグ・親カテゴリ名・説明の4行(要望のサンプル表通り)。タグ: 名前・スラッグ・説明の3行(タグは階層を持たないため親の行はなし) |
| マスターとの差分表示 | 各属性値を**マスター環境の値と文字列完全一致で比較**し、異なれば赤字表示する。マスター環境自身の列は常に基準色(赤字にしない)。マスター環境にその項目が存在しない行(非マスター環境にのみ存在する項目)は、非マスター側のセルをすべて赤字にする(「マスターに存在しない」ことを示す) |
| ページネーション | 名前の和集合を名前の昇順でソートし、20件/ページで表示する(カテゴリ・タグそれぞれ独立してページングする) |
| 新規追加 | 各タブ上部に「+ 新規追加」フォームを設け、**マスター環境のみ**に新規作成する(既存の`CATEGORY_CREATE`/新設`TAG_CREATE`をマスター環境1つに対して実行)。作成直後は非マスター環境に存在しないため、一覧再読み込み後にその行が赤字(マスターにのみ存在)で表示される。他環境へ反映するには「同期」を使う |
| 編集 | 項目(行)単位。「編集」ボタン押下でその行の**マスター環境側の現在値**(名前・スラッグ・親・説明)をフォームに反映し、保存すると**マスター環境のみ**を更新する(`CATEGORY_EDIT`/`TAG_EDIT`)。他環境は更新しない(反映は「同期」で別途行う) |
| 削除 | 項目単位。押下で**3環境すべて(managedかつその項目が存在する環境)**から該当項目を削除する。各環境はその環境自身が持つ現在のスラッグを使って削除する(環境間でスラッグが一致していなくても、名前が一致していれば削除対象とみなす)。存在しない環境はスキップ済みとして扱う(Phase11の冪等性方針を踏襲) |
| 同期 | 項目単位。**マスター環境の現在値**(名前・スラッグ・親カテゴリ名・説明)を、**非マスターの2環境**へ反映する。対象環境に同名の項目が既にあれば更新(スラッグも含めてマスターの値に上書きする。スラッグのずれを解消する目的を含む)、なければマスターの値で新規作成する。マスター環境にその項目が存在しない行では「同期」ボタンを非表示(または無効化)にする(同期元がないため) |
| 親カテゴリの同期 | 親もカテゴリ名で解決するのではなく**スラッグ**で対象環境側に問い合わせて解決する(provision-agentの既存`findCategoryBySlug`と同じ方式)。親カテゴリが対象環境に存在しない場合、その環境の同期は失敗として記録する(黙って親なしで作成しない、既存方針を踏襲)。階層全体を1回で同期する自動再帰は行わない(親を先に同期してから子を同期する、という順序はユーザーの操作に委ねる) |
| 単一環境への適用の共通化 | `BulkManagementService`に新規メソッド`applyToEnvironment(projectId, environment, type, value, categorySlug, categoryParentSlug, categoryDescription, categoryTargetSlug, actorId)`を追加する。`replay()`内の`applyFromHistory()`相当のロジック(1環境だけを解決して`bulkManagementClient.apply(...)`を呼び、1件の`BulkOperationLog`を保存する)をこのメソッドとして切り出し、`replay()`・本タスクの「新規追加」「編集」・[04-bulk-management-plugin-theme.md](04-bulk-management-plugin-theme.md)のプラグイン/テーマ操作すべてから共通で使う |
| 旧`execute()`の扱い | 「1操作を全managed環境へ同時適用する」という`execute()`・`POST /api/projects/{id}/bulk-management`(JSON)・`BulkOperationRequest`・`GET /bulk-management/categories`(参照環境1つのみ)は、比較テーブルUIからは一切呼ばれなくなるため**削除する**(未使用コードを残さない方針、CLAUDE.mdの禁止事項にも合致)。`executeFromUpload()`(zipアップロード、全managed環境へ一括インストール)は04で引き続き使うため維持する |
| BulkOperationTypeの追加 | `TAG_CREATE`/`TAG_EDIT`/`TAG_DELETE`を追加する。`wpCliAction()`は既存と同じく列挙子名の小文字化(`tag_create`等)をそのまま使う |
| provision-agentの拡張 | 既存の`fetchCategories($sitePath)`・`findCategoryBySlug()`・`category_create`/`category_edit`/`category_delete`ハンドラを**taxonomy引数**(`category`/`post_tag`)で共用できるよう内部関数を`fetchTerms($sitePath, $taxonomy)`にリネームして一般化し、`tag_create`/`tag_edit`/`tag_delete`アクションを追加する(`--parent`オプションは`category`の時のみ付与、`post_tag`では常に無視する)。一覧取得は既存`/categories`に加え、同じ構造の`/tags`エンドポイントを新設する(`/categories`のレスポンス形式・呼び出し規約は変更しない、既存の`listReferenceCategories`実装は削除するが provision-agent 側の`/categories`自体は比較テーブルの1環境分取得にも引き続き使う) |
| 比較データ取得の実現方式 | Spring Boot側で、プロジェクトに紐づくmanaged環境ごとに`WordPressBulkManagementClient.listCategories(slug)`(または新設`listTags(slug)`)を呼び出し(最大3回)、名前をキーに結果をマージして`TermComparisonRow`のリストを組み立てる。provision-agent側でのマルチサイト集約は行わない(既存の1環境1リクエストパターンを踏襲) |
| 権限 | 既存と同じく管理者のみ |
| 確認UX | 削除・同期は`window.confirm`。新規追加・編集はフォーム送信のみ(確認なし、既存の`CATEGORY_CREATE`/`CATEGORY_EDIT`と同じ) |
| タイムアウト対策 | 比較データ取得(3環境分の逐次リクエスト)・同期(2環境分)は数秒程度で完了する軽量な処理のため、既存の`/api/`既定タイムアウトで問題ない(300秒延長locationは不要) |

## アーキテクチャ・実装詳細

### 全体フロー(比較テーブルの表示)

```
プロジェクト詳細画面(再編後のBulkManagementPanel.tsx、カテゴリ/タグタブ)
  ↓ ページロード時にfetch(page.tsxのPromise.allに追加)
apiClient.ts: listCategoryComparison(projectId, page) / listTagComparison(projectId, page)
  ↓ HTTP GET /api/projects/{id}/bulk-management/categories/comparison?page=0
ProjectController → TermComparisonService.listCategoryComparison(projectId, page, size=20)
  1. プロジェクトのmasterEnvironmentを解決
  2. managed環境ごとにWordPressBulkManagementClient.listCategories(slug)を呼び出す(最大3回)
  3. 名前をキーに(名前で大文字小文字を無視して集約)結果をマージし、名前昇順でソート
  4. マスター環境の値と異なる属性にdiffフラグを立てる
  5. 20件単位でページングして返す
  ↓
レスポンス: { items: TermComparisonRow[], totalCount, page, size, masterEnvironment }
```

### 全体フロー(新規追加・編集・削除・同期)

```
新規追加/編集
  ↓ Server Action: applyToEnvironmentAction(projectId, environment=masterEnvironment固定, type, value, ...)
apiClient.ts: applyToEnvironment(projectId, { environment, operationType, value, categorySlug, categoryParentSlug, categoryDescription, categoryTargetSlug }, actor)
  ↓ HTTP POST /api/projects/{id}/bulk-management/apply
ProjectController.applyBulkOperation(id, request)
  ↓ adminAuthorizationService.requireAdmin()
  ↓ CATEGORY_CREATE/CATEGORY_EDIT/TAG_CREATE/TAG_EDITの場合、request.environment() が
     projectService.getMasterEnvironment(id) と一致しなければ400(「マスター環境以外への作成・編集はできません」)
BulkManagementService.applyToEnvironment(projectId, environment, type, value, ..., actorId)
  → 単一環境を解決 → WordPressBulkManagementClient.apply(...) → BulkOperationLog 1件保存
  ↓
レスポンス: BulkOperationLogResponse 1件

削除
  ↓ Server Action: deleteTermEverywhereAction(projectId, kind="category"|"tag", name)
apiClient.ts: deleteCategoryEverywhere(projectId, name, actor) / deleteTagEverywhere(...)
  ↓ HTTP POST /api/projects/{id}/bulk-management/categories/delete-all (or /tags/delete-all)
TermComparisonService.deleteCategoryEverywhere(projectId, name, actorId)
  1. 現在の比較データを取得し、対象名を持つ環境ごとの現在のスラッグを特定
  2. 各managed環境について、その項目が存在すればBulkManagementService.applyToEnvironment(environment, CATEGORY_DELETE, categoryTargetSlug=その環境の現在スラッグ, ...)を呼ぶ(存在しない環境はスキップ)
  ↓
レスポンス: 環境ごとの結果一覧(BulkOperationLogResponse[])

同期
  ↓ Server Action: syncTermToMasterAction(projectId, kind, name)
apiClient.ts: syncCategoryToMaster(projectId, name, actor) / syncTagToMaster(...)
  ↓ HTTP POST /api/projects/{id}/bulk-management/categories/sync (or /tags/sync)
TermComparisonService.syncCategory(projectId, name, actorId)
  1. 現在の比較データを取得し、マスター環境の値(名前・スラッグ・親カテゴリ名・説明)を特定
     (マスターに存在しなければ400「マスター環境に存在しない項目は同期できません」)
  2. 非マスターの各managed環境について:
     - 同名の項目が既にあれば BulkManagementService.applyToEnvironment(environment, CATEGORY_EDIT,
       value=マスターの名前, categorySlug=マスターのスラッグ, categoryParentSlug=マスターの親スラッグ,
       categoryDescription=マスターの説明, categoryTargetSlug=その環境の現在スラッグ)
     - なければ applyToEnvironment(environment, CATEGORY_CREATE, 同上・categoryTargetSlugなし)
  ↓
レスポンス: 環境ごとの結果一覧(BulkOperationLogResponse[])
```

### `wordpress/provision-agent/index.php`の変更

```php
// fetchCategories($sitePath) → fetchTerms($sitePath, $taxonomy) に一般化(既存の内部実装をtaxonomy引数化するだけ)
function fetchTerms(string $sitePath, string $taxonomy): array {
    [$code, $out] = runWp(['term', 'list', $taxonomy, '--format=json',
        '--fields=term_id,name,slug,parent,description', "--path=$sitePath", '--allow-root']);
    // parent(term_id)からparentSlugへ解決する既存ロジックはそのまま流用
}

const ALLOWED_BULK_ACTIONS = [
    'category_create', 'category_edit', 'category_delete',
    'tag_create', 'tag_edit', 'tag_delete',   // 追加
    'plugin_install', 'plugin_activate', 'plugin_deactivate', 'plugin_delete',
    'theme_install', 'theme_activate', 'theme_delete',
];

// category_create/category_edit/category_delete のハンドラは taxonomy='category' 固定で fetchTerms を呼ぶよう書き換え
// tag_create/tag_edit/tag_delete は taxonomy='post_tag' で同じロジックを呼ぶ(--parentは付与しない)
if ($action === 'tag_create') {
    $tagSlug = (string) ($input['categorySlug'] ?? '');
    // category_createと同じ流れ、taxonomy='post_tag'・--parentなし
}
// tag_edit / tag_delete も同様にcategory_edit/category_deleteのtaxonomy='post_tag'版として実装

if ($path === '/tags' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    // /categories と同じ構造、fetchTerms($sitePath, 'post_tag') を返す
    respond(200, ['tags' => array_values(fetchTerms($sitePath, 'post_tag'))]);
}
```

### バックエンド(Spring Boot)

`WordPressBulkManagementClient.java`に追加:

```java
public List<CategoryInfo> listTags(String slug) {
    // listCategories(slug) と同じ実装、URIのみ /tags に変更
}
```

`BulkOperationType.java`に追加:

```java
TAG_CREATE, TAG_EDIT, TAG_DELETE;
// isCategory() は isCategoryOrTag() にリネームし、TAG_*も含める
```

`BulkManagementService.java`:

- `execute()`・`GET /bulk-management/categories`用の`listReferenceCategories()`を削除
- 新規: `applyToEnvironment(Long projectId, String environment, BulkOperationType type, String value, String categorySlug, String categoryParentSlug, String categoryDescription, String categoryTargetSlug, Long actorId)`(`replay()`内の単一環境適用ロジックを共通化したもの、1件の`BulkOperationLog`を返す)
- `replay()`は内部で`applyToEnvironment`を1件ずつ呼ぶ形にリファクタリングする(既存の外部インターフェース・戻り値は変更しない)

`TermComparisonService.java`(新規):

```java
@Service
public class TermComparisonService {
    // カテゴリ・タグ共通のロジックをtaxonomy種別(CATEGORY/TAG)で分岐して実装する

    public TermComparisonPage listCategoryComparison(Long projectId, int page, int size) { ... }
    public TermComparisonPage listTagComparison(Long projectId, int page, int size) { ... }

    public List<BulkOperationLog> syncCategory(Long projectId, String name, Long actorId) { ... }
    public List<BulkOperationLog> syncTag(Long projectId, String name, Long actorId) { ... }

    public List<BulkOperationLog> deleteCategoryEverywhere(Long projectId, String name, Long actorId) { ... }
    public List<BulkOperationLog> deleteTagEverywhere(Long projectId, String name, Long actorId) { ... }
}
```

`dto/TermComparisonRow.java`(新規): `record TermComparisonRow(String name, TermEnvironmentValue local, TermEnvironmentValue test, TermEnvironmentValue production)`
`dto/TermEnvironmentValue.java`(新規): `record TermEnvironmentValue(boolean available, String slug, String parentSlug, String description, boolean matchesMaster)`(`available=false`は未紐付け・非managed・その環境に項目が存在しないのいずれか。`matchesMaster`は表示側で赤字判定に使う)
`dto/TermComparisonPage.java`(新規): `record TermComparisonPage(List<TermComparisonRow> items, int page, int size, long totalCount, String masterEnvironment)`
`dto/ApplyToEnvironmentRequest.java`(新規): `record ApplyToEnvironmentRequest(@NotBlank String environment, @NotNull BulkOperationType operationType, String value, String categorySlug, String categoryParentSlug, String categoryDescription, String categoryTargetSlug) {}`
`dto/SyncTermRequest.java` / `dto/DeleteTermRequest.java`(新規): `record SyncTermRequest(@NotBlank String name) {}` (削除も同じ形のため共通の`TermNameRequest`として1つにまとめてよい)

`ProjectController.java`:

```java
@PostMapping("/{id}/bulk-management/apply")
public BulkOperationLogResponse applyBulkOperation(@PathVariable Long id, @Valid @RequestBody ApplyToEnvironmentRequest request) {
    adminAuthorizationService.requireAdmin();
    if (request.operationType().requiresMasterEnvironment()) {
        String master = projectService.getMasterEnvironment(id);
        if (!master.equals(request.environment())) {
            throw new IllegalArgumentException("マスター環境(" + master + ")以外への作成・編集はできません");
        }
    }
    Long actorId = currentActorService.getCurrentActorId();
    return BulkOperationLogResponse.from(bulkManagementService.applyToEnvironment(
            id, request.environment(), request.operationType(), request.value(),
            request.categorySlug(), request.categoryParentSlug(), request.categoryDescription(),
            request.categoryTargetSlug(), actorId));
}

@GetMapping("/{id}/bulk-management/categories/comparison")
public TermComparisonPage categoryComparison(@PathVariable Long id, @RequestParam(defaultValue = "0") int page) {
    adminAuthorizationService.requireAdmin();
    return termComparisonService.listCategoryComparison(id, page, 20);
}

@GetMapping("/{id}/bulk-management/tags/comparison")
public TermComparisonPage tagComparison(@PathVariable Long id, @RequestParam(defaultValue = "0") int page) {
    adminAuthorizationService.requireAdmin();
    return termComparisonService.listTagComparison(id, page, 20);
}

@PostMapping("/{id}/bulk-management/categories/sync")
public List<BulkOperationLogResponse> syncCategory(@PathVariable Long id, @Valid @RequestBody TermNameRequest request) {
    adminAuthorizationService.requireAdmin();
    return toResponses(termComparisonService.syncCategory(id, request.name(), currentActorService.getCurrentActorId()));
}

@PostMapping("/{id}/bulk-management/categories/delete-all")
public List<BulkOperationLogResponse> deleteCategoryEverywhere(@PathVariable Long id, @Valid @RequestBody TermNameRequest request) {
    adminAuthorizationService.requireAdmin();
    return toResponses(termComparisonService.deleteCategoryEverywhere(id, request.name(), currentActorService.getCurrentActorId()));
}
// /tags/sync・/tags/delete-all も同様
```

(`BulkOperationType.requiresMasterEnvironment()`は`CATEGORY_CREATE`/`CATEGORY_EDIT`/`TAG_CREATE`/`TAG_EDIT`で`true`を返すヘルパーとして追加する)

`POST /api/projects/{id}/bulk-management`(JSON)・`GET /api/projects/{id}/bulk-management/categories`・`BulkOperationRequest.java`・`CategoryOptionResponse.java`は削除する。

### フロントエンド

`web/src/app/projects/[id]/BulkManagementPanel.tsx`を全面的に書き換える:

- タブ: カテゴリ/プラグイン/テーマ/タグ(本タスクではカテゴリ・タグの中身を実装、プラグイン・テーマは[04](04-bulk-management-plugin-theme.md)で実装)
- カテゴリ/タグタブ共通の`TermComparisonTable`コンポーネント(新規、`kind: "category" | "tag"`で親カテゴリ列の有無を切り替える):
  - 「+ 新規追加」フォーム(名前・スラッグ・親(カテゴリのみ、マスター環境の既存カテゴリからの選択式)・説明)。送信すると`applyToEnvironmentAction(environment=masterEnvironment, type=CATEGORY_CREATE/TAG_CREATE, ...)`
  - テーブル本体: 1行=1項目、列=名前/スラッグ/親/説明の属性ラベル+ローカル/テスト/本番の値+操作(編集/削除/同期)。マスター列以外でマスターと異なる値は`text-red-600`で表示
  - 「編集」: 行のマスター値をインラインフォーム(またはモーダル)に反映し、保存で`applyToEnvironmentAction(environment=masterEnvironment, type=CATEGORY_EDIT/TAG_EDIT, categoryTargetSlug=マスターの現在スラッグ, ...)`
  - 「削除」: `window.confirm`後に`deleteTermEverywhereAction`
  - 「同期」: マスターにその項目がない行では非表示。`window.confirm`(「{name}を{非マスター環境名}へ同期します。既存の内容は上書きされます。よろしいですか?」)後に`syncTermToMasterAction`
  - ページネーション: 20件/ページの「前へ/次へ」または番号付きページャー
- 既存の作業ログ一覧・ロールフォワードのセクションはそのまま維持する(操作種別ラベルに`TAG_CREATE`等を追加するのみ)

`web/src/app/projects/[id]/page.tsx`: `listReferenceCategories`の呼び出しを削除し、`listCategoryComparison(projectId, 0)`・`listTagComparison(projectId, 0)`の初期ページ取得を`Promise.all`に追加(2ページ目以降はクライアント側でfetchする、または`page.tsx`をpage検索パラメータ対応にする。実装時にどちらの方式にするかは実装者判断とするが、他のページネーションUIが本プロジェクトに存在しないため、まずはクライアントサイドfetch方式を推奨する)

`web/src/lib/apiClient.ts`: `TermComparisonRow`/`TermComparisonPage`/`TermEnvironmentValue`型、`BulkOperationType`に`TAG_CREATE`/`TAG_EDIT`/`TAG_DELETE`追加、`listCategoryComparison()`/`listTagComparison()`/`applyToEnvironment()`/`syncCategoryToMaster()`/`syncTagToMaster()`/`deleteCategoryEverywhere()`/`deleteTagEverywhere()`を追加。旧`runBulkOperation()`・`CategoryOption`型は削除する。

`web/src/app/projects/[id]/actions.ts`: `applyToEnvironmentAction`・`syncTermToMasterAction`・`deleteTermEverywhereAction`を追加し、旧`runBulkOperationAction`は削除する(`runBulkOperationUploadAction`・`replayBulkOperationsAction`は維持)。

## スコープ・実装項目

実装対象:

- [ ] `api/src/main/java/com/letsblog/api/domain/BulkOperationType.java`: `TAG_CREATE`/`TAG_EDIT`/`TAG_DELETE`・`requiresMasterEnvironment()`追加
- [ ] `wordpress/provision-agent/index.php`: `fetchCategories`→`fetchTerms(taxonomy)`一般化、`tag_create`/`tag_edit`/`tag_delete`ハンドラ、`/tags`エンドポイント
- [ ] `api/src/main/java/com/letsblog/api/provisioning/WordPressBulkManagementClient.java`: `listTags()`追加
- [ ] `api/src/main/java/com/letsblog/api/service/BulkManagementService.java`: `applyToEnvironment()`新設・`replay()`のリファクタリング・`execute()`/`listReferenceCategories()`削除
- [ ] `api/src/main/java/com/letsblog/api/service/TermComparisonService.java`(新規)
- [ ] `api/src/main/java/com/letsblog/api/dto/TermComparisonRow.java`・`TermEnvironmentValue.java`・`TermComparisonPage.java`・`ApplyToEnvironmentRequest.java`・`TermNameRequest.java`(新規)、`BulkOperationRequest.java`・`CategoryOptionResponse.java`削除
- [ ] `ProjectController.java`: `/bulk-management/apply`・`/categories/comparison`・`/tags/comparison`・`/categories/sync`・`/categories/delete-all`・`/tags/sync`・`/tags/delete-all`追加、旧`POST /bulk-management`・`GET /bulk-management/categories`削除
- [ ] `web/src/lib/apiClient.ts`: 型・関数の追加/削除(上記)
- [ ] `web/src/app/projects/[id]/BulkManagementPanel.tsx`: タブ構成の全面書き換え、`TermComparisonTable.tsx`(新規、共通コンポーネント)
- [ ] `web/src/app/projects/[id]/page.tsx`: データ取得の差し替え
- [ ] `web/src/app/projects/[id]/actions.ts`: Server Actionの追加/削除

対象外・スコープ外:

- カテゴリ/タグの階層全体を1クリックで同期する自動再帰処理
- 名前が変わった項目を同一項目として追跡するリネーム検出
- 比較テーブルの検索・絞り込みUI(ページネーションのみ)

## 実装順序

1. `BulkOperationType`拡張 → provision-agent(`fetchTerms`一般化・tag_*ハンドラ・`/tags`)
2. `WordPressBulkManagementClient.listTags()` → `BulkManagementService.applyToEnvironment()`(`replay()`リファクタリング含む)
3. `TermComparisonService`・DTO群 → `ProjectController`エンドポイント追加、旧エンドポイント削除
4. フロント: `apiClient.ts` → `TermComparisonTable.tsx` → `BulkManagementPanel.tsx` → `page.tsx` → `actions.ts`
5. テスト整備・実機検証

## テスト整備

- `BulkManagementServiceTest`: `applyToEnvironment()`が指定した1環境のみへ適用され1件のログを保存すること、`replay()`の既存挙動が変わっていないこと(リファクタリングの回帰確認)
- `TermComparisonServiceTest`(新規): 3環境の値を名前でマージして正しく`TermComparisonRow`を構築すること、マスターと異なる値に`matchesMaster=false`が付くこと、マスターに存在しない項目行で非マスター側が全て差分扱いになること、ページネーションが20件単位で正しく区切られること、`syncCategory`が非マスター環境へ存在しなければ作成・存在すれば更新すること、マスターに存在しない名前を`syncCategory`した場合に例外になること、`deleteCategoryEverywhere`が各環境の現在スラッグを使って削除しスキップも正しく扱われること
- `ProjectControllerTest`: `/bulk-management/apply`で`CATEGORY_CREATE`/`CATEGORY_EDIT`にマスター以外の環境を指定した場合400になること、各新規エンドポイントのadmin権限チェック
- PHPエージェント: 手動テストで(a) `tag_create`/`tag_edit`/`tag_delete`がカテゴリと同様に動作すること(`--parent`を渡さないこと)、(b) `/tags`が`/categories`と同じ形式でタグ一覧を返すこと

## 実機検証

1. プロジェクトにローカル/テスト/本番3つのmanaged環境を紐付け、マスター環境を「テスト」に設定した状態でカテゴリタブを開き、3環境の値が正しく横並び表示されることを確認
2. マスター(テスト)にのみ存在するカテゴリを新規追加し、ローカル・本番列が赤字(未存在)で表示されることを確認
3. 「同期」を実行し、ローカル・本番にも同じ名前・スラッグ・説明でカテゴリが作成されることを確認
4. マスターのカテゴリのスラッグを「編集」で変更し、再度「同期」するとローカル・本番のスラッグもマスターに合わせて更新されることを確認
5. 「削除」を実行し、3環境すべてから該当カテゴリが削除されることを確認
6. タグタブでも1〜5と同様の挙動を確認(親カテゴリ列がないことも確認)
7. 21件以上のカテゴリを用意し、ページネーションが正しく機能することを確認
8. 作業ログ一覧に`CATEGORY_CREATE`/`CATEGORY_EDIT`/`CATEGORY_DELETE`/`TAG_CREATE`等が正しい環境・結果で記録されていることを確認
