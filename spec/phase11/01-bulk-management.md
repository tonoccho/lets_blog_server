# 01. プロジェクト画面: 一括管理(カテゴリ/プラグイン/テーマ)・作業ログ・ロールフォワード

## 目的

プロジェクトに紐づく複数の環境(ローカル/テスト/本番)へ、カテゴリの作成・プラグインのインストール・テーマのインストールを同時に実行できる「一括管理」機能を追加する。プラグイン/テーマのインストールは、WordPress.org公式ディレクトリのslug指定に加えて、zipファイルのアップロードによるインストール(非公式・カスタムビルド・プレミアムプラグイン等)にも対応する。実行内容は「作業ログ」として保存し、任意の1環境に対して過去の操作を再適用(ロールフォワード)できるようにする。これにより、例えばローカル環境をDocker再構築等で作り直した後、それまでにテスト/本番へ行ってきたカテゴリ作成・プラグイン/テーマインストール(zipアップロードによるものを含む)の履歴を一括でローカルへ当て直せる。

## 現状確認

- Phase10-03で実装済みの環境同期(`ProjectEnvironmentSyncService`/`WordPressSyncClient`)は、2環境間でファイル・DBを丸ごとコピーする方式であり、「カテゴリを1つ作成する」「プラグインを1つ入れる」といった個別の操作単位・履歴という概念を持たない
- `CmsAdapter.resolveCategories`(REST版は`WordPressAdapter`、SSH版は`WordPressSshOperations`)は投稿作成時にカテゴリ名をID解決するための機能であり、「複数環境に同時作成する」というバルク実行の入り口がない
- プラグイン/テーマのインストールに相当する機能はコードベース上どこにも存在しない(新規追加)
- managedWordpress環境はいずれも同一の常駐コンテナ(`lbs-wordpress`)内に存在するため、Phase10-03と同じく`wordpress/provision-agent/index.php`にハンドラを追加し、`wp-cli`をローカル実行する方式で実現できる。SSHや外部転送は不要
- `Project`は`localSiteId`/`testSiteId`/`productionSiteId`の3スロットで環境を保持しており、`ProjectEnvironmentSyncService.resolveManagedSite()`に環境名→`Site`解決とmanagedWordpress検証のロジックが既にある。本タスクでも同様の解決ロジックが必要になる
- 監査ログ(`AuditLog`/`AuditLogAction`)は「誰が・いつ・何をしたか」の単発記録であり、環境ごとの成功/失敗や「後から再実行できる」再現可能な形式のログではないため転用しない。本タスク専用のテーブルを新設する
- ファイルアップロードの既存実装は`MediaController`(`POST /api/media/upload`)のみで、受け取った`MultipartFile`のバイト列をその場でCMS側へ渡すだけの完全なパススルー方式であり、APIコンテナ側には何も永続化しない(`docker-compose.yml`上もAPIコンテナには現状ボリュームが1つもない、完全にステートレスなコンテナ)。一方、zipアップロードによるプラグイン/テーマインストールをロールフォワード対象に含めるには、後から同じzipの中身を再現できる必要があるため、本タスクでは新たにAPIコンテナへ永続ボリュームを追加してアップロードzipを保管する(後述の決定事項参照)

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 対象範囲 | プロジェクトに紐づく`managedWordpress = true`の環境すべて(Phase10-03の同期と同じ制約)。外部登録サイトが紐付いている環境スロットは対象から自動的に除外する(UIでもグレーアウト等で選択不可にする必要はなく、常に「紐付いているmanaged環境全部」が対象になるため選択UI自体を設けない) |
| 実行単位 | 1回の一括操作につき、操作種別(`category`/`plugin`/`theme`)は1種類・値は1件のみ(例: カテゴリ名「お知らせ」を作成、プラグインslug「akismet」をインストール、または1個のzipファイルをインストール)。複数件の同時投入は本フェーズでは対象外とし、複数件必要な場合は1件ずつ実行する運用とする |
| プラグイン/テーマの指定方法 | 2種類の入力ソースに対応する。(1) **SLUG**: WordPress.org公式ディレクトリのslug(`wp plugin install <slug>`/`wp theme install <slug>`)。(2) **ZIP**: ユーザーが管理画面からzipファイルをアップロードし、その中身をインストールする(`wp plugin install <zipパス>`/`wp theme install <zipパス>`)。プレミアムプラグインのライセンスキー投入・自動アクティベーションは対象外 |
| zipファイルの永続化 | APIコンテナに新規Dockerボリューム(`bulk_upload_files`、マウント先`/app/data/bulk-uploads`)を追加し、アップロードされたzipを`{project_id}/{sha256}.zip`のパスで保存する(同一内容のファイルはハッシュで自然に重複排除される)。ロールフォワード実行時はこの保存済みファイルを再度読み出して対象環境へ転送する。プロジェクト削除時は`bulk_operation_logs`のDB行はFKの`ON DELETE CASCADE`で自動削除されるが、保存済みzipファイル自体は`ProjectService.deleteProject()`から`BulkUploadStorageService`のディレクトリ削除を呼び出して合わせて削除する(DBのCASCADEだけではファイルシステム上のファイルは消えないため) |
| zipファイルのアップロード上限 | `spring.servlet.multipart.max-file-size`/`max-request-size`を100MBに設定する(既定は1MB/10MBのため、プラグイン/テーマzipのサイズを考慮して引き上げる。既存の`MediaController`の画像アップロードにも同じ上限が適用されるが、画像サイズが100MBを超えることは通常ないため実質的な影響はない) |
| zipファイルのバリデーション | 拡張子`.zip`かつ`Content-Type`が`application/zip`または`application/x-zip-compressed`であること、空ファイルでないことをAPI層で検証する。zip内部の構造(プラグイン/テーマとして妥当か)はwp-cliの`plugin install`/`theme install`自体の検証に委ねる(API層では中身まで検証しない) |
| インストール後の有効化 | 自動有効化しない(`--activate`を付けない)。複数環境へ同時投入する性質上、意図せず本番の有効テーマ/プラグイン構成を変えてしまうことを避けるため、有効化は各環境の管理者が個別に判断してwp-admin等から行う |
| カテゴリ作成の実装方式 | 既存の`CmsAdapter.resolveCategories`(REST/SSH)は流用せず、プラグイン/テーマと同じくprovision-agent経由の`wp-cli`(`wp term create category`)で統一する。対象がmanagedWordpressのみのため実行経路を1つに揃え、実装・テストを単純化する |
| 名前の一致条件(カテゴリ) | `wp term list category --search=<name>`で大文字小文字を無視した完全一致を既存カテゴリとみなし、あれば作成しない(REST版`resolveCategories`と同じ方針) |
| 冪等性(プラグイン/テーマ・SLUG) | `wp plugin install`/`wp theme install`は既にインストール済みの対象に対して実行するとエラー終了するため、事前に`wp plugin list --field=name --format=json`/`wp theme list --field=name --format=json`で存在確認し、既に存在する場合は実行せず`SKIPPED`として記録する |
| 冪等性(プラグイン/テーマ・ZIP) | zipの中身(実際のslug/フォルダ名)はインストールするまで確定しないため、SLUG方式のような事前存在チェックは行わない。常に`--force`を付与して`wp plugin install <zip> --force`/`wp theme install <zip> --force`を実行し、既存インストールがあれば無条件に上書きする(Phase10-03のDB同期等、本アプリで一貫している「差分チェックをせずマスターの内容で上書きする」という方針に合わせる)。そのためZIP方式の結果は`SUCCESS`/`FAILED`のみで`SKIPPED`は発生しない |
| 実行結果の粒度 | 環境ごとに成功(`SUCCESS`)/スキップ(`SKIPPED`)/失敗(`FAILED`)を個別に記録する。1環境の失敗が他環境への実行を止めない(全環境に対して実行を試み、まとめて結果を返す) |
| 作業ログの永続化 | 新規テーブル`bulk_operation_logs`。1回の一括実行(または1回のロールフォワード実行)につき、対象環境の数だけ行を記録する。ZIP方式の場合は保存先パス・元のファイル名・ハッシュ値も記録する |
| ロールフォワード(再適用)の対象 | プロジェクト単位。対象環境を1つ指定すると、そのプロジェクトの過去の成功ログ(`status = SUCCESS`、`is_replay`問わず)を実行日時の古い順にすべて再実行する。SLUG方式は再度同じslugをインストール、ZIP方式は保存済みのzipファイルを読み出して再インストールする。同じ内容の重複適用は上記の冪等性(SLUGはSKIPPED化、ZIPは`--force`上書き)により無害なため、除外条件は設けない。保存済みzipファイルが何らかの理由で見つからない場合は`FAILED`として記録し、エラーメッセージに「元ファイルが見つかりません。再度アップロードしてください」等を含める |
| ロールフォワードの記録 | 再適用の実行結果も同じ`bulk_operation_logs`テーブルに`is_replay = true`として記録する(通常実行と区別できるようにする) |
| 確認UX | 既存の同期パネルと同じ`window.confirm`のみ(環境名入力等の強い確認UIは導入しない) |
| タイムアウト対策 | プラグイン/テーマインストール(wordpress.orgからのダウンロード、またはzipの転送・展開)は時間を要するため、既存の`/api/projects/{id}/environments/sync`と同じ`proxy_read_timeout 300s`パターンのnginx locationを新規エンドポイントにも追加する |
| 権限 | 既存の環境同期と同じく管理者(`adminAuthorizationService.requireAdmin()`)のみ実行可能とする |

## アーキテクチャ・実装詳細

### 全体フロー(一括実行・SLUG方式/カテゴリ)

```
プロジェクト詳細画面(新規: BulkManagementPanel.tsx)
  操作種別(カテゴリ/プラグイン/テーマ)を選択・値を入力(プラグイン/テーマは「slug」か「zipアップロード」をタブ等で選択)
  (対象環境はプロジェクトに紐づく全managed環境固定。選択UIは設けない)
  ↓ Server Action: runBulkOperationAction
apiClient.ts: runBulkOperation(projectId, { operationType, value }, actor)
  ↓ HTTP POST /api/projects/{id}/bulk-management (JSON)
ProjectController.runBulkOperation(id, request)
  ↓ adminAuthorizationService.requireAdmin()
BulkManagementService.execute(projectId, operationType, value, actorId)
  1. プロジェクトに紐づくmanaged環境(local/test/productionのうちmanagedWordpress=trueのもの)を解決
  2. 環境ごとに WordPressBulkManagementClient.apply(slug, operationType, value) を呼び出す
  3. 環境ごとの結果(SUCCESS/SKIPPED/FAILED)をBulkOperationLog(source_type=SLUG)として保存
  ↓
レスポンス: 環境ごとの結果一覧
```

### 全体フロー(一括実行・ZIP方式)

```
プロジェクト詳細画面(BulkManagementPanel.tsx、zipアップロードタブ)
  操作種別(プラグイン/テーマ)を選択、zipファイルを選択
  ↓ Server Action: runBulkOperationUploadAction (multipart/form-data)
apiClient.ts: runBulkOperationUpload(projectId, { operationType, file }, actor)
  ↓ HTTP POST /api/projects/{id}/bulk-management/upload (multipart/form-data)
ProjectController.runBulkOperationUpload(id, operationType, file)
  ↓ adminAuthorizationService.requireAdmin()
BulkManagementService.executeFromUpload(projectId, operationType, file, actorId)
  1. ファイル拡張子・Content-Type・サイズを検証
  2. bulkUploadStorageService.store(projectId, file) → 保存先パス・sha256・元ファイル名を取得
  3. プロジェクトに紐づくmanaged環境を解決
  4. 環境ごとに WordPressBulkManagementClient.applyZip(slug, operationType, zipBytes) を呼び出す
     (保存済みファイルから読み出したバイト列を、環境の数だけprovision-agentへ都度転送する)
  5. 環境ごとの結果をBulkOperationLog(source_type=ZIP、original_filename・storage_path・file_sha256付き)として保存
  ↓
レスポンス: 環境ごとの結果一覧
```

```
provision-agent/index.php: /bulk-management ハンドラ(JSON、SLUG方式・カテゴリ用)
  action=category: wp term list category --search=<value> --field=name --format=json
                    → 完全一致なければ wp term create category <value> --porcelain
  action=plugin:    wp plugin list --field=name --format=json
                    → 含まれていなければ wp plugin install <value> --path=<sitePath>
  action=theme:     wp theme list --field=name --format=json
                    → 含まれていなければ wp theme install <value> --path=<sitePath>

provision-agent/index.php: /bulk-management/upload ハンドラ(multipart、ZIP方式・plugin/theme専用)
  1. アップロードされたzipバイト列をコンテナ内の一時パス(/tmp/letsblog-bulk-<uuid>.zip)へ保存
  2. action=plugin: wp plugin install /tmp/letsblog-bulk-<uuid>.zip --force --path=<sitePath>
     action=theme:  wp theme install /tmp/letsblog-bulk-<uuid>.zip --force --path=<sitePath>
  3. finallyで一時ファイルを削除(失敗時もログのみで削除、既存のSSH uploadMediaの一時ファイル削除方針と同じ)
```

### 作業ログ・ロールフォワードのフロー

```
プロジェクト詳細画面(作業ログ一覧テーブル + 環境ごとの「ロールフォワード」ボタン)
  ↓ Server Action: replayBulkOperationsAction(projectId, environment)
apiClient.ts: replayBulkOperations(projectId, { environment }, actor)
  ↓ HTTP POST /api/projects/{id}/bulk-management/replay
ProjectController.replayBulkOperations(id, request)
  ↓
BulkManagementService.replay(projectId, environment, actorId)
  1. bulkOperationLogRepository.findByProjectIdAndStatusOrderByCreatedAtAsc(projectId, SUCCESS)
     から (sourceType, operationType, value, storagePath) の実行履歴を古い順に取得
  2. 1件ずつ、対象environmentのみへ再適用(is_replay=trueで記録)
     - sourceType=SLUG: executeと同じくWordPressBulkManagementClient.apply(...)
     - sourceType=ZIP: bulkUploadStorageService.load(storagePath)でバイト列を読み出し、
       WordPressBulkManagementClient.applyZip(...)。ファイルが見つからなければFAILEDとして記録
  ↓
レスポンス: 再適用結果一覧
```

```
プロジェクト詳細画面(作業ログ一覧)
  ↓ Server Action: (ページロード時にfetch)
apiClient.ts: listBulkOperationLogs(projectId, actor)
  ↓ HTTP GET /api/projects/{id}/bulk-management/logs
ProjectController.listBulkOperationLogs(id)
  ↓
BulkManagementService.listLogs(projectId) // createdAt降順
```

### データベース(新規マイグレーション `V16__add_bulk_operation_logs.sql`)

```sql
CREATE TABLE bulk_operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    operation_type VARCHAR(20) NOT NULL, -- CATEGORY / PLUGIN / THEME
    source_type VARCHAR(10) NOT NULL DEFAULT 'SLUG', -- SLUG / ZIP
    value VARCHAR(255) NOT NULL, -- SLUG: wordpress.orgのslug / ZIP: 元のファイル名(original_filenameと同値)
    original_filename VARCHAR(255), -- ZIPのみ
    storage_path VARCHAR(500), -- ZIPのみ、bulk_upload_files ボリューム内の相対パス
    file_sha256 VARCHAR(64), -- ZIPのみ
    environment VARCHAR(20) NOT NULL, -- local / test / production
    status VARCHAR(20) NOT NULL, -- SUCCESS / SKIPPED / FAILED
    error_message TEXT,
    is_replay BOOLEAN NOT NULL DEFAULT FALSE,
    actor_id BIGINT,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_bulk_operation_logs_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_bulk_operation_logs_project_id (project_id),
    INDEX idx_bulk_operation_logs_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

(`actor_id`は既存の`audit_logs.user_id`と同じくFK制約なしのプレーンなカラムとする)

`BulkOperationLog.java`(新規ドメイン、`AuditLog.java`と同じ構成方針)、`BulkOperationType`(enum: `CATEGORY`/`PLUGIN`/`THEME`)、`BulkOperationSourceType`(enum: `SLUG`/`ZIP`)、`BulkOperationStatus`(enum: `SUCCESS`/`SKIPPED`/`FAILED`)。

### zipファイルの永続化(`BulkUploadStorageService`)

`api/src/main/java/com/letsblog/api/service/BulkUploadStorageService.java`(新規):

```java
@Service
public class BulkUploadStorageService {

    private final Path rootDir; // app.bulk-upload-storage-path

    public StoredZip store(Long projectId, MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        String sha256 = DigestUtils.sha256Hex(bytes); // 既存依存(Apache Commons Codec等)を利用、なければ標準MessageDigestで実装
        Path dir = rootDir.resolve(String.valueOf(projectId));
        Files.createDirectories(dir);
        Path target = dir.resolve(sha256 + ".zip");
        if (!Files.exists(target)) {
            Files.write(target, bytes);
        }
        return new StoredZip(projectId + "/" + sha256 + ".zip", sha256, file.getOriginalFilename());
    }

    public byte[] load(String storagePath) throws IOException {
        Path target = rootDir.resolve(storagePath);
        if (!Files.exists(target)) {
            throw new IOException("保存済みファイルが見つかりません: " + storagePath);
        }
        return Files.readAllBytes(target);
    }

    public void deleteAll(Long projectId) {
        // プロジェクト削除時にProjectService.deleteProject()から呼び出す
        FileSystemUtils.deleteRecursively(rootDir.resolve(String.valueOf(projectId)).toFile());
    }

    public record StoredZip(String storagePath, String sha256, String originalFilename) {}
}
```

`application.yml`に設定追加:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 100MB

app:
  bulk-upload-storage-path: ${BULK_UPLOAD_STORAGE_PATH:/app/data/bulk-uploads}
```

`ProjectService.deleteProject()`に`bulkUploadStorageService.deleteAll(projectId)`の呼び出しを追加する(DBの`ON DELETE CASCADE`はファイルシステム上のファイルまでは削除しないため)。

### `wordpress/provision-agent/index.php`に追加

```php
const ALLOWED_BULK_ACTIONS = ['category', 'plugin', 'theme'];

if ($path === '/bulk-management' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $action = (string) ($input['action'] ?? '');
    $value = (string) ($input['value'] ?? '');

    if (!isValidSlug($slug) || !in_array($action, ALLOWED_BULK_ACTIONS, true) || $value === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }

    if ($action === 'category') {
        [$code, $out] = runWp(['term', 'list', 'category', "--search=$value", '--field=name', '--format=json', "--path=$sitePath", '--allow-root']);
        $existing = $code === 0 ? (json_decode($out, true) ?: []) : [];
        $matched = array_filter($existing, fn($name) => strcasecmp($name, $value) === 0);
        if (!empty($matched)) {
            respond(200, ['status' => 'skipped']);
        }
        [$code, $out] = runWp(['term', 'create', 'category', $value, '--porcelain', "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'カテゴリの作成に失敗しました', 'detail' => $out]);
        }
        respond(200, ['status' => 'ok']);
    }

    // action === 'plugin' | 'theme' (SLUG方式)
    [$code, $out] = runWp([$action, 'list', '--field=name', '--format=json', "--path=$sitePath", '--allow-root']);
    $installed = $code === 0 ? (json_decode($out, true) ?: []) : [];
    if (in_array($value, $installed, true)) {
        respond(200, ['status' => 'skipped']);
    }
    [$code, $out] = runWp([$action, 'install', $value, "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => "{$action}のインストールに失敗しました", 'detail' => $out]);
    }
    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);
    respond(200, ['status' => 'ok']);
}

const ALLOWED_BULK_UPLOAD_ACTIONS = ['plugin', 'theme'];

if ($path === '/bulk-management/upload' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($_POST['slug'] ?? '');
    $action = (string) ($_POST['action'] ?? '');

    if (!isValidSlug($slug) || !in_array($action, ALLOWED_BULK_UPLOAD_ACTIONS, true) || empty($_FILES['file'])) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }

    $tmpPath = '/tmp/letsblog-bulk-' . bin2hex(random_bytes(8)) . '.zip';
    if (!move_uploaded_file($_FILES['file']['tmp_name'], $tmpPath)) {
        respond(500, ['error' => 'アップロードファイルの一時保存に失敗しました']);
    }

    [$code, $out] = runWp([$action, 'install', $tmpPath, '--force', "--path=$sitePath", '--allow-root']);
    runCommand(['rm', '-f', $tmpPath]);
    if ($code !== 0) {
        respond(500, ['error' => "{$action}のインストールに失敗しました", 'detail' => $out]);
    }
    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);
    respond(200, ['status' => 'ok']);
}
```

(`value`/`slug`はwp-cliのサブコマンド引数として`runCommand`経由=`escapeshellarg`でトークン単位エスケープされるため、シェルインジェクションの懸念はPhase10-03と同様に対処済み。`/bulk-management/upload`はJSONではなくmultipart/form-dataで受けるため、既存の`$input`(`php://input`のJSONデコード結果)ではなく`$_POST`/`$_FILES`を使う点が他ハンドラと異なる)

### バックエンド(Spring Boot)

`WordPressBulkManagementClient.java`(新規、`WordPressSyncClient`と同じ内部限定エージェントを呼ぶ):

```java
@Component
public class WordPressBulkManagementClient {
    // WordPressSyncClientと同じRestClient/トークン構成を再利用(コンストラクタで同じbaseUrl/tokenを注入)

    public BulkApplyResult apply(String slug, String action, String value) {
        try {
            Map<String, String> body = client.post().uri("/bulk-management")
                    .header("X-Provision-Token", provisionToken)
                    .body(new BulkApplyCommand(slug, action, value))
                    .retrieve().body(Map.class);
            return resultOf(body);
        } catch (RestClientException e) {
            return BulkApplyResult.failed(e.getMessage());
        }
    }

    public BulkApplyResult applyZip(String slug, String action, byte[] zipBytes, String filename) {
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("slug", slug);
            form.add("action", action);
            form.add("file", new ByteArrayResource(zipBytes) {
                @Override public String getFilename() { return filename; }
            });
            Map<String, String> body = client.post().uri("/bulk-management/upload")
                    .header("X-Provision-Token", provisionToken)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve().body(Map.class);
            return resultOf(body);
        } catch (RestClientException e) {
            return BulkApplyResult.failed(e.getMessage());
        }
    }

    private BulkApplyResult resultOf(Map<String, String> body) {
        String status = body != null ? body.get("status") : null;
        return "skipped".equals(status) ? BulkApplyResult.skipped() : BulkApplyResult.success();
    }

    public record BulkApplyCommand(String slug, String action, String value) {}
    public record BulkApplyResult(String status, String errorMessage) {
        public static BulkApplyResult success() { return new BulkApplyResult("SUCCESS", null); }
        public static BulkApplyResult skipped() { return new BulkApplyResult("SKIPPED", null); }
        public static BulkApplyResult failed(String message) { return new BulkApplyResult("FAILED", message); }
    }
}
```

(環境同期の`WordPressSyncClient.sync`は例外送出=失敗を1回で止める設計だが、一括管理は「1環境の失敗が他環境を止めない」という決定事項があるため、例外を投げずに結果を返す設計にする点が異なる)

`BulkManagementService.java`(新規、要点のみ):

```java
@Service
public class BulkManagementService {

    public List<BulkOperationLog> execute(Long projectId, BulkOperationType type, String value, Long actorId) {
        // resolveManagedEnvironments(project) の各環境へ WordPressBulkManagementClient.apply(...) を呼び、
        // BulkOperationLog(sourceType=SLUG) として保存する
    }

    public List<BulkOperationLog> executeFromUpload(
            Long projectId, BulkOperationType type, MultipartFile file, Long actorId) throws IOException {
        validateZip(file); // 拡張子・Content-Type・空ファイルチェック
        BulkUploadStorageService.StoredZip stored = bulkUploadStorageService.store(projectId, file);
        // resolveManagedEnvironments(project) の各環境へ WordPressBulkManagementClient.applyZip(...) を呼び、
        // BulkOperationLog(sourceType=ZIP、original_filename/storage_path/file_sha256付き) として保存する
    }

    public List<BulkOperationLog> replay(Long projectId, String environment, Long actorId) {
        Site targetSite = resolveManagedSite(getProject(projectId), environment); // Phase10-03と同じ解決ロジック
        List<BulkOperationLog> history = bulkOperationLogRepository
                .findByProjectIdAndStatusOrderByCreatedAtAsc(projectId, BulkOperationStatus.SUCCESS);
        // history を1件ずつ、sourceTypeに応じて apply / applyZip(bulkUploadStorageService.load(...)) を実行し、
        // is_replay=true で記録する。ZIPのload失敗時はFAILEDとして記録し処理を続行する
    }

    public List<BulkOperationLog> listLogs(Long projectId) {
        return bulkOperationLogRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }
}
```

`ProjectController.java`に追加:

```java
@PostMapping("/{id}/bulk-management")
public List<BulkOperationLogResponse> runBulkOperation(
        @PathVariable Long id, @Valid @RequestBody BulkOperationRequest request) {
    adminAuthorizationService.requireAdmin();
    Long actorId = currentActorService.getCurrentActorId();
    return toResponses(bulkManagementService.execute(id, request.operationType(), request.value(), actorId));
}

@PostMapping(value = "/{id}/bulk-management/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public List<BulkOperationLogResponse> runBulkOperationUpload(
        @PathVariable Long id,
        @RequestParam BulkOperationType operationType,
        @RequestPart("file") MultipartFile file) throws IOException {
    adminAuthorizationService.requireAdmin();
    Long actorId = currentActorService.getCurrentActorId();
    return toResponses(bulkManagementService.executeFromUpload(id, operationType, file, actorId));
}

@PostMapping("/{id}/bulk-management/replay")
public List<BulkOperationLogResponse> replayBulkOperations(
        @PathVariable Long id, @Valid @RequestBody ReplayBulkOperationRequest request) {
    adminAuthorizationService.requireAdmin();
    Long actorId = currentActorService.getCurrentActorId();
    return toResponses(bulkManagementService.replay(id, request.environment(), actorId));
}

@GetMapping("/{id}/bulk-management/logs")
public List<BulkOperationLogResponse> listBulkOperationLogs(@PathVariable Long id) {
    adminAuthorizationService.requireAdmin();
    return toResponses(bulkManagementService.listLogs(id));
}
```

`dto/BulkOperationRequest.java`: `record BulkOperationRequest(@NotNull BulkOperationType operationType, @NotBlank String value) {}`(SLUG方式・カテゴリ用。`operationType=PLUGIN/THEME`かつzipアップロードの場合は代わりに`/upload`エンドポイントを使う)
`dto/ReplayBulkOperationRequest.java`: `record ReplayBulkOperationRequest(@NotBlank String environment) {}`
`dto/BulkOperationLogResponse.java`: `record BulkOperationLogResponse(Long id, BulkOperationType operationType, BulkOperationSourceType sourceType, String value, String originalFilename, String environment, BulkOperationStatus status, String errorMessage, boolean isReplay, LocalDateTime createdAt) {}`(`storagePath`/`fileSha256`はサーバー内部の情報でありフロントには返さない)

### インフラ(nginx)

`nginx/conf.d/default.conf`の`location /api/`ブロックより前に、既存の同期用locationと同じパターンで追加:

```nginx
location ~ ^/api/projects/[0-9]+/bulk-management(/upload|/replay)?$ {
    set $upstream_api api:8080;
    proxy_pass http://$upstream_api;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 300s;
    client_max_body_size 100m;
}
```

(`GET /bulk-management/logs`は軽量なため既定の`/api/`のタイムアウトで問題ない。上記正規表現は`/bulk-management`・`/bulk-management/upload`・`/bulk-management/replay`のみにマッチし`/logs`は含まれないことに注意。`client_max_body_size`はzipアップロードのため既定(1MB)から引き上げる)

### インフラ(docker-compose)

`docker-compose.yml`の`api`サービスに永続ボリュームを追加:

```yaml
  api:
    ...
    environment:
      ...
      BULK_UPLOAD_STORAGE_PATH: /app/data/bulk-uploads
    volumes:
      - bulk_upload_files:/app/data/bulk-uploads
```

トップレベルの`volumes:`に`bulk_upload_files:`を追加する。

### フロントエンド

`web/src/app/projects/[id]/BulkManagementPanel.tsx`(新規、`EnvironmentSyncPanel.tsx`と同じ配置場所):

- 操作種別(カテゴリ/プラグイン/テーマ)の`<select>`
- カテゴリ選択時: 値の`<input>`のみ表示
- プラグイン/テーマ選択時: 「slugを指定」/「zipをアップロード」のタブ切り替えを表示し、slugタブでは`<input>`(placeholder「wordpress.orgのプラグイン/テーマslug」)、zipタブでは`<input type="file" accept=".zip">`を表示
- 送信前に`window.confirm`(「紐付いている全環境({環境名一覧})に対して、{操作}「{値 or ファイル名}」を実行します。よろしいですか?」)
- 実行結果(環境ごとのSUCCESS/SKIPPED/FAILED)を送信後にインライン表示
- 作業ログ一覧テーブル(操作種別・入力元(slug/zip)・値またはファイル名・環境・結果・実行日時・再適用かどうか)を`createdAt`降順で表示
- 環境ごとに「この環境へロールフォワード」ボタンを配置し、押下で`window.confirm`後に`replayBulkOperationsAction`を実行

`web/src/app/projects/[id]/page.tsx`に`<BulkManagementPanel projectId={project.id} project={project} logs={logs} />`を追加(`listBulkOperationLogs`を`Promise.all`に追加)。

`web/src/lib/apiClient.ts`に`BulkOperationType`/`BulkOperationSourceType`/`BulkOperationLog`型、`runBulkOperation()`/`runBulkOperationUpload()`(multipart送信)/`replayBulkOperations()`/`listBulkOperationLogs()`を追加。

## スコープ・実装項目

実装対象:

- [x] `api/src/main/resources/db/migration/V16__add_bulk_operation_logs.sql`(新規)
- [x] `api/src/main/java/com/letsblog/api/domain/BulkOperationLog.java`・`BulkOperationType.java`・`BulkOperationSourceType.java`・`BulkOperationStatus.java`(新規)
- [x] `api/src/main/java/com/letsblog/api/repository/BulkOperationLogRepository.java`(新規)
- [x] `api/src/main/java/com/letsblog/api/service/BulkUploadStorageService.java`(新規)
- [x] `api/src/main/java/com/letsblog/api/service/ProjectService.java`: `deleteProject()`に`BulkUploadStorageService.deleteAll()`呼び出しを追加
- [x] `api/src/main/resources/application.yml`: multipart上限・`app.bulk-upload-storage-path`追加
- [x] `wordpress/provision-agent/index.php`: `/bulk-management`・`/bulk-management/upload`ハンドラ
- [x] `api/src/main/java/com/letsblog/api/provisioning/WordPressBulkManagementClient.java`(新規)
- [x] `api/src/main/java/com/letsblog/api/service/BulkManagementService.java`(新規)
- [x] `api/src/main/java/com/letsblog/api/dto/BulkOperationRequest.java`・`ReplayBulkOperationRequest.java`・`BulkOperationLogResponse.java`(新規)
- [x] `ProjectController.java`: `POST /bulk-management`・`POST /bulk-management/upload`・`POST /bulk-management/replay`・`GET /bulk-management/logs`
- [x] `nginx/conf.d/default.conf`: 一括管理・アップロード・ロールフォワード用location(300秒タイムアウト・アップロードサイズ上限)
- [x] `docker-compose.yml`: `bulk_upload_files`ボリューム追加(api・トップレベル`volumes:`双方)
- [x] `web/src/lib/apiClient.ts`: `runBulkOperation()`・`runBulkOperationUpload()`・`replayBulkOperations()`・`listBulkOperationLogs()`
- [x] `web/src/app/projects/[id]/BulkManagementPanel.tsx`(新規)
- [x] `web/src/app/projects/[id]/page.tsx`: パネル追加
- [x] `web/src/app/projects/[id]/actions.ts`: `runBulkOperationAction`・`runBulkOperationUploadAction`・`replayBulkOperationsAction`

対象外・スコープ外:

- 外部登録(非managed)サイトへの一括管理対応
- プラグイン/テーマの自動有効化・バージョン指定・自動更新
- プレミアムプラグインのライセンスキー投入
- 複数値・複数ファイルの同時投入(1回の実行につき1件のみ)
- 作業ログ・保存済みzipファイルの保持世代管理・自動削除(容量が問題になった場合は将来検討)

## 実装順序

1. `V16__add_bulk_operation_logs.sql` → `BulkOperationLog`ドメイン・リポジトリ
2. `BulkUploadStorageService`・`application.yml`設定・`docker-compose.yml`ボリューム追加
3. `wordpress/provision-agent/index.php`: `/bulk-management`(category → plugin/theme SLUGの順)→`/bulk-management/upload`ハンドラ実装
4. `WordPressBulkManagementClient`/`BulkManagementService`/DTO/`ProjectController`
5. nginx location追加
6. フロント: `apiClient.ts` → `BulkManagementPanel.tsx` → `page.tsx` → `actions.ts`
7. `ProjectService.deleteProject()`へのクリーンアップ追加
8. テスト整備・実機検証

## テスト整備

- `BulkManagementServiceTest`: managed環境が1つもない/一部のみの場合の解決ロジック、環境ごとの結果(SUCCESS/SKIPPED/FAILED)が正しくログ保存されること、1環境が失敗しても他環境の実行が続行されること、ZIP方式では常に`--force`相当の呼び出しになり`SKIPPED`が発生しないこと、`replay`が成功ログのみを古い順に取得し正しい`environment`固定で再適用すること(SLUG/ZIP双方)、`isReplay`フラグが正しく記録されること、保存済みzipが存在しない場合の`replay`が`FAILED`として記録され処理が継続されること
- `BulkUploadStorageServiceTest`(新規): 保存したファイルが正しいパス・ハッシュで読み出せること、同一内容のファイルを複数回保存してもディスク上のファイルが1つに重複排除されること、`deleteAll`でプロジェクト単位のディレクトリが削除されること
- `ProjectControllerTest`(既存クラスへ追加): 各エンドポイント(`/bulk-management`・`/bulk-management/upload`・`/bulk-management/replay`・`/bulk-management/logs`)のadmin権限チェック、リクエストバリデーション、zip以外の拡張子/Content-Typeを送った場合のエラー
- PHPエージェント: 手動テストで(a) カテゴリ名を指定した際、未存在なら作成・既存なら`skipped`になること、(b) SLUG指定のプラグイン/テーマで、未インストールならインストール・インストール済みなら`skipped`になること、(c) 存在しないslugを指定した際に`FAILED`として扱われエラー詳細が返ること、(d) zipアップロードでプラグイン/テーマがインストールされ、再度同じzipを送ると`--force`により上書きされ`FAILED`にならないこと

## 実機検証

1. プロジェクトにローカル/テスト2つのmanaged環境を紐付けた状態で、カテゴリ「お知らせ」の一括作成を実行し、両環境のWordPress管理画面でカテゴリが作成されていることを確認
2. 同じカテゴリ名で再実行し、両環境とも`SKIPPED`として記録され重複作成されないことを確認
3. プラグインslug(例: `akismet`)のインストールを一括実行し、両環境の`wp-content/plugins`にインストールされることを確認
4. カスタムテーマ(またはプラグイン)のzipファイルをアップロードして一括インストールを実行し、両環境にインストールされることを確認
5. コンテナ内で`/app/data/bulk-uploads/{projectId}/`配下に該当zipが保存されていることを確認
6. ローカル環境のWordPressインスタンスを削除→`managed-wordpress`で同じsiteKeyで再構築(または新規構築)した後、作業ログからロールフォワードを実行し、過去に実行したカテゴリ作成・プラグインインストール(SLUG方式・ZIP方式の両方)がローカル環境へ再適用されることを確認
7. 作業ログ一覧に通常実行とロールフォワード実行が区別して表示されること、ZIP方式のログには元のファイル名が表示されることを確認

## 未決事項・将来検討

- 外部登録(非managed)サイトへの一括管理対応の要否・実現方式
- プラグイン/テーマの自動有効化オプションの要否
- ロールフォワード対象を「全履歴」ではなく期間・操作種別で絞り込む機能の要否
- 作業ログ・保存済みzipファイルの保持世代・自動削除ポリシー(容量監視も含む)
- zipファイルのウイルススキャン等のセキュリティチェックの要否(現状は管理者のみが実行可能なため対象外としている)
