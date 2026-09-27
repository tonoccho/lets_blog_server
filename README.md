# Let's Blog Server

VSCode上でMarkdownを執筆し、複数のWordPressサイトへ投稿できる自己ホスト型の仲介システム。
AI執筆支援(外部LLMサービス)・チャットでの壁打ちからのプロンプト生成込みのアイキャッチ/挿絵の自動生成(ComfyUI)・
図表レンダリング(PlantUML)を含む一式を
Docker Composeでまとめて起動する。クライアントはVSCode拡張機能(執筆・投稿)とWeb管理画面
(サイト管理・投稿履歴・ユーザー管理等)の2つ。

現在はマイクロサービス化 + Keycloak認証基盤への移行(Epic #551)を進行中で、以下はその現状と目標。
設計上の意思決定は [docs/adr/](docs/adr/README.md) に、コンテナ構成の詳細は
[docs/DOCKER_COMPOSE_ARCHITECTURE.md](docs/DOCKER_COMPOSE_ARCHITECTURE.md) にある。

## アーキテクチャ

ドメイン単位のマイクロサービス構成。認証は Keycloak(OIDC)発行の JWT へ一括切り替え済み
(#566)で、旧来のヘッダベースの自己申告方式([ADR-0002](docs/adr/0002-keycloak-oidc.md) の
Context 参照)は撤去した。分割前の単一サービス `legacy-api` は #583 で解体・削除し、
その旧 MySQL スキーマも #785 で廃止した(Epic #551 完了)。

```mermaid
flowchart LR
    subgraph Client
        VSCode["VSCode拡張<br/>(Device Code)"]
        Web["Web管理画面<br/>(Auth Code + PKCE)"]
    end

    RP["reverse-proxy<br/>(nginx)"]
    GW["gateway<br/>(JWT検証/ルーティング/レート制限/相関ID)"]
    KC["Keycloak<br/>(OIDC IdP) + PostgreSQL"]

    subgraph Services["ドメインサービス群(各サービス専用MySQLスキーマ)"]
        Identity["identity-service<br/>ユーザー・ロール・メンバー"]
        Project["project-service<br/>プロジェクト・サイト・SSH鍵"]
        Content["content-service<br/>投稿・カスタムタグ・描画"]
        Media["media-service<br/>画像生成・ダイアグラム"]
        AI["ai-service<br/>LLM生成・記事プラン"]
        Publishing["publishing-service<br/>公開・一括管理・プレビュー"]
        Analytics["analytics-service<br/>GA/AdSense"]
        Platform["platform-service<br/>システム設定・バックアップ"]
        LogW["log-writer<br/>監査/操作/エラーログ"]
    end

    Events[["RabbitMQ<br/>letsblog.events / letsblog.logs"]]

    subgraph External["外部連携"]
        WP[("WordPress サイト群")]
        ComfyUI["ComfyUI (GPU)"]
        PlantUML["PlantUML / drawio"]
        Penpot["Penpot"]
    end

    VSCode -- OIDCトークン --> RP
    Web -- OIDCトークン --> RP
    RP --> GW
    RP --> Web
    GW -- JWT検証 --> KC
    GW --> Identity
    GW --> Project
    GW --> Content
    GW --> Media
    GW --> AI
    GW --> Publishing
    GW --> Analytics
    GW --> Platform
    GW --> LogW
    Services -- 発行/購読 --> Events
    Events --> LogW
    Publishing -- "SSH + wp-cli" --> WP
    Media --> ComfyUI
    Media --> Penpot
    Content --> PlantUML
```

サービス間の同期呼び出しは Client Credentials Grant で相互認証する(図では省略。
サービス数が多く全組み合わせを描くと見づらいため)。詳細は
[docs/SYNC_SERVICE_CALLS.md](docs/SYNC_SERVICE_CALLS.md) を参照。

### サービス一覧

| サービス | 責務 | スキーマ |
|---|---|---|
| `gateway` | 単一入口。ルーティング・JWT検証・レート制限・相関ID付与・下流ヘルスの集約 | なし |
| `identity` | ユーザー・ロール・権限・プロジェクトメンバー・著者マッピング・初回セットアップ | `lbs_identity` |
| `project` | プロジェクト・サイト・SSH鍵ペア・組み込みタグのデザイン設定・GitHubトークン | `lbs_project` |
| `content` | 投稿本文・カスタムタグ・組み込みタグ展開・コンテンツキャッシュ・CSSセレクタ接頭辞 | `lbs_content` |
| `media` | 画像生成(ComfyUI/ChatGPT)・生成画像・ダイアグラム・画像設定 | `lbs_media` |
| `ai` | LLM生成(下書き/校正/タグ/セクション)・記事プラン・生成ジョブ・Brave Searchキー | `lbs_ai` |
| `publishing` | WordPressへの公開・削除・一括管理・環境間比較・記事プレビュー | `lbs_publishing` |
| `analytics` | Google Analytics / AdSense のレポートと資格情報 | `lbs_analytics` |
| `platform` | システム設定・バックアップ・ダッシュボード状態・VSCode拡張の配布 | `lbs_platform` |
| `log-writer` | 監査ログ・操作ログ・フロントエンドエラーログ(RabbitMQ経由で非同期に書き込む) | `lbs_log` |

各サービスは**自分のスキーマにしかアクセスしない**([ADR-0004](docs/adr/0004-schema-per-service.md))。
他サービスのデータが要る場合は `/api/internal/**` の内部ブリッジ経由で問い合わせる。

認証ゲート(有効な JWT が無ければ401)は gateway ではなく**各サービスの `SecurityConfig`** が担う
([ADR-0008](docs/adr/0008-auth-gate-in-each-service-security-config.md))。gateway を迂回した
直接アクセスでも守られる。エンドポイント単位の認可の網羅状況は
[docs/AUTHORIZATION_MATRIX.md](docs/AUTHORIZATION_MATRIX.md) にある。

移行の意思決定は [docs/adr/](docs/adr/README.md)、実行の記録は Epic #551 とその子Issueが一次情報。

### ディレクトリ構成

直下は「何であるか」で分けてある(#963)。

```
apps/        利用者が直接触るアプリケーション
  web/            Next.js のフロントエンド(BFF を兼ねる)
  extension/      VSCode 拡張
  mcp-server/     MCP サーバー
  penpot-plugin/  Penpot プラグイン
services/    バックエンドの9サービス(Spring Boot)
packages/    サービス・アプリ間で共有するライブラリ
  lbs-common/     Java 共通ライブラリ(Gradle プロジェクト :packages:lbs-common)
  api-client/     OpenAPI から生成する TypeScript クライアント
infra/       ミドルウェアの設定。それ自体はビルド対象ではない
  nginx/ mysql/ keycloak/ wordpress/ e2e-stubs/
config/      ビルド・生成ツールの設定(checkstyle / dependency-check / orval)
scripts/     運用・開発用スクリプト
openapi/     各サービスから取得した OpenAPI spec(生成の中間物)
docs/        設計・運用ドキュメントと ADR
```

`docker-compose*.yml` は直下に残している。移動すると `build.context` やボリュームバインドの
相対パスが全て変わるうえ、`-f` か `COMPOSE_FILE` を渡さないと `docker compose` を素で
叩けなくなるため。`gradlew` / `gradle/` / `settings.gradle` / `build.gradle` は Gradle の規約により直下。

## 品質の担保

**このリポジトリは CI を持たない。** GitHub Actions は移行前から意図的に無効化されており、
GitLab へ移行した 2026-09-03 に「稼働させない」と決定した（Runner を運用しないため）。
GitHub Actions のワークフロー定義も同時に削除した。動く見込みの無い定義とバッジを残さないためである（#1027）。

推測させないために書いておくと、代わりに品質を担保しているのは次の3つで、いずれも
**コミットとマージの経路上で機械的に強制される**。

| 仕組み | 実体 | 強制するもの |
| --- | --- | --- |
| git フック | `scripts/git-hooks/pre-commit` | テストとプロダクションの混在コミット禁止、テスト無効化の禁止、テストファースト |
| Claude Code フック | `.claude/hooks/guard.py` | 上記に加え、読み取り専用ステージ、マージ方式、ラベル整合性 |
| カバレッジゲート | `scripts/check-changed-coverage.py` | 変更コードの C1/C2 が 90% 未満なら Merge Request を作れない |

git フックは `bash scripts/setup-git-hooks.sh` で有効になる（`core.hooksPath` を
`scripts/git-hooks` に束縛する。冪等なので何度実行してもよい）。有効になれば
**エージェントか人間かを問わず、あらゆるコミッタに適用される。**

`core.hooksPath` は git の設定であってリポジトリの内容ではないため、クローンにも
チェックアウトにも含まれない。束縛が外れていても症状は「何も起きない」ことだけで、
現に #976 で追加されて以降 #1039 まで一度も動いていなかった。だから状態を断言せず、
点検できるようにしてある。

```bash
bash scripts/setup-git-hooks.sh --check   # 束縛されているか（外れていれば非0で終了）
```

`scripts/test_git_hooks_binding.py` が下記の単体テストの中で同じことを検査するので、
外れたまま気づかないことは無い。

テストは手元で回す。

```bash
./gradlew test                                                    # バックエンド
cd apps/web && npm run test:coverage && npm run lint              # フロントエンド
npm run test:at                                                   # 受け入れテスト
python3 -m unittest discover -s .claude/hooks -t .claude/hooks -p 'test_*.py'
python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
```

JVM サービスのコードや Dockerfile を変えたら、Merge Request を開く前に実行イメージ(JRE)の
起動スモークテストを回す。JDK にしか無い API への依存は単体テストで検出できない(#1101)。
所要時間と詳細は [docs/JRE_IMAGE_SMOKE_TEST.md](docs/JRE_IMAGE_SMOKE_TEST.md)。

```bash
python3 scripts/smoke_jre_image.py --changed    # 変更したサービスだけ
```

受け入れテスト(`npm run test:at`)は、初回だけ Playwright のブラウザの導入が要る。
**2段あり、下の段は root 権限が要るので自動実行しない**(#1045)。

```bash
cd apps/web
npm run playwright:install     # 1. ブラウザ本体。root 不要
sudo npx playwright install-deps   # 2. ブラウザが依存する OS 共有ライブラリ。root が要る
```

どちらが足りないかは、`global-setup` の前提確認が導入コマンド付きで示す。
手順の詳細と導入方針の理由は [docs/e2e-testing.md §3.3](docs/e2e-testing.md)。

依存の更新は自動化していない（Dependabot は GitLab では動かない）。定期的に手元で確認する。

```bash
cd apps/web && npm audit --audit-level=moderate
cd apps/extension && npm audit --audit-level=moderate
./gradlew dependencyCheckAnalyze
```

規約そのものは [`.claude/CLAUDE.md`](.claude/CLAUDE.md)、ワークフロー環境の構築は
[`docs/GITLAB_WORKFLOW_SETUP.md`](docs/GITLAB_WORKFLOW_SETUP.md) を参照。

## 目次

- [アーキテクチャ](#アーキテクチャ)
- [ディレクトリ構成](#ディレクトリ構成)
- [ハードウェア要件](#ハードウェア要件)
- [前提ソフトウェア要件](#前提ソフトウェア要件)
- [前提ソフトウェアのインストール](#前提ソフトウェアのインストール)
- [アプリケーションの起動(Docker)](#アプリケーションの起動docker)
- [VSCode拡張機能の入手とインストール](#vscode拡張機能の入手とインストール)
- [関連ドキュメント](#関連ドキュメント)

## ハードウェア要件

| 項目 | 要件 |
|---|---|
| GPU | **NVIDIA GPU(VRAM 16GB以上)必須**。ComfyUI(画像生成)がGPUを使用するため |
| GPUドライバ | NVIDIA GPUドライバ + NVIDIA Container Toolkit(Dockerコンテナへのパススルー用) |
| メモリ | **24GB以上を推奨**(全29コンテナ起動時の実測で約15GiB。ホストOS分の余裕や、ComfyUIでの画像生成時のスパイクを考慮するとこの程度が安全。以前は19コンテナ/約10.3GiBだったが、Epic #551 のマイクロサービス分割でサービス数が増えた。詳細は[docs/DOCKER_COMPOSE_ARCHITECTURE.md](docs/DOCKER_COMPOSE_ARCHITECTURE.md#リソース実測)参照) |
| ディスク | Dockerイメージに加え、ComfyUIのモデルファイルで数GB〜十数GB程度の空き容量が必要 |
| ネットワーク | ホストの80番・443番ポートが空いていること(リバースプロキシが使用) |

GPUを持たないホストでも、ComfyUIをCPUで実行できる(issue #1395)。`.env` で
`COMFYUI_IMAGE=yanwk/comfyui-boot:cpu` とし、`COMPOSE_PROFILES=cpu` を有効にすると、
`docker compose up -d` のまま別サービス `comfyui-cpu`(`--cpu --force-fp32`、nvidia予約なし)が
起動する。`COMFYUI_BASE_URL` は変更不要で、`comfyui`(GPU)とは同時に起動しない。
ただしCPU実行は**実用的な速度に達しない**(画像生成は遅いが完了することが到達点)。
AI機能を実用速度で使うにはGPU(上記要件)を推奨する。

**`comfyui` はGPUオプトインのcompose profile(`gpu`)を持つ(issue #1066)。** GPUの無い
ホストで `docker compose up -d`(サービス無指定)を実行しても、`comfyui` はコンテナ自体が
作られないため起動は中断しない(画像生成機能だけが使えない状態になる)。GPUを持つホストは
`.env` の `COMPOSE_PROFILES=gpu` の行を有効にする(コメントアウトを外す)ことで、従来どおり
`docker compose up -d` のまま `comfyui` が起動する。

## 前提ソフトウェア要件

| ソフトウェア | 用途 |
|---|---|
| Git | リポジトリの取得 |
| Docker Engine + Docker Compose(v2系) | 全サービスのコンテナ起動 |
| NVIDIA Container Toolkit | ComfyUIコンテナへのGPUパススルー |
| openssl | リバースプロキシ用の自己署名TLS証明書生成(Linux/macOSは標準搭載) |
| Visual Studio Code | VSCode拡張機能(執筆・投稿)の利用 |

## 前提ソフトウェアのインストール

### Git

```bash
# Ubuntu/Debian
sudo apt-get update && sudo apt-get install -y git
```

macOS/Windowsの場合は [git-scm.com](https://git-scm.com/downloads) からインストーラーを取得する。

### Docker Engine + Docker Compose

公式手順: https://docs.docker.com/engine/install/

```bash
# Ubuntu/Debianの例(公式インストールスクリプト)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # sudoなしでdockerコマンドを使う場合(再ログインが必要)
```

macOS/WindowsはDocker Desktopをインストールする(Compose v2が同梱される)。

インストール後、以下でバージョンを確認する。

```bash
docker --version
docker compose version   # Compose v2系であることを確認
```

### NVIDIA GPUドライバ + NVIDIA Container Toolkit

GPUドライバが未導入の場合は先にNVIDIA公式のGPUドライバをインストールし、`nvidia-smi`が
動作することを確認する。

NVIDIA Container Toolkitの公式手順: https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html

```bash
# Ubuntu/Debianの例
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
curl -s -L https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | \
  sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | \
  sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
```

### Visual Studio Code

公式手順: https://code.visualstudio.com/download

```bash
# Ubuntu/Debianの例(snap)
sudo snap install code --classic
```

## アプリケーションの起動(Docker)

前提ソフトが何も入っていない Ubuntu/Debian 系の機械であれば、`setup.sh` を1回実行すれば
そのまま `https://localhost` にアクセスできる状態になる(前提ソフト導入・`.env` 生成・
TLS証明書生成・全サービス起動・healthy確認まで一括で行う)。

```bash
git clone -b develop <このリポジトリのURL>
cd lets_blog_server
./setup.sh
```

既定では `develop` ブランチ上での実行のみを許可する。他ブランチで使う場合は
`./setup.sh --branch <name>` または `./setup.sh --main` を指定する。

`.env` は既に存在する場合は上書きしない。存在しない場合は `.env.example` を土台に、
値が何でもよい内部の秘密値(DBパスワード・`NEXTAUTH_SECRET` 等)は自動生成して書き込み、
利用者自身が用意する外部の値(`LLM_API_KEY`・`BRAVE_SEARCH_API_KEY`・`MAIL_PASSWORD` 等)は
生成せず空のまま残して実行の最後に一覧表示する。それらの機能を使う場合は `.env` を手動で
編集する。

### `setup.sh` が内部で行っていること(手動でも同じ手順で進められる)

`setup.sh` は以下を順に、冪等に実行しているだけである。前提ソフトが既に導入済みの環境や、
Ubuntu/Debian 以外の環境では、同じ手順を手動でなぞればよい。

```bash
# 0. git フックを有効にする（コミット時の規約検査。このリポジトリにコミットするなら必須。
#    setup.shはこのリポジトリへのコミットを前提にしないため呼ばない)
bash scripts/setup-git-hooks.sh

# 1. 前提ソフトの導入(git/curl/openssl、Docker Engine + Compose v2、dockerグループ、
#    nvidia-smiが通る場合のみNVIDIA Container Toolkit。導入コマンドは次節を参照)

# 2. 環境変数を設定
cp .env.example .env
vi .env   # パスワード・APIキー・暗号化キー・NEXTAUTH_SECRET等を変更
bash scripts/check-env.sh   # .env が .env.example の全項目を満たしているか確認

# 3. リバースプロキシ用の自己署名証明書を生成
bash scripts/generate-certs.sh

# 4. Docker Composeで全サービスを起動
docker compose up -d --build

# 5. 全サービスがhealthyになるまで待機(setup.shはこれで起動完了を判定する)
bash scripts/wait-for-stack-healthy.sh --all

# 6. ブラウザで https://localhost にアクセス(自己署名証明書の警告は例外承認する)
```

`web`(Next.js)はコンテナ起動時のエントリポイントが `apps/web/node_modules` の有無を確認し、
無ければコンテナ内で自動的に `npm ci` する(クローン直後はホストにこのディレクトリが無いため)。
初回起動時はこのインストール分だけ `web` の起動が遅れる。

初回アクセス時、まだユーザーが1人も存在しない場合は `/setup` にリダイレクトされ、
セルフサインアップで最初のユーザー(管理者権限)を作成できる。

環境変数の各項目の詳細、ComfyUIチェックポイントの配置、
トラブルシューティング等は [docs/setup.md](docs/setup.md) を参照。

## アップデート

導入済みの環境を最新にするには、リポジトリ直下で `./update.sh` を実行する。
最新の取り込み(fast-forwardのみ)・全サービスの再ビルド・再起動・全サービスがhealthyになるまでの
待機を1コマンドで行う。これがアップデートの正の手順である。

```bash
./update.sh                  # develop の最新を取り込む(既定)
./update.sh --main           # main を更新元にする
./update.sh --branch <name>  # 任意のブランチを更新元にする
```

- **既定の更新元は `develop`**。現状、修正は `develop` に先に入るため、通常はフラグなしでよい。
  `--main` は配布用の安定版ブランチとして運用される `main` を使いたい場合に指定する。
  どのブランチを更新元にしたかは、実行の最初に必ず表示される。
- 現在のブランチが更新元と異なる場合、または未コミットの変更がある場合は、何も変更せずに中断する
  (stash や上書きはしない)。更新元と同じブランチへ切り替えてから再実行する。
- データボリューム(ユーザー・プロジェクト・投稿など)には触れない。何度実行しても壊れない。
- healthyにならないサービスがあれば、そのサービス名を示して終了コード非0で終わる。

## VSCode拡張機能の入手とインストール

VSCode拡張機能(`.vsix`)は事前ビルド済みファイルとしては配布されておらず、起動中のサーバーから
オンデマンドビルドしてダウンロードする。

1. `https://localhost/` にログインし、システム画面(`/system`)を開く
2. 「拡張機能をダウンロード (.vsix)」ボタンをクリックし、`letsblog-vscode-<バージョン>.vsix` を保存する

ダウンロードした `.vsix` をVSCodeにインストールする方法は2通り。

**VSCode UIから**

コマンドパレット(`Ctrl+Shift+P` / macOSは`Cmd+Shift+P`)で
`Extensions: Install from VSIX...` を実行し、ダウンロードしたファイルを選択する。

**コマンドラインから**

```bash
code --install-extension letsblog-vscode-<バージョン>.vsix
```

インストール後、コマンドパレットから `Let's Blog: Login` を実行し、サーバーのアカウント
(メールアドレス・パスワード)でログインするとAPIキーが自動的に設定される。拡張の設定
`letsBlog.serverUrl`(既定値: `https://localhost`)がサーバーのURLと一致していることを確認する。

自己署名証明書を使用しているため、VSCodeを起動するシェルで
`NODE_EXTRA_CA_CERTS=/path/to/certs/localhost.crt` を設定してから起動するか、OS/ブラウザの
証明書ストアに `certs/localhost.crt` を信頼済み証明書として登録する必要がある。詳細は
[docs/setup.md](docs/setup.md) の「VSCode拡張の設定」を参照。

拡張のTLS証明書検証は既定で有効(`letsBlog.allowInsecureTls: false`)。上記の証明書登録が
行えない場合に限り、`letsBlog.allowInsecureTls: true` で検証をスキップできるが、
中間者攻撃を検出できなくなるため信頼できるネットワーク上のローカル環境でのみ使用すること。
拡張の設計は [apps/extension/ARCHITECTURE.md](apps/extension/ARCHITECTURE.md)、API仕様は
[apps/extension/API_REFERENCE.md](apps/extension/API_REFERENCE.md)、セキュリティ仕様は
[apps/extension/SECURITY.md](apps/extension/SECURITY.md)、不具合調査は
[apps/extension/TROUBLESHOOTING.md](apps/extension/TROUBLESHOOTING.md) を参照。

## API ドキュメント

REST APIは以下のエンドポイントで公開しています:

- **Swagger UI (対話的ドキュメント)**: `https://localhost/api/swagger-ui.html`
- **OpenAPI JSON スペック**: `https://localhost/v3/api-docs`

APIの認証にはKeycloakが発行するアクセストークンを`Authorization: Bearer`ヘッダーで使用します(issue #566で旧ヘッダベースのAPIキー認証から移行済み)。

## Code Quality & Coverage Details

我々は継続的に全コンポーネント(API・Frontend・Extension)のコード品質を監視しています:

- **API Tests**: JaCoCo経由でコード品質を測定(Java/Spring Boot)
- **Frontend Tests**: Jest経由でユニットテストカバレッジを測定(TypeScript/React)
- **Extension Build**: TypeScript型チェックとコンパイル検証
- **詳細**: [COVERAGE_TARGETS.md](docs/COVERAGE_TARGETS.md) を参照

## 関連ドキュメント

### ユーザーガイド

- [**Getting Started Guide**](docs/GETTING_STARTED.md) — セットアップの詳細ガイド（スクリーンショット説明付き）
- [**Features and Usage Guide**](docs/FEATURES_AND_USAGE.md) — 主要機能と使用方法
- [**Article Authoring Best Practices**](docs/ARTICLE_AUTHORING_BEST_PRACTICES.md) — 記事作成のベストプラクティス
- [**Video Tutorials Guide**](docs/VIDEO_TUTORIALS_GUIDE.md) — ビデオチュートリアルの構成と活用方法
- [**Comprehensive Troubleshooting Guide**](docs/COMPREHENSIVE_TROUBLESHOOTING.md) — 問題解決ガイド

### 技術ドキュメント

- [docs/setup.md](docs/setup.md) — 環境変数・起動手順・トラブルシューティングの詳細
- [docs/ACCEPTANCE_CRITERIA.md](docs/ACCEPTANCE_CRITERIA.md) — 受け入れ基準カタログ(全機能の一覧と検証状況)
- [docs/ACCEPTANCE_TESTING.md](docs/ACCEPTANCE_TESTING.md) — 受け入れテスト(Gherkin)の書き方・タグ規約・実行方法
- [docs/e2e-testing.md](docs/e2e-testing.md) — E2E/受け入れテストの実行環境と前提
- [docs/DOCKER_COMPOSE_ARCHITECTURE.md](docs/DOCKER_COMPOSE_ARCHITECTURE.md) — コンテナ構成・ポート割当・起動順序
- [docs/adr/](docs/adr/README.md) — アーキテクチャ意思決定記録 (ADR)
- [docs/SERVICE_SCHEMA_MIGRATION.md](docs/SERVICE_SCHEMA_MIGRATION.md) — サービス別MySQLスキーマ分離とデータ移行ガイド
- [docs/AI_SERVICE_PROVIDER_RESEARCH.md](docs/AI_SERVICE_PROVIDER_RESEARCH.md) — 外部AIサービス移行の調査・比較
- [docs/RELEASE_NOTES.md](docs/RELEASE_NOTES.md) — リリースノート

## ライセンス

このプロジェクトはMITライセンスの下で公開されています。詳細は [LICENSE](LICENSE) ファイルを参照してください。
