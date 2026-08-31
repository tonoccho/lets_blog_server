# 01. 環境同期: ローカル環境を同期元に指定できない制限

## 目的

Phase10-03で実装済みの環境同期(テーマ・プラグイン・メディア・DBのまるごとコピー、Phase11-02でメディアを追加)について、**ローカル環境を同期元(`from`)に指定できない**制限を追加する。ローカルは開発者の手元で自由に書き換えられる未検証な環境であり、これをそのままテスト・本番へ同期先として反映させてしまうと、テスト・本番環境の内容(正としての情報)が意図せず上書きされる事故につながるため。

## 現状確認

- [ProjectEnvironmentSyncService.java](../../api/src/main/java/com/letsblog/api/service/ProjectEnvironmentSyncService.java)の`sync()`は、`from`/`to`が有効な環境名(`local`/`test`/`production`)であること・`from != to`であることのみを検証しており、`from`にどの環境を指定してもよい
- [EnvironmentSyncPanel.tsx](../../web/src/app/projects/%5Bid%5D/EnvironmentSyncPanel.tsx)の「同期元」`<select>`は、managedWordpressな環境(ローカル含む)をすべて選択肢として表示している
- バックエンド側にバリデーションがないため、フロントエンドの選択肢を絞るだけでは同一の制限をAPI直叩きで回避できてしまう

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 制限の対象 | `from`(同期元)のみ。`to`(同期先)には引き続きローカルを指定できる(テスト・本番の内容をローカルへ取り込む用途は許可する) |
| バリデーション箇所 | `ProjectEnvironmentSyncService.sync()`(API層)で必須実装する。フロントエンドの選択肢からも`local`を除外するが、これは補助的なUXであり、真のガードはサービス層で行う |
| エラーメッセージ | 「ローカル環境は同期元に指定できません」(`IllegalArgumentException`、既存の`from === to`エラーと同じ扱いで400を返す) |
| 影響範囲 | 同期対象(テーマ/プラグイン/メディア/DB)の種別に関わらず一律で適用する(DBのみ・テーマのみの同期であっても`from=local`は不可) |

## アーキテクチャ・実装詳細

### バックエンド(Spring Boot)

`ProjectEnvironmentSyncService.java`の`sync()`冒頭、`from == to`のチェックの直後に追加:

```java
if ("local".equals(fromEnvironment)) {
    throw new IllegalArgumentException("ローカル環境は同期元に指定できません");
}
```

`ProjectController.syncEnvironment()`・`SyncEnvironmentRequest`は変更不要(バリデーションはサービス層で完結する)。

### フロントエンド

`EnvironmentSyncPanel.tsx`:

- 「同期元」の`<select>`の選択肢生成を、`syncableEnvironments`から`local`を除いた`syncSourceEnvironments`(`test`/`production`のうちmanagedWordpressなもの)に変更する。「同期先」の`<select>`は従来通り`syncableEnvironments`(ローカル含む)を使う
- 同期元候補が0件(テスト・本番のいずれもmanaged環境として紐付いていない)の場合、パネルの案内文を「テスト環境または本番環境が1つ以上、自動構築(managed)されたWordPressとして紐付いている場合に同期元として選択できます」等に更新する
- `handleSubmit`の確認ダイアログ文言は変更不要(from/toの値をそのまま表示しているため)

`actions.ts`の`syncEnvironmentAction`は変更不要(バックエンドのエラーメッセージがそのまま`state.error`に表示される)。

## スコープ・実装項目

実装対象:

- [ ] `api/src/main/java/com/letsblog/api/service/ProjectEnvironmentSyncService.java`: `from == "local"`のバリデーション追加
- [ ] `web/src/app/projects/[id]/EnvironmentSyncPanel.tsx`: 同期元`<select>`からローカルを除外、案内文の調整

対象外・スコープ外:

- 同期先(`to`)への制限追加
- テーマ/プラグイン/メディア/DBの対象種別ごとの個別制限(一律`from=local`を禁止する)

## 実装順序

1. `ProjectEnvironmentSyncService`にバリデーション追加
2. `ProjectEnvironmentSyncServiceTest`にテストケース追加
3. `EnvironmentSyncPanel.tsx`の選択肢調整
4. 実機検証

## テスト整備

- [ProjectEnvironmentSyncServiceTest.java](../../api/src/test/java/com/letsblog/api/service/ProjectEnvironmentSyncServiceTest.java)(既存クラスへ追加): `from="local"`を指定した場合に`IllegalArgumentException`(「ローカル環境は同期元に指定できません」)がスローされ、`WordPressSyncClient.sync`が一切呼ばれないこと。`to="local"`・`from="test"`の組み合わせは従来通り成功すること

## 実機検証

1. プロジェクト詳細画面の環境同期パネルで、「同期元」の選択肢にローカルが表示されないことを確認
2. 「同期先」の選択肢にはローカルが表示されることを確認(テスト→ローカルの同期が可能なことを確認)
3. APIを直接叩いて`from=local`を指定した場合に400エラー(「ローカル環境は同期元に指定できません」)が返ることを確認
