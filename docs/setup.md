# セットアップマニュアル

Let's Blog Server の開発環境を構築する手順。Phase 6 でリバースプロキシ(nginx)が導入され、
以降はすべてのサービスに `https://localhost` 経由でアクセスする。

## クイックスタート

```bash
git clone <このリポジトリのURL>
cd lets_blog_server

# 1. 環境変数を設定
cp .env.example .env
vi .env   # パスワード・APIキー・暗号化キー等を変更

# 2. リバースプロキシ用の自己署名証明書を生成
bash scripts/generate-certs.sh

# 3. Docker Compose で各サービスを起動(api/mysql/phpmyadmin/ollama/comfyui/plantuml/mailhog/reverse-proxy)
docker compose up -d

# 4. Web管理画面(Next.js)は現状Docker外で起動する
cd web
cp .env.local.example .env.local
vi .env.local   # NEXTAUTH_SECRET等を変更
npm install
npm run dev

# 5. ブラウザで https://localhost にアクセス(自己署名証明書の警告は例外承認する)
```

## システム要件

- Docker / Docker Compose(Compose v2 系。`docker compose version` で確認)
- Node.js(`web/.node-version` 相当。Web管理画面をローカルで起動する場合に必要)
- openssl(証明書生成に使用。Linux/macOSは標準搭載)
- NVIDIA GPU + NVIDIA Container Toolkit(Ollama・ComfyUIのGPU利用に推奨。CPUのみでも動作するイメージタグに変更すれば起動は可能だが低速)
- ホストの 80番・443番ポートが空いていること(リバースプロキシが使用)

## 1. `.env` の設定

`.env.example` をコピーして `.env` を作成し、以下を変更する。

| 項目 | 説明 | 変更要否 |
|---|---|---|
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | MySQLのパスワード | 必須変更 |
| `SERVER_API_KEY` | Web管理画面・VSCode拡張が使う固定APIキー(`X-API-Key`ヘッダ) | 必須変更 |
| `APP_ENCRYPTION_KEY` | CMS認証情報暗号化キー(Base64, 32バイト)。生成例: `openssl rand -base64 32` | 必須変更 |
| `COMFYUI_IMAGE` | ComfyUIイメージ(GPU種別に応じて変更。既定はNVIDIA CUDA13系) | 環境に応じて変更 |
| `OLLAMA_MODEL` | 下書き/校正/要約・タグ提案で使うOllamaモデル | 既定値のままでも可 |
| `COMFYUI_CHECKPOINT` | 画像生成に使うチェックポイントファイル名 | 既定値のままでも可 |
| `APP_MAIL_FROM` / `APP_WEB_BASE_URL` | メール送信元・Web公開URL(メール内リンク生成に使用) | `APP_WEB_BASE_URL` は `https://localhost` を指定 |

## 2. TLS証明書の生成

リバースプロキシ(nginx)が `https://localhost` を終端するための自己署名証明書を生成する。

```bash
bash scripts/generate-certs.sh
```

- `certs/localhost.crt` / `certs/localhost.key` を生成する(初回のみ。既にあれば何もしない)
- 秘密鍵を含むため `certs/` はコミット対象外([.gitignore](../.gitignore))
- 証明書を作り直す場合は `certs/` 配下を削除してから再実行する

## 3. Docker Compose の起動

```bash
docker compose up -d
```

起動するサービス: `reverse-proxy`(nginx) / `api` / `mysql` / `phpmyadmin` / `ollama` / `comfyui` / `plantuml` / `mailhog`。

Phase 6 以降、`reverse-proxy` の `80`(HTTP→HTTPSリダイレクト)・`443`(HTTPS)以外はホストにポート公開していない。
各サービスへは直接ポートではなく、必ず `https://localhost/...` 経由でアクセスする。

```bash
docker compose ps           # 起動状況確認
docker compose logs -f api  # 個別サービスのログ確認
```

### アクセスURL一覧

| サービス | URL | 用途 |
|---|---|---|
| Web管理画面 | https://localhost/ | サイト管理・投稿履歴・AIジョブ・ユーザー管理等(Next.js、Docker外で別途起動) |
| 仲介APIサーバー | https://localhost/api/ | REST API(VSCode拡張・Web管理画面が使用) |
| phpMyAdmin | https://localhost/phpmyadmin/ | MySQLデータベース管理 |
| Ollama | https://localhost/ollama/ | ローカルLLM API(UIなし。`GET /ollama/api/tags` 等) |
| ComfyUI | https://localhost/comfyui/ | 画像生成ワークフローUI |
| PlantUML | https://localhost/plantuml/ | 図のプレビュー・検証用 |
| Mailhog | https://localhost/mailhog/ | 開発時のメール送受信確認(送信先の実メールサーバーの代わり) |

### ブラウザの自己署名証明書警告について

`https://localhost` は自己署名証明書を使用しているため、初回アクセス時にブラウザで
「この接続ではプライバシーが保護されません」等の警告が表示される。「詳細設定」→
「localhost にアクセスする(安全ではありません)」等から例外承認して進める(表記はブラウザにより異なる)。

## 4. Ollamaモデルの準備

`.env` の `OLLAMA_MODEL`(既定: `qwen2.5:7b-instruct`)に対応するモデルを事前にpullする。

```bash
docker exec lbs-ollama ollama pull qwen2.5:7b-instruct
docker exec lbs-ollama ollama list   # 取得済みモデルの確認
```

`OLLAMA_MODEL` を別モデルに変更する場合は、そのモデルも同様にpullしてから `.env` を変更し
`docker compose up -d api` で反映する。

## 5. ComfyUIチェックポイントの配置

`comfyui_models` はDocker管理の名前付きボリュームであり、ホストの特定ディレクトリに
直接バインドされていない。チェックポイントファイルは `docker cp` でコンテナ内にコピーする。

```bash
docker cp <ダウンロードしたcheckpointファイル> lbs-comfyui:/root/ComfyUI/models/checkpoints/
```

配置後、`.env` の `COMFYUI_CHECKPOINT` にファイル名を設定し、`docker compose up -d api` で反映する。
配置確認:

```bash
docker exec lbs-comfyui ls /root/ComfyUI/models/checkpoints/
```

## 6. Web管理画面(Next.js)の起動

Web管理画面は現状 Docker Compose の対象外で、ホスト上で直接起動する。

```bash
cd web
cp .env.local.example .env.local
```

`.env.local` の内容:

| 項目 | 説明 |
|---|---|
| `LETS_BLOG_API_URL` | 仲介APIサーバーのURL。リバースプロキシ経由の `https://localhost` を指定(直接の `:8080` は非公開のため使用不可) |
| `LETS_BLOG_API_KEY` | `.env` の `SERVER_API_KEY` と同じ値 |
| `NEXTAUTH_SECRET` | セッション署名鍵。生成例: `openssl rand -hex 32` |
| `NEXTAUTH_URL` | Web管理画面の外部公開URL。`https://localhost` を指定 |
| `NODE_EXTRA_CA_CERTS` | `../certs/localhost.crt` を指定(下記参照) |

Next.jsサーバー自身がAPI呼び出し(`proxy.ts`/`apiClient.ts`)で `https://localhost` へ
サーバーサイドfetchを行うため、Node.jsが自己署名証明書を信頼できるよう
`NODE_EXTRA_CA_CERTS` で証明書ファイルを指定する必要がある。未設定の場合、
`DEPTH_ZERO_SELF_SIGNED_CERT` エラーで通信に失敗する。

```bash
npm install
npm run dev
```

起動後、`https://localhost/`(nginx経由でホストの3000番へプロキシされる)でアクセスする。

## 7. 初回管理者アカウントの作成

初回アクセス時、まだユーザーが1人も存在しない場合は `/setup` にリダイレクトされ、
セルフサインアップで最初のユーザー(管理者権限)を作成できる。

## 8. VSCode拡張の設定

拡張の設定 `letsBlog.serverUrl`(既定値: `https://localhost`)と、
APIキー(`Let's Blog: Set API Key` コマンドで `SERVER_API_KEY` と同じ値を設定)が必要。

拡張(VSCodeの拡張ホスト、Node.jsで動作)から `https://localhost` へアクセスする際も
自己署名証明書の検証が行われるため、VSCodeを起動するシェルで
`NODE_EXTRA_CA_CERTS=/path/to/certs/localhost.crt` を設定してから `code .` 等で起動するか、
OS/ブラウザの証明書ストアに `certs/localhost.crt` を信頼済み証明書として登録する。

## トラブルシューティング

**ポート80/443が使用中で `docker compose up -d` が失敗する**
他のWebサーバー等が既にそのポートを使用していないか確認する(`sudo ss -ltnp | grep -E ':(80|443)'`)。

**`https://localhost/` (Web管理画面) が 502 を返す**
Web管理画面(Next.js dev server)がホスト側で起動していない、または `LETS_BLOG_API_URL` 等の
環境変数変更後にプロセスを再起動していない可能性がある。`npm run dev` が起動しているか、
ポート3000でLISTENしているか確認する。

**Web管理画面からのAPI呼び出しが `DEPTH_ZERO_SELF_SIGNED_CERT` で失敗する**
`web/.env.local` の `NODE_EXTRA_CA_CERTS` が正しいパス(`../certs/localhost.crt`)を
指しているか確認し、`npm run dev` を再起動する(環境変数の変更はプロセス再起動が必要)。

**GPU (NVIDIA) が認識されない**
ホスト側で `nvidia-smi` が動作するか、NVIDIA Container Toolkitが導入済みか確認する。
`docker compose logs ollama` / `docker compose logs comfyui` でGPU認識ログを確認できる。

**Ollamaでモデルが見つからないと言われる**
`docker exec lbs-ollama ollama list` でpull済みか確認し、未取得なら
`docker exec lbs-ollama ollama pull <モデル名>` を実行する。

**個別ポート(8080/8081/11434/8188/8085等)に直接アクセスできない**
Phase 6以降は意図した仕様(すべて `https://localhost/...` 経由に一本化)。
デバッグ目的で一時的に直接アクセスしたい場合は、該当サービスの `docker-compose.yml` に
一時的に `ports:` を追加する(恒久的な変更はしないこと)。

## 関連ドキュメント

- [spec/phase6/00-overview.md](../spec/phase6/00-overview.md) — リバースプロキシ導入の全体設計
- [spec/phase6/01-reverse-proxy.md](../spec/phase6/01-reverse-proxy.md) — nginx設定の詳細
- [.env.example](../.env.example) — 環境変数の全項目
