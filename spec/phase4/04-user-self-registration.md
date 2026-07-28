# 04. ユーザーセルフサインアップ + 初期管理者アカウントの安全な払い出し

## 目的

利用者フィードバックにより以下2点を実装する:

1. ユーザーが自分でアカウントを作成できるセルフサインアップ機能を追加する(現状は admin がユーザーを作成する運用のみ)
2. 初期管理者アカウントの認証情報を `.env` に固定値で平文で置く現行方式を廃止し、認証情報がファイルに残らない方式に変更する

## 現状調査結果

- セルフサインアップ相当のエンドポイントは存在しない。[`UserController`](../../api/src/main/java/com/letsblog/api/controller/UserController.java) は `GET/POST /api/users`、`PATCH /api/users/{id}`、`DELETE /api/users/{id}` のみで、管理者による操作を前提とした設計になっている。
- 初期管理者アカウントは [`InitialAdminBootstrap.java`](../../api/src/main/java/com/letsblog/api/config/InitialAdminBootstrap.java)(`ApplicationRunner`)が `users` テーブルが空の場合のみ、`INITIAL_ADMIN_EMAIL`/`INITIAL_ADMIN_PASSWORD` から admin を1件作成する仕組み。
- 同値の参照箇所:
  - `api/src/main/resources/application.yml`(`${INITIAL_ADMIN_EMAIL:}` / `${INITIAL_ADMIN_PASSWORD:}` のプロパティマッピング)
  - `docker-compose.yml`(APIコンテナへの環境変数注入)
  - `.env.example`(サンプル値としてプレースホルダーを記載)
  - `spec/phase2/01-database-schema.md`、`spec/phase2/02-api-server.md`(Phase2仕様書内の記載)
- 開発環境のローカル `.env`(gitで追跡されない開発用ファイル)には実際の値として `admin@example.com` / `dev_admin_password` が設定されていた。値自体は開発者が変更可能で、`.env` はコミット対象外だが、固定の認証情報がファイルに存在すること自体がフィードバックで指摘された問題点。

## 前提・決定事項(要確認)

初期管理者の払い出し方式について、以下2案を比較検討する(結論は未決事項として提示):

| 案 | 概要 | メリット | デメリット |
|---|---|---|---|
| A. ランダムパスワード生成+ログ出力 | 初回起動時に `InitialAdminBootstrap` がランダムなパスワードを生成し、ファイルには残さずアプリログにのみ一度出力する | 実装コストが低い、既存の仕組みを流用できる | ログの保管・アクセス制御が別途必要。ログがどこかに残ればファイルに残すのと大差ない |
| B. 初回セットアップ画面 | `users` テーブルが空の場合、Web側で「初回セットアップ」画面を表示し、その場で管理者のメールアドレス・パスワードを入力させて作成する | 認証情報がどこにも保存されずユーザー自身が決められる。セルフサインアップ機能と設計を共有できる | 実装コストがやや高い(未認証状態からのみアクセス可能な専用エンドポイント・画面が必要) |

案Bを推奨(セルフサインアップの仕組みと共通化できるため)。ただし最終判断はユーザー確認後に本ドキュメントを更新する。

セルフサインアップのロールについて:
- 新規登録者のデフォルトロールは一般ユーザー(admin以外)とする
- admin による承認制にするか、登録直後から利用可能にするかは未決事項

## 対応方針

1. `POST /api/auth/signup` エンドポイントを新設し、メールアドレス・パスワードでの新規ユーザー作成を可能にする(バリデーションは既存の `UserCreateRequest` 相当を流用)。
2. `InitialAdminBootstrap.java` を、案Bを採用する場合は削除し、代わりに「`users` テーブルが空の場合は Web 側の `/setup` 等の初回セットアップ画面へ誘導し、そこで最初の admin を作成する」フローに置き換える。
3. `.env.example`・`docker-compose.yml`・`application.yml` から `INITIAL_ADMIN_EMAIL`/`INITIAL_ADMIN_PASSWORD` の記載を除去する(案Bを採用する場合)。
4. `spec/phase2/01-database-schema.md`・`spec/phase2/02-api-server.md` 内の旧方式の記載について、Phase4での変更を反映するかは別途検討(既存のPhase2ドキュメントを遡って書き換えるかどうかは要相談)。

## タスクチェックリスト

- [ ] 初期管理者払い出し方式(案A/案B)をユーザーと確定する
- [ ] `POST /api/auth/signup` エンドポイント実装(DTO・バリデーション・パスワードハッシュ化)
- [ ] セルフサインアップのデフォルトロール・有効化フロー(即時 or 承認制)を実装
- [ ] 初回セットアップ画面(案B採用時): `users` テーブルが空の場合のみアクセス可能な専用エンドポイント・Web画面を実装
- [ ] `InitialAdminBootstrap.java` の置き換え or 削除
- [ ] `.env.example`/`docker-compose.yml`/`application.yml` から固定デフォルト認証情報の記載を除去
- [ ] Web フロントエンド: サインアップ画面(`/signup`)追加
- [ ] テスト整備(サインアップAPIのテスト、初回セットアップフローのテスト)
- [ ] `spec/phase2/` 内の関連記載の扱いを決定

## 未決事項

- 初期管理者払い出し方式: 案A(ランダムパスワード+ログ出力) vs 案B(初回セットアップ画面) — 上記の通り案Bを推奨するが要確定
- セルフサインアップ後のデフォルトロールと有効化フロー(即時利用可 vs admin承認制)
- セルフサインアップを無効化するオプション(招待制のみで運用したい場合の設定フラグ)が必要か
- 既存の `UserController` の認可(admin専用であるべき箇所)がコード上どこで担保されているか未確認のため、セルフサインアップ追加に伴い認可設計全体を見直す必要がないか要調査
