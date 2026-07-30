# 03. プロジェクト環境間のテーマ・プラグイン・DB同期

## 目的

プロジェクトに紐づく3つの環境(ローカル/テスト/本番)の間で、WordPressのテーマ・プラグイン・データベースをコピーできるようにする(例: ローカルで作り込んだテーマ・プラグイン・コンテンツをテスト環境や本番環境へ反映する)。

## 現状確認(調査結果)

- 自動構築(managed)されたWordPress環境は、すべて同一の常駐コンテナ(`lbs-wordpress`)上のサブディレクトリ設置(`/var/www/html/sites/{slug}`)と、同一のMySQLインスタンス(`lbs-mysql`)上の専用データベース(`wp_{slug}`)として存在する。ネットワーク越しの転送・SSHなどを一切使わず、コンテナ内のローカルコマンドだけで同期が完結できる
- `Project`の環境スロット(`localSiteId`/`testSiteId`/`productionSiteId`)には、`managedWordpress`かどうかの区別なく任意の`Site`を紐付けられる(`ProjectService.bindEnvironment`にその区別がない)。同期機能では、両端が`managedWordpress = true`であることをアプリケーション側で新たに検証する必要がある
- `wp-cli`(`/usr/local/bin/wp`)は既にコンテナ内にあり、`wp theme`/`wp plugin`/`wp db export`/`wp db import`/`wp search-replace`のいずれも現状未使用(新規追加)
- 各managedサイトの公開URLは`https://localhost/sites/{slug}`で確定しており、`wp_options.siteurl`/`home`に焼き込まれている。DBを別環境へそのままコピーすると、コピー先のURLがコピー元のものになってしまう(WordPressのDB移行における典型的な既知の問題)。`wp search-replace`はシリアライズ化データも安全に置換できるため必須の後処理とする
- プロジェクトのユーザー同期(`ProjectUserSyncService`)は環境ごとに個別にWordPressユーザーを作成・管理しており、DBの一括コピーで`wp_users`/`wp_usermeta`を上書きすると、コピー先環境の管理者アカウント・各プロジェクトメンバーのアカウントが失われる(自分たちの認証情報記録と食い違いが生じる)。そのため同期対象からこの2テーブルは除外する
- 既存の同種機能(`ProjectUserSyncService`)は完全に同期的(async/job基盤は存在しない)。nginxの`/api/`には現状タイムアウト延長設定がなく(既定60秒)、DBダンプ等の重い処理には`/ollama/`と同様の`proxy_read_timeout 300s`の専用locationが必要

## 前提・決定事項

(詳細は[00-overview.md](00-overview.md)の決定事項テーブルを参照。要点のみ再掲)

| 項目 | 決定内容 |
|---|---|
| 対象 | `managedWordpress = true`の環境同士のみ |
| 同期内容 | テーマ(`wp-content/themes`)・プラグイン(`wp-content/plugins`)・DB(`wp_users`/`wp_usermeta`除く全テーブル)の3種、個別に選択可能 |
| 実現方式 | `lbs-wordpress`コンテナ内のローカルコマンドのみ(`cp -r`、`mysqldump`\|`mysql`、`wp search-replace`) |
| バックアップ | 同期先(上書きされる側)を上書き前に自動バックアップ(直近1世代のみ) |
| 同期方向 | 任意の2環境間(制限なし) |
| タイムアウト対策 | nginxに新規location(300秒)を追加 |
| 確認UX | `window.confirm`のみ |

## アーキテクチャ・実装詳細

### 全体フロー

```
プロジェクト詳細画面(新規: EnvironmentSyncPanel.tsx)
  (from環境・to環境・同期対象(テーマ/プラグイン/DB、複数選択可)を選択、確認ダイアログ)
  ↓ Server Action: syncEnvironmentAction
apiClient.ts: syncProjectEnvironment(projectId, { from, to, targets })
  ↓ HTTP POST /api/projects/{id}/environments/sync
ProjectController.syncEnvironment(id, request)
  ↓ adminAuthorizationService.requireAdmin()
ProjectEnvironmentSyncService.sync(project, from, to, targets)
  (from/to の Site を解決、両方 managedWordpress であることを検証)
  ↓
WordPressSyncClient.sync(fromSlug, fromDbName, toSlug, toDbName, targets)
  ↓ HTTP POST http://wordpress:9000/sync (内部限定、X-Provision-Token認証)
provision-agent/index.php: /sync ハンドラ
  - themes/plugins指定時: 対象ディレクトリのバックアップ(tar) → cp -r で上書き
  - db指定時: コピー先DBのバックアップ(mysqldump) → mysqldump(コピー元)|mysql(コピー先)
             → wp search-replace <コピー元URL> <コピー先URL> --all-tables --skip-tables=wp_users,wp_usermeta --allow-root
  ↓ レスポンス
成功/失敗をSpring Boot経由でフロントへ返却
```

### `provision-agent/index.php` に追加

```php
if ($path === '/sync' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $fromSlug = (string) ($input['fromSlug'] ?? '');
    $fromDbName = (string) ($input['fromDbName'] ?? '');
    $toSlug = (string) ($input['toSlug'] ?? '');
    $toDbName = (string) ($input['toDbName'] ?? '');
    $targets = is_array($input['targets'] ?? null) ? $input['targets'] : [];

    if (!isValidSlug($fromSlug) || !isValidSlug($toSlug) || !isValidDbName($fromDbName) || !isValidDbName($toDbName)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $fromPath = "/var/www/html/sites/$fromSlug";
    $toPath = "/var/www/html/sites/$toSlug";
    if (!is_dir($fromPath) || !is_dir($toPath)) {
        respond(404, ['error' => '同期元または同期先のサイトが見つかりません']);
    }

    $backupDir = "/var/www/html/backups/$toSlug";
    runCommand(['mkdir', '-p', $backupDir]);
    $timestamp = date('Ymd-His');

    foreach (['themes', 'plugins'] as $type) {
        if (!in_array($type, $targets, true)) continue;
        runCommand(['tar', '-czf', "$backupDir/{$type}-{$timestamp}.tar.gz",
                    '-C', "$toPath/wp-content", $type]);
        runCommand(['rm', '-rf', "$toPath/wp-content/$type"]);
        [$code, $out] = runCommand(['cp', '-r', "$fromPath/wp-content/$type", "$toPath/wp-content/$type"]);
        if ($code !== 0) {
            respond(500, ['error' => "{$type}の同期に失敗しました", 'detail' => $out]);
        }
    }

    if (in_array('db', $targets, true)) {
        runCommand(['sh', '-c',
            "mysqldump --skip-ssl -h" . escapeshellarg($dbHost) . " -uroot -p" . escapeshellarg($rootPassword) .
            " " . escapeshellarg($toDbName) . " > " . escapeshellarg("$backupDir/db-{$timestamp}.sql")]);

        $dumpCmd = "mysqldump --skip-ssl -h" . escapeshellarg($dbHost) . " -uroot -p" . escapeshellarg($rootPassword) .
                   " --ignore-table=" . escapeshellarg("$fromDbName.wp_users") .
                   " --ignore-table=" . escapeshellarg("$fromDbName.wp_usermeta") .
                   " " . escapeshellarg($fromDbName);
        $importCmd = "mysql --skip-ssl -h" . escapeshellarg($dbHost) . " -uroot -p" . escapeshellarg($rootPassword) .
                     " " . escapeshellarg($toDbName);
        [$code, $out] = runCommand(['sh', '-c', "$dumpCmd | $importCmd"]);
        if ($code !== 0) {
            respond(500, ['error' => 'DBの同期に失敗しました', 'detail' => $out]);
        }

        $fromUrl = "https://localhost/sites/$fromSlug";
        $toUrl = "https://localhost/sites/$toSlug";
        [$code, $out] = runWp(['search-replace', $fromUrl, $toUrl, '--all-tables',
                                "--path=$toPath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'URL書き換え(search-replace)に失敗しました', 'detail' => $out]);
        }
    }

    runCommand(['chown', '-R', 'www-data:www-data', $toPath]);
    respond(200, ['status' => 'ok']);
}
```

`mysqldump`のコマンド組み立てで各値を`escapeshellarg`しているが、パイプ経由の`sh -c`実行のため、`runCommand`のトークン単位エスケープとは別に文字列全体を構築している点に注意(実装時、シェルインジェクション対策として各動的値は必ず`escapeshellarg`を通す。`dbHost`/`rootPassword`は既存の`/provision`ハンドラと同じ環境変数由来)。

### バックエンド(Spring Boot)

`WordPressSyncClient.java`(新規、`WordPressProvisioningClient`と同じ内部限定エージェントを呼ぶ):

```java
@Component
public class WordPressSyncClient {
    // provisioningClientと同じRestClient/トークン構成を再利用(コンストラクタで同じbaseUrl/tokenを注入)

    public void sync(SyncCommand command) {
        try {
            client.post().uri("/sync").header("X-Provision-Token", provisionToken)
                    .body(command).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new ProvisioningException("環境同期に失敗しました: " + e.getMessage(), e);
        }
    }

    public record SyncCommand(String fromSlug, String fromDbName, String toSlug, String toDbName, List<String> targets) {}
}
```

`ProjectEnvironmentSyncService.java`(新規):

```java
@Service
public class ProjectEnvironmentSyncService {
    public void sync(Long projectId, String fromEnvironment, String toEnvironment, List<String> targets) {
        Project project = getProject(projectId);
        requireValidEnvironment(fromEnvironment);
        requireValidEnvironment(toEnvironment);
        if (fromEnvironment.equals(toEnvironment)) {
            throw new IllegalArgumentException("同期元と同期先には異なる環境を指定してください");
        }
        Site fromSite = resolveManagedSite(project, fromEnvironment);
        Site toSite = resolveManagedSite(project, toEnvironment);

        syncClient.sync(new WordPressSyncClient.SyncCommand(
                fromSite.getWpSlug(), fromSite.getWpDbName(),
                toSite.getWpSlug(), toSite.getWpDbName(), targets));
    }

    private Site resolveManagedSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
        if (siteId == null) {
            throw new IllegalArgumentException(environment + "環境にはサイトが紐付けられていません");
        }
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
        if (!site.isManagedWordpress()) {
            throw new IllegalArgumentException(environment + "環境(" + site.getSiteKey() + ")は自動構築サイトではないため同期できません");
        }
        return site;
    }
}
```

`ProjectController.java`に追加:

```java
@PostMapping("/{id}/environments/sync")
public ResponseEntity<Void> syncEnvironment(@PathVariable Long id, @Valid @RequestBody SyncEnvironmentRequest request) {
    adminAuthorizationService.requireAdmin();
    projectEnvironmentSyncService.sync(id, request.from(), request.to(), request.targets());
    return ResponseEntity.noContent().build();
}
```

`SyncEnvironmentRequest.java`(新規): `record SyncEnvironmentRequest(@NotBlank String from, @NotBlank String to, @NotEmpty List<String> targets) {}`(`targets`は`"themes"`/`"plugins"`/`"db"`の部分集合)

### インフラ(nginx)

`nginx/conf.d/default.conf`の`location /api/`ブロックの**手前**に、同期エンドポイント専用のlocationを追加(`/ollama/`と同じ`proxy_read_timeout 300s`パターン):

```nginx
location = /api/projects/environments/sync-passthrough {
    # 実際にはpath変数を使わずプレフィックスマッチできないため、正規表現locationで
    # /api/projects/{id}/environments/sync のみを先に拾い、通常の /api/ より長いタイムアウトを適用する
}
location ~ ^/api/projects/[0-9]+/environments/sync$ {
    set $upstream_api api:8080;
    proxy_pass http://$upstream_api;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 300s;
}
```

(上記の空の`location =`ブロックは実装時に削除し、正規表現locationのみを`/api/`より前に置く。nginxは完全一致→正規表現→prefixの優先順位で評価するため、`location /api/`より前に書けば正しく優先される)

### フロントエンド

`web/src/app/projects/[id]/EnvironmentSyncPanel.tsx`(新規、`EnvironmentSlot.tsx`と同じ配置場所):

- from/to環境を`<select>`で選択(選択肢はプロジェクトに紐付いており、かつ`managedWordpress`なサイトのみ。非managed・未紐付けの環境は選択不可としてグレーアウト)
- テーマ/プラグイン/DBのチェックボックス(複数選択可)
- 送信前に`window.confirm`(`DeleteSiteButton`と同じパターン)で「{from}環境から{to}環境へ、選択した内容を同期します。{to}環境の内容は上書きされます。よろしいですか?」を表示
- `apiClient.ts`: `syncProjectEnvironment(projectId, { from, to, targets }, actor)` を追加

`web/src/app/projects/[id]/page.tsx`に`<EnvironmentSyncPanel projectId={project.id} project={project} />`を追加。

## スコープ・実装項目

実装対象:

- [x] `wordpress/provision-agent/index.php`: `/sync`ハンドラ(バックアップ→テーマ/プラグインcp -r→DBダンプ&インポート→search-replace)
- [x] `api/src/main/java/com/letsblog/api/provisioning/WordPressSyncClient.java`(新規)
- [x] `api/src/main/java/com/letsblog/api/service/ProjectEnvironmentSyncService.java`(新規)
- [x] `api/src/main/java/com/letsblog/api/dto/SyncEnvironmentRequest.java`(新規)
- [x] `ProjectController.java`: `POST /api/projects/{id}/environments/sync`
- [x] `nginx/conf.d/default.conf`: 同期専用location(300秒タイムアウト)
- [x] `web/src/lib/apiClient.ts`: `syncProjectEnvironment()`
- [x] `web/src/app/projects/[id]/EnvironmentSyncPanel.tsx`(新規)
- [x] `web/src/app/projects/[id]/page.tsx`: パネル追加
- [x] `web/src/app/projects/[id]/actions.ts`: `syncEnvironmentAction`

対象外・スコープ外:

- 外部登録(非managed)サイトへの同期
- 同期処理の非同期ジョブ化・進捗表示(プログレスバー等)
- テーマ/プラグインの個別選択同期(現状はディレクトリ全体一括のみ)
- バックアップの世代管理・自動削除(直近1世代を上書き保存するのみ)
- `wp_users`/`wp_usermeta`以外の除外テーブルの精査(プラグイン依存データ等は将来検討)

## 実装順序

1. `provision-agent/index.php`: `/sync`ハンドラ実装(themes/plugins→db→search-replaceの順)
2. `WordPressSyncClient`/`ProjectEnvironmentSyncService`/DTO/`ProjectController`
3. nginx location追加
4. フロント: `apiClient.ts`→`EnvironmentSyncPanel.tsx`→`page.tsx`→`actions.ts`
5. テスト整備・実機検証

## テスト整備

- `ProjectEnvironmentSyncServiceTest`: from/to両方managedでなければ例外、from===toで例外、環境未紐付けで例外、正常系で`WordPressSyncClient.sync`が正しい`slug`/`dbName`/`targets`で呼ばれること
- PHPエージェント: 手動テストで(a) テーマ同期後に対象ディレクトリの内容が一致すること、(b) DB同期後に`wp option get siteurl`がコピー先自身のURLのままであること(コピー元のURLに書き換わっていないこと)、(c) DB同期後もコピー先のWordPress管理者アカウント・プロジェクトメンバーでログインできること(`wp_users`/`wp_usermeta`が保持されていること)、(d) 同期前のバックアップファイルが`/var/www/html/backups/{slug}/`に作成されていること

## 実機検証

1. プロジェクトのローカル環境でテーマ・プラグインを追加し、記事を1件作成
2. プロジェクト詳細画面から「ローカル→テスト」でテーマ・プラグイン・DBすべてを同期
3. テスト環境のWordPress管理画面で、テーマ・プラグイン・記事がローカルと一致していることを確認
4. テスト環境のURL(`https://localhost/sites/{testのslug}/`)がそのまま正しく機能する(ローカルのURLに書き換わっていない)ことを確認
5. テスト環境の管理者アカウント・プロジェクトメンバーで引き続きログインできることを確認(ユーザーテーブルが上書きされていないこと)
6. 同期前のバックアップが作成されていることをコンテナ内で確認
