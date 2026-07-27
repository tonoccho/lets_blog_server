# 02. Spring Boot API サーバー拡張

## 目的

ユーザー認証・ユーザー管理機能を提供するAPIエンドポイントを追加し、Web管理フロントエンドのログイン・ユーザーCRUD処理を支える。

## 前提・決定事項

- 既存の固定APIキー認証(`ApiKeyAuthFilter`, [phase1/03-api-server](../phase1/03-api-server.md)参照)は変更しない。新規エンドポイント群も同じ`ApiKeyAuthFilter`配下に置く。
- パスワードハッシュはBCrypt(`spring-security-crypto`をライブラリ追加。spring-securityフレームワーク全体は導入しない)。
- 権限チェックはNext.js側(セッション情報の`role`)で行い、APIサーバー側は「Web管理フロントから来た正当なリクエスト」の検証のみに徹する(二重実装を避ける)。

## 主要APIエンドポイント(新規)

| メソッド | パス | 用途 | リクエスト / レスポンス | 状態 |
|---|---|---|---|---|
| POST | `/api/auth/login` | ユーザー認証 | req: `{ email, password }` / res: `{ id, email, role }` (認証失敗時は401) | 実装待ち |
| GET | `/api/users` | ユーザー一覧取得 | res: `[{ id, email, role, created_at }, ...]` | 実装待ち |
| POST | `/api/users` | ユーザー新規作成 | req: `{ email, password, role }` / res: `{ id, email, role }` | 実装待ち |
| PATCH | `/api/users/{id}` | ユーザー情報更新(role/パスワード) | req: `{ role?, password? }` / res: `{ id, email, role }` | 実装待ち |
| DELETE | `/api/users/{id}` | ユーザー削除 | status: 204 No Content | 実装待ち |

### `/api/auth/login` 詳細

- リクエスト: `application/json`で `email`, `password` を送付
- ユーザーを `email` で検索、存在しない場合は401 Unauthorized
- 存在する場合、`password_hash` に対してBCryptで照合。不一致なら401
- 一致した場合、`{ id, email, role }` をJSONで返す
- レスポンスにはセッション情報なし。Next.js側でこのレスポンスをAuth.jsに渡して、セッションCookie生成

### `/api/users` 一覧詳細

- パスワードハッシュは返さない(ログイン後のユーザー情報表示用)
- role で絞り込みクエリパラメータは当面不要

### `/api/users` 新規作成詳細

- email が既に存在する場合は409 Conflict(`{ error: "Email already exists" }`)
- role が `admin` / `user` 以外の場合は400 Bad Request
- 作成成功後は201 Created でレスポンス

### `/api/users/{id}` 更新詳細

- role 変更のみ、またはパスワード再設定のみ、両方指定も可
- role が無効値なら400
- id が存在しない場合は404 Not Found
- 成功後は200 OK

### `/api/users/{id}` 削除詳細

- 自分自身(セッションユーザー)の削除は認めない(400 Bad Request)。管理者権限なし時の削除試行の防止。※実装時に検討: APIサーバー側で禁止するか、Next.js側で禁止するか(後者が責任分離として望ましい)
- id が存在しない場合は404
- 成功後は204 No Content

## 実装の詳細方針

### パスワード処理

- 新規作成・更新時のリクエストで平文の `password` を受け取った場合のみ、BCryptハッシュ化して保存する。
- `password` フィールド不在のリクエスト(例: roleのみ更新)の場合は既存のハッシュ値を保持する。
- BCrypt実装はSpring Securityの `BCryptPasswordEncoder`(`spring-security-crypto`に含む)を再利用。

### 初回管理者のブートストラップ

- [01-database-schema](01-database-schema.md)の対応に従い、APIサーバー起動時に環境変数 `INITIAL_ADMIN_EMAIL` / `INITIAL_ADMIN_PASSWORD` をチェック。
- `users` テーブルが空の場合のみadminユーザーを自動作成する(`ApplicationRunner`インターフェース実装)。
- 冪等性を確保するため、作成済みの場合は何もしない。

### エラーハンドリング

既存の `GlobalExceptionHandler` に新規例外を追加:
- `EmailAlreadyExistsException` → 409 Conflict
- `InvalidRoleException` → 400 Bad Request
- `UserNotFoundException` → 404 Not Found
- 認証失敗時の401 は既存の認証フィルタで返す

## タスクチェックリスト

- [ ] BCryptパッケージ(`spring-security-crypto`)をbuild.gradleに追加
- [ ] `users` テーブルのJPA Entity/Repository実装
- [ ] `UserService` 実装(CRUD・BCrypt処理)
- [ ] `/api/auth/login` エンドポイント実装
- [ ] `/api/users` CRUD エンドポイント実装(GET一覧, POST作成, PATCH更新, DELETE削除)
- [ ] 初回管理者ブートストラップの `ApplicationRunner` 実装
- [ ] エラーハンドリング(`GlobalExceptionHandler`拡張)
- [ ] 実機検証(ログイン・ユーザー追加・一覧取得・削除など一通りをcurlやPostmanで確認)

## 未決事項

- ユーザー削除時に「自分自身の削除禁止」をAPIサーバー側で検証するか、Next.js側で禁止するか(責任分離の観点から後者が望ましい想定だが、実装時に検討)
- パスワード更新履歴の追跡必要性(Phase 2では不要と想定)
- ユーザー削除時に該当ユーザーが作成した投稿レコードをどうするか(Phase 2では「投稿主」カラムなし、未検討)
