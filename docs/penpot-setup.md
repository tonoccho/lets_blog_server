# Penpot セットアップ手順書

## 前提条件

- Let's Blog Server が Docker で起動済み
- `docker-compose.yml` に Penpot・PostgreSQL コンテナが定義済み
- `.env` に `PENPOT_DB_PASSWORD` が設定済み
- nginx の penpot.conf が配置済み

---

## セットアップ手順

### 1. 環境変数設定確認

`.env` に以下が含まれていることを確認：

```bash
# PostgreSQL (Penpot用)
PENPOT_DB_PASSWORD=dev_penpot_db_password  # 実本番では強力なパスワードに変更
```

### 2. Docker コンテナの起動

```bash
# 1. リポジトリディレクトリへ
cd /path/to/lets_blog_server

# 2. コンテナ起動
docker compose up -d penpot_postgres penpot

# 3. 起動確認
docker compose logs -f penpot
```

**起動完了の目安** (ログ内容)：
```
lbs-penpot | [...] INFO  o.s.b.w.embedded.tomcat.TomcatWebServer -- Tomcat started on port(s): 8080 (http)
```

または Penpot 独自の起動メッセージ。

**タイムアウト:** Penpot は初回起動時に DB マイグレーションを実行するため、起動に 30-60 秒かかる場合があります。

### 3. ブラウザからアクセス

```
https://localhost/penpot
```

**自己署名証明書の警告が表示されますが、「例外を追加」「続行」等で進んでください。**

### 4. ユーザー登録（初回のみ）

Penpot UI が表示されたら：

1. **「Sign up」をクリック**
2. **ユーザー情報を入力**
   - メール: `admin@letsblog.local`
   - パスワード: 強力なパスワード（12文字以上推奨）
   - 確認: パスワード再入力
3. **「Create account」をクリック**
4. **確認メール確認** (ローカル開発環境では不要な場合がある)

### 5. ログイン確認

```
✓ メール・パスワードでログイン可能
✓ ダッシュボード表示
✓ 新規プロジェクト作成可能
```

---

## ユーザーアカウント管理

### 管理者アカウント

| 項目 | 値 |
|------|-----|
| メール | `admin@letsblog.local` |
| ロール | Admin（全機能利用可） |
| 作成方法 | セットアップ時のサインアップ |

### 追加ユーザー（オプション）

後続のフェーズで、Let's Blog の他のユーザーを Penpot にも登録可能。

---

## Penpot プロジェクト初期化

### 1. ダッシュボード へ移動

```
https://localhost/penpot
```

### 2. 新規プロジェクト作成

- **プロジェクト名**: `Let's Blog Design`
- **説明** (オプション): `Design system and components for Let's Blog Server`
- **[Create]** をクリック

### 3. ファイル作成

- **ファイル名**: `Design System v1`
- **[Create File]** をクリック

### 4. キャンバス開始

Penpot エディタが起動し、デザイン作業が可能になります。

---

## 環境変数・認証情報

### Penpot API トークン（MCP連携用）

後の Phase B（MCP 実装）で必要になります。

**取得方法:**

1. Penpot ダッシュボード右上 → **Settings**
2. **API** または **Tokens** セクション
3. **Generate API Token** をクリック
4. トークンをコピー・保存

トークンは `.env` または `secrets` ファイルで管理してください。

```bash
# .env（例）
PENPOT_API_TOKEN=your_api_token_here
```

---

## ボリューム・データの永続性確認

### PostgreSQL ボリューム確認

```bash
# Penpot データベースボリューム確認
docker volume ls | grep penpot_postgres

# ボリューム詳細
docker volume inspect penpot_postgres
```

**出力例:**
```
[
    {
        "Name": "penpot_postgres",
        "Driver": "local",
        "Mountpoint": "/var/lib/docker/volumes/penpot_postgres/_data",
        ...
    }
]
```

### データ永続性テスト

```bash
# コンテナ停止
docker compose stop penpot penpot_postgres

# コンテナ再起動
docker compose up -d penpot penpot_postgres

# ✓ 作成済みプロジェクト・ファイルが残存することを確認
```

---

## トラブルシューティング

### Q: `https://localhost/penpot` が 404 を返す

**原因 1: Penpot コンテナが起動していない**

```bash
docker compose ps | grep penpot
```

起動していなければ：
```bash
docker compose up -d penpot
docker compose logs penpot
```

**原因 2: nginx が penpot.conf を読み込んでいない**

```bash
docker compose exec reverse-proxy nginx -t
docker compose exec reverse-proxy nginx -s reload
```

**原因 3: 接続タイムアウト**

Penpot は初回起動時に時間がかかる場合があります。1-2 分待ってからアクセスしてください。

---

### Q: PostgreSQL が起動しない

```bash
docker compose logs penpot_postgres
```

**一般的なエラー:**

- `FATAL: database "penpot" does not exist`
  → PostgreSQL コンテナが正常に初期化されていない
  → ボリューム削除して再起動
  ```bash
  docker volume rm penpot_postgres
  docker compose up -d penpot_postgres
  ```

---

### Q: Penpot ログイン画面が表示されない

```bash
docker compose logs penpot | grep -i error
```

**確認事項:**
- [ ] PostgreSQL が起動・接続可能か
- [ ] `PENPOT_POSTGRES_URI` 環境変数が正しいか
- [ ] `PENPOT_PUBLIC_URI` が `https://localhost/penpot` か

---

### Q: WebSocket エラー（リアルタイム編集が動作しない）

**ログ例:**
```
WebSocket is closed before the connection is established
```

**原因:** nginx の WebSocket 設定が不足している

**対応:**
```bash
# penpot.conf に以下が含まれているか確認
cat infra/nginx/conf.d/penpot.conf | grep -A2 "Upgrade"
```

出力に以下が含まれていることを確認：
```
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
```

---

## デバッグ・ログ確認

### Penpot ログ

```bash
docker compose logs -f lbs-penpot --tail=100
```

### PostgreSQL ログ

```bash
docker compose logs -f lbs-penpot-postgres --tail=100
```

### nginx ログ

```bash
docker compose logs -f lbs-reverse-proxy --tail=100
```

---

## まとめ

| チェック項目 | 確認方法 |
|-------------|---------|
| ✓ Penpot 起動 | `docker compose ps \| grep penpot` |
| ✓ PostgreSQL 起動 | `docker compose ps \| grep postgres` |
| ✓ ブラウザアクセス | `https://localhost/penpot` → ログイン画面表示 |
| ✓ ユーザーログイン | メール・パスワードでログイン成功 |
| ✓ プロジェクト作成 | 新規プロジェクト作成可能 |
| ✓ データ永続化 | コンテナ再起動後もプロジェクト残存 |

すべてチェック完了で A-4 完成。

---

## 次のフェーズ

- **Phase B**: MCP サーバー設計・実装（Ollama 連携）
- **Phase C**: デザインシステム構築
