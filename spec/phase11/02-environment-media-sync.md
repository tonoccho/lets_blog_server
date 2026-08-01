# 02. プロジェクト環境同期へのメディアファイル(uploads)対応

## 目的

Phase10-03で実装済みの環境間同期(`POST /api/projects/{id}/environments/sync`)は、テーマ・プラグイン・DBの3種類が同期対象になっているが、メディアファイル(`wp-content/uploads`)が対象外のため、記事本文中の画像等はコピー元とコピー先で食い違ったままになる。例えば「テスト環境で確認した変更をローカルへコピーしたい」場合に、DBの投稿データはコピーされても添付画像が表示されない状態になる。本タスクでは`media`(uploads)を同期対象に追加する。

## 現状確認

- 既存の同期対象は`wordpress/provision-agent/index.php`の`/sync`ハンドラで`ALLOWED_SYNC_TARGETS = ['themes', 'plugins', 'db']`として定義されており、`themes`/`plugins`はディレクトリ全体を`tar`バックアップ→`rm -rf`→`cp -r`で無条件上書きする方式、`db`は`mysqldump`|`mysql`で無条件上書き後に`wp search-replace`でURLを補正する方式になっている([wordpress/provision-agent/index.php:219-295](../../wordpress/provision-agent/index.php)参照)
- 差分チェックを行わずマスター側の内容で全上書きするという要件は、既存のthemes/plugins/db同期において既に満たされている実装方針であり、本タスクはこの既存方式をuploadsディレクトリにも当てはめるだけでよい
- `wp-content/uploads`は`themes`/`plugins`と同じく`wp-content`直下のディレクトリであり、`tar`バックアップ→`rm -rf`→`cp -r`のロジックがそのまま使い回せる
- フロントエンドの`EnvironmentSyncTarget`型([web/src/lib/apiClient.ts:568](../../web/src/lib/apiClient.ts))は`"themes" | "plugins" | "db"`の3値のみで、`EnvironmentSyncPanel.tsx`のチェックボックスも3つ固定
- バックエンド(`SyncEnvironmentRequest`/`ProjectEnvironmentSyncService`/`WordPressSyncClient`)は`targets`を`List<String>`のまま素通しするだけで、許可される値のバリデーションはprovision-agent側の`ALLOWED_SYNC_TARGETS`にしか存在しない。そのためJavaレイヤーの変更は不要

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 対象ディレクトリ | `wp-content/uploads`。同期対象の外部公開名は`media`とする(ユーザー向け表記との対応: UI上は「メディア」) |
| 実現方式 | 既存の`themes`/`plugins`と同じバックアップ→`rm -rf`→`cp -r`方式をそのまま適用する。専用の差分同期(rsync等)は導入しない |
| バリデーション | provision-agentの`ALLOWED_SYNC_TARGETS`に`'media'`を追加するのみ。Javaレイヤーの`SyncEnvironmentRequest`に変更は不要(既存通りprovision-agent側で許可値を検証) |
| バックアップ対象ファイル名 | `{backupDir}/media-{timestamp}.tar.gz`(themes/pluginsと同じ命名規則) |

## アーキテクチャ・実装詳細

### `wordpress/provision-agent/index.php`の変更

`ALLOWED_SYNC_TARGETS`にmediaを追加し、`targets`名とディレクトリ名のマッピングを導入する(mediaのみディレクトリ名`uploads`が異なるため):

```php
const ALLOWED_SYNC_TARGETS = ['themes', 'plugins', 'media', 'db'];
const SYNC_TARGET_DIRS = ['themes' => 'themes', 'plugins' => 'plugins', 'media' => 'uploads'];

// ...(targets検証は変更なし。ALLOWED_SYNC_TARGETSに'media'が含まれるだけで自動的に許可される)

foreach (SYNC_TARGET_DIRS as $target => $dirName) {
    if (!in_array($target, $targets, true)) {
        continue;
    }
    $fromContentPath = "$fromPath/wp-content/$dirName";
    $toContentPath = "$toPath/wp-content/$dirName";
    if (!is_dir($fromContentPath)) {
        continue;
    }
    runCommand(['tar', '-czf', "$backupDir/{$target}-{$timestamp}.tar.gz", '-C', "$toPath/wp-content", $dirName]);
    runCommand(['rm', '-rf', $toContentPath]);
    [$code, $out] = runCommand(['cp', '-r', $fromContentPath, $toContentPath]);
    if ($code !== 0) {
        respond(500, ['error' => "{$target}の同期に失敗しました", 'detail' => $out]);
    }
}
```

(既存の`foreach (['themes', 'plugins'] as $type)`ループを上記の`SYNC_TARGET_DIRS`ベースのループへ置き換える。`db`ブロックは変更なし)

### バックエンド(Spring Boot)

変更なし。`SyncEnvironmentRequest.targets()`は自由な文字列リストとしてそのままprovision-agentへ渡っているため、`"media"`を含むリストを受け付けても既存コードは無修正で動作する。

### フロントエンド

`web/src/lib/apiClient.ts`:

```ts
export type EnvironmentSyncTarget = "themes" | "plugins" | "media" | "db";
```

`web/src/app/projects/[id]/EnvironmentSyncPanel.tsx`の同期対象チェックボックスに追加:

```tsx
<label className="flex items-center gap-1.5">
  <input type="checkbox" name="targets" value="media" />
  メディア
</label>
```

(DBチェックボックスの前に配置し、テーマ/プラグイン/メディア/DBの順に揃える)

`web/src/app/projects/[id]/actions.ts`の`syncEnvironmentAction`エラーメッセージ文言(「同期する対象(テーマ/プラグイン/DB)を1つ以上選択してください。」)を「テーマ/プラグイン/メディア/DB」に更新する。

## スコープ・実装項目

実装対象:

- [x] `wordpress/provision-agent/index.php`: `ALLOWED_SYNC_TARGETS`へ`media`追加、themes/plugins同期ループを`SYNC_TARGET_DIRS`ベースに変更
- [x] `web/src/lib/apiClient.ts`: `EnvironmentSyncTarget`型に`"media"`追加
- [x] `web/src/app/projects/[id]/EnvironmentSyncPanel.tsx`: メディアのチェックボックス追加
- [x] `web/src/app/projects/[id]/actions.ts`: バリデーションエラー文言の更新

対象外・スコープ外:

- uploads内の年月別ディレクトリ(`YYYY/MM`)単位での部分同期(常にディレクトリ全体一括)
- 大容量メディア(数GB単位)を想定した非同期化・進捗表示

## 実装順序

1. `wordpress/provision-agent/index.php`の変更・コンテナ再ビルド
2. フロントエンド(`apiClient.ts` → `EnvironmentSyncPanel.tsx` → `actions.ts`)
3. 実機検証

## テスト整備

- 既存の`ProjectEnvironmentSyncServiceTest`に変更は不要(targetsを素通しするだけのため)。念のため`targets`に`"media"`を含むケースで`WordPressSyncClient.sync`が正しく呼ばれることを確認するテストケースを追加する
- PHPエージェント: 手動テストで、mediaを指定した同期後に`wp-content/uploads`の内容(ディレクトリ構成・ファイル)がコピー元と一致すること、バックアップ(`media-{timestamp}.tar.gz`)が作成されていることを確認する

## 実機検証

1. プロジェクトのテスト環境で記事に画像を添付し、公開
2. プロジェクト詳細画面から「テスト→ローカル」でメディア・DBを同期
3. ローカル環境のWordPress管理画面で該当記事を開き、添付画像が正しく表示されること(コピー元URLのままになっていないこと)を確認
4. 同期前のバックアップ(`/var/www/html/backups/{slug}/media-*.tar.gz`)が作成されていることをコンテナ内で確認
