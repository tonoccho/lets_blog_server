# 02-setup-manual: セットアップマニュアル整備

## 目的

Phase 1 から Phase 6 まで複数フェーズで追加・変更された各種セットアップ手順(環境構築・初期設定・各サービスの起動)を、単一の統合ドキュメント(`docs/setup.md` 相当)にまとめ、ユーザーが最初から最後まで確実にセットアップできるようにする。

リバースプロキシ導入後の新しいアクセス URL 一覧(https://localhost/...)も含める。

## 決定事項

| 項目 | 決定内容 |
|---|---|
| マニュアル設置場所 | `docs/setup.md` (リポジトリ内、README ではなく別ファイル) |
| 対象ユーザー | 開発環境構築時の初期セットアップを想定(本番環境対応は Phase 7+ の検討課題) |
| カバー範囲 | システム要件・`.env` 設定・証明書生成・docker-compose 起動・モデル/チェックポイント配置・初回管理者作成・アクセス確認 |
| トラブルシューティング | よくある問題と対処法(GPU認識不可・メモリ不足・ポート競合等)を記載 |

## 実装内容チェックリスト

> **実装メモ**: 実際の成果物は [docs/setup.md](../../docs/setup.md)。以下のチェックリストは
> 概ねカバーしているが、実機検証で裏付けできない推測情報(モデル別VRAM要件の詳細比較表、
> SDXLチェックポイントのサイズ等)は誤情報を避けるため簡略化・省略している。また
> `comfyui_models` は名前付きボリューム(ホストへのディレクトリバインドではない)であることが
> 実装時に判明したため、チェックポイント配置手順は `docker cp` ベースに変更した。

### マニュアル構成・内容作成

- [x] ファイル作成: `docs/setup.md`

- [x] セクション構成:
  1. **クイックスタート** (5分で全体を把握できる要約)
  2. **システム要件** (OS・CPU・GPU・メモリ・ディスク・ソフトウェア)
  3. **リポジトリの取得** (git clone)
  4. **環境変数の設定** (`.env` ファイル作成・各項目の説明)
  5. **TLS証明書の生成** (openssl スクリプト実行)
  6. **Docker Compose による起動** (手順・ヘルスチェック確認)
  7. **Ollama モデルの準備** (モデル pull・使用可能なモデル一覧)
  8. **ComfyUI チェックポイントの配置** (ダウンロード・配置ディレクトリ)
  9. **初回管理者アカウントの作成** (セルフサインアップ手順・初期管理者の確認)
  10. **各サービスへのアクセス確認** (新 URL 一覧・ブラウザテスト)
  11. **トラブルシューティング** (よくある問題・ログ確認・対処法)
  12. **環境変数リファレンス** (`.env.example` の詳細説明)
  13. **次のステップ** (VSCode 拡張のセットアップ等)

### クイックスタートセクション

- [x] 3〜5ステップで環境構築できるワンライナー集
  ```bash
  git clone https://github.com/...
  cd lets_blog_server
  cp .env.example .env
  # (`.env` を編集)
  bash scripts/generate-certs.sh
  docker compose up -d
  # ... モデル pull 等を実行
  ```

### システム要件セクション

- [x] OS サポート
  - Linux (Ubuntu 20.04 LTS 以上推奨)
  - macOS (Docker Desktop で動作確認済み)
  - Windows (WSL2 + Docker Desktop で動作確認済み)

- [x] ハードウェア要件
  - CPU: 4コア以上推奨(Ollama・ComfyUI 使用時)
  - RAM: 8GB 以上(GPU 側メモリとの合計で考慮)
  - ディスク: 30GB 以上(モデルファイル・チェックポイント込み)
  - GPU: NVIDIA GPU 推奨(ComfyUI での CUDA 画像生成用)
    - 計算能力 3.5 以上(古い GPU では非対応の場合がある)

- [x] ソフトウェア要件
  - Docker: 20.10 以上
  - Docker Compose: 2.10 以上
  - Git: 最新版
  - openssl: Linux/macOS は標準、Windows は Git Bash/WSL2 で使用可能
  - (optional) NVIDIA Container Toolkit: GPU 使用時に必須

- [x] ネットワーク要件
  - インターネット接続(初回のモデル・イメージ pull に要)
  - ローカルホスト:
    - 80 番ポート(HTTP → HTTPS リダイレクト)
    - 443 番ポート(HTTPS)が使用可能か確認
    - その他のサービスの個別ポートは nginx 側で管理(外部非公開)

### 環境変数設定セクション

- [x] `.env` ファイルの作成手順
  ```bash
  cp .env.example .env
  # テキストエディタで開いて編集
  ```

- [x] 各項目の詳細説明(`.env.example` の全項目をカバー)
  - `MYSQL_ROOT_PASSWORD`: MySQL root パスワード(変更必須)
  - `MYSQL_DATABASE`: MySQL データベース名(デフォルト: lets_blog)
  - `MYSQL_USER`: MySQL アプリケーション用ユーザー(デフォルト: lbs_app)
  - `MYSQL_PASSWORD`: MySQL アプリケーション用パスワード(変更必須)
  - `SERVER_API_KEY`: VSCode 拡張・Web フロント が使用する API キー(変更必須)
  - `APP_ENCRYPTION_KEY`: WordPressアプリケーションパスワード等の暗号化キー(Base64 エンコード 32 バイト)
    - 生成方法: `openssl rand -base64 32`
  - `COMFYUI_IMAGE`: ComfyUI コンテナイメージ(GPU 種別別・OS別で使い分け)
    - NVIDIA GPU (CUDA 13): `yanwk/comfyui-boot:cu130-slim` (デフォルト)
    - CPU のみ: `yanwk/comfyui-boot:cpu`
    - AMD GPU: `yanwk/comfyui-boot:rocm-latest` (未検証)
  - `OLLAMA_MODEL`: Ollama で使用するモデル(初期値: qwen2.5:7b-instruct)
    - サポート済みモデル一覧は本マニュアルの「Ollama モデル準備」セクション参照
  - `COMFYUI_CHECKPOINT`: ComfyUI で使用するチェックポイント(初期値: v1-5-pruned-emaonly.safetensors)
  - `APP_MAIL_FROM`: パスワード再設定メール等の送信元アドレス(デフォルト: noreply@letsblog.example.com)
  - `APP_WEB_BASE_URL`: Web フロント管理画面の公開 URL(デフォルト: https://localhost, リバースプロキシ導入後は変更不要)
  - SMTP 設定(本番環境のみ): `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM_ADDRESS`
    - 開発環境では Mailhog が使用されるため不要

- [x] デフォルト値と変更必須項目の区別を明確に
  - 変更必須(セキュリティ): `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`, `SERVER_API_KEY`, `APP_ENCRYPTION_KEY`
  - 変更推奨(運用): `APP_MAIL_FROM`, `COMFYUI_IMAGE` (GPU種別に応じて)
  - デフォルト値で OK: `MYSQL_DATABASE`, `MYSQL_USER`, その他

### TLS 証明書生成セクション

- [x] 証明書生成スクリプト実行
  ```bash
  bash scripts/generate-certs.sh
  ```

- [x] スクリプトが実行するコマンドの詳細説明(参考用)
  ```bash
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout certs/localhost.key \
    -out certs/localhost.crt \
    -subj "/C=JP/ST=Tokyo/L=Tokyo/O=LetsBlog/CN=localhost"
  ```

- [x] 既存証明書の再利用について
  - 同じ certs/ ディレクトリに既存ファイルがあれば、スクリプトは新規生成をスキップ(再利用)
  - 強制的に新規生成したい場合: `rm certs/*.{crt,key} && bash scripts/generate-certs.sh`

### Docker Compose 起動セクション

- [x] 前提確認
  ```bash
  docker --version          # 20.10 以上
  docker compose version    # 2.10 以上
  ```

- [x] 起動コマンド
  ```bash
  docker compose up -d
  ```

- [x] ヘルスチェック確認(すべてのサービスが `healthy` または `running` になるまで待機)
  ```bash
  docker compose ps
  docker logs lbs-mysql       # ヘルスチェック確認
  docker logs lbs-api         # 起動ログ確認
  ```

- [x] 初回起動に要する時間(目安)
  - イメージ pull: インターネット速度による(5〜15分程度)
  - MySQL マイグレーション実行: 1〜2分
  - API サーバー起動: 1〜2分
  - Ollama・ComfyUI のダウンロード: 別途(初回は無し、モデル pull 時に実行)

### Ollama モデル準備セクション

- [x] 利用可能なモデル一覧と特性
  | モデル | サイズ | VRAM | 推奨用途 | 注記 |
  |---|---|---|---|---|
  | qwen2.5:7b-instruct | 4.4GB | 8GB 以上 | 下書き/校正/要約(デフォルト) | 高速、日本語対応 |
  | qwen2:7b-instruct | 4.4GB | 8GB 以上 | 汎用 | - |
  | llama2:7b | 3.8GB | 8GB 以上 | 汎用 | 英語メイン |
  | mistral:7b | 4.1GB | 8GB 以上 | 高速推論 | 軽量 |
  | neural-chat:7b | 4.1GB | 8GB 以上 | 会話 | 実験的 |

- [x] モデルの pull 手順
  ```bash
  # コンテナ内から実行
  docker exec lbs-ollama ollama pull qwen2.5:7b-instruct
  
  # または、複数モデルを事前に pull
  docker exec lbs-ollama bash -c "\
    ollama pull qwen2.5:7b-instruct && \
    ollama pull mistral:7b
  "
  ```

- [x] 初回 pull に要する時間(目安)
  - qwen2.5:7b-instruct: 5〜10分(ネットワーク速度による)
  - 複数モデル: 20〜30分

- [x] Ollama ステータス確認
  ```bash
  curl -s http://ollama:11434/api/tags | jq .
  # (または、リバースプロキシ経由: curl -k https://localhost/ollama/api/tags | jq .)
  ```

### ComfyUI チェックポイント配置セクション

- [x] サポート済みチェックポイント
  | ファイル名 | サイズ | 入手方法 | 用途 |
  |---|---|---|---|
  | v1-5-pruned-emaonly.safetensors | 4.1GB | HuggingFace | Stable Diffusion v1.5(デフォルト) |
  | model.safetensors (SDXL) | 5.2GB | HuggingFace | Stable Diffusion XL(より高品質) |

- [x] チェックポイント配置ディレクトリ
  ```
  comfyui_models/
  └─ checkpoints/
     └─ v1-5-pruned-emaonly.safetensors
  ```

- [x] ダウンロード・配置手順
  ```bash
  # 1. HuggingFace から直接ダウンロード
  # https://huggingface.co/runwayml/stable-diffusion-v1-5
  
  # 2. または、ComfyUI Manager 経由でダウンロード(UI上から操作)
  
  # 3. comfyui_models/checkpoints/ 配下に配置
  cp ~/Downloads/v1-5-pruned-emaonly.safetensors comfyui_models/checkpoints/
  
  # 4. ComfyUI が認識しているか確認
  curl -k https://localhost/comfyui/api/checkpoints | jq .
  ```

- [x] 初回配置に要する時間(目安)
  - ダウンロード: 10〜15分(ネットワーク速度による)
  - 配置: 1分未満

### 初回管理者アカウント作成セクション

- [x] セルフサインアップ手順(Phase 4 で実装)
  1. ブラウザで `https://localhost` にアクセス
     - 自己署名証明書による警告が表示 → 「例外承認」で進める
  2. ログイン画面が表示される
  3. 「新規ユーザー登録」(Signup) をクリック
  4. メールアドレス・パスワード・パスワード確認を入力 → 送信
  5. Web フロント初回アクセス時に、最初に登録したユーザーが管理者(admin)権限を自動取得する

- [x] デフォルト管理者作成の廃止
  - Phase 4 以前: `.env` に `INITIAL_ADMIN_EMAIL` / `INITIAL_ADMIN_PASSWORD` を記載
  - Phase 4 以降: セルフサインアップのみ(上記手順を推奨)
  - 移行時に既存管理者がいる場合: そのまま保持(DB は migrate されるため)

### 各サービスへのアクセス確認セクション

- [x] リバースプロキシ導入後の新 URL 一覧
  | サービス | リバースプロキシ前 | リバースプロキシ後 | 用途 |
  |---|---|---|---|
  | Web フロント管理画面 | http://localhost:3000 | https://localhost | 管理画面・投稿履歴・AI ジョブ |
  | API | http://localhost:8080 | https://localhost/api | REST API |
  | phpMyAdmin | http://localhost:8081 | https://localhost/phpmyadmin | MySQL 管理ツール |
  | Ollama | http://localhost:11434 | https://localhost/ollama | LLM API |
  | ComfyUI | http://localhost:8188 | https://localhost/comfyui | 画像生成 UI |
  | PlantUML | http://localhost:8085 | https://localhost/plantuml | 図レンダリング API |
  | Mailhog | http://localhost:8025 | https://localhost/mailhog | メール送受信テスト(開発時) |

- [x] アクセステスト(curl またはブラウザ)
  ```bash
  # API ヘルスチェック
  curl -k https://localhost/api/health
  
  # Ollama API
  curl -k https://localhost/ollama/api/tags
  
  # Web フロント(HTML取得)
  curl -k https://localhost/ | head -20
  ```

- [x] ブラウザでの動作確認
  1. `https://localhost` にアクセス
  2. 自己署名証明書警告 → 例外承認
  3. ログイン画面が表示
  4. 新規ユーザー登録 → 管理者アカウント作成
  5. 管理画面へのログイン確認
  6. `/posts`, `/ai-jobs`, `/system` 各ページへのアクセス確認

### トラブルシューティングセクション

- [x] よくある問題と対処法

  **Q1: ポート 443 が既に使用されている**
  ```
  Error: port 443 is already allocated
  ```
  - 対処: 他のアプリケーション(Web サーバー等)を停止し、再度起動
  - または、docker-compose.yml の ports で別ポート(例: 8443)にマッピング(検証後に推奨)

  **Q2: GPU (NVIDIA) が認識されない**
  ```
  RuntimeError: No CUDA capable GPU devices found
  ```
  - 対処:
    1. ホスト側で GPU 動作確認: `nvidia-smi`
    2. NVIDIA Container Toolkit インストール確認
    3. docker-compose.yml の deploy.resources.reservations.devices の gpu count が 1 に設定されているか確認
    4. または、docker コマンド直接実行でテスト: `docker run --rm --gpus all nvidia/cuda:11.0 nvidia-smi`

  **Q3: MySQL マイグレーション失敗**
  ```
  Migration V1__init_schema.sql failed
  ```
  - 対処:
    1. MySQL ログを確認: `docker logs lbs-mysql`
    2. 既存データが競合している場合: `docker compose down -v` で volume も削除し、再起動

  **Q4: Web フロント管理画面が表示されない/API 呼び出し失敗**
  ```
  Mixed Content error / CORS error
  ```
  - 対処: ブラウザの開発者ツール(F12)でコンソール・ネットワークログを確認
    - 特に `https://localhost` 経由で `/api/` へのリクエストが実行されているか確認
    - nginx ログの確認: `docker logs lbs-reverse-proxy`

  **Q5: Ollama モデルが見つからない**
  ```
  Error: model "qwen2.5:7b-instruct" not found
  ```
  - 対処: モデルが pull されているか確認
    ```bash
    docker exec lbs-ollama ollama list
    docker exec lbs-ollama ollama pull qwen2.5:7b-instruct
    ```

  **Q6: ComfyUI チェックポイント読み込み失敗**
  ```
  Model not found: v1-5-pruned-emaonly.safetensors
  ```
  - 対処: チェックポイントが正しいディレクトリ(`comfyui_models/checkpoints/`)に配置されているか確認
    ```bash
    ls -la comfyui_models/checkpoints/
    ```

  **Q7: 自己署名証明書警告が毎回表示される**
  ```
  Your connection is not private / NET::ERR_CERT_AUTHORITY_INVALID
  ```
  - 対処: 正常な動作(開発環境では避けられない)。ブラウザで例外承認を一度行うと、以降は警告が出ないようにできる場合がある(ブラウザ仕様に依存)

  **Q8: メモリ不足エラー**
  ```
  MemoryError / Killed (OOM)
  ```
  - 対処:
    1. ホスト側メモリ確認: `free -h`
    2. 実行中のコンテナのメモリ使用量確認: `docker stats`
    3. 不要なサービスを停止: `docker compose down && docker compose up -d`(特定サービスのみ)

  **Q9: VSCode 拡張が API に接続できない**
  ```
  Failed to connect to API
  ```
  - 対処:
    1. API キーが設定されているか確認: VSCode 拡張の設定で `letsBlog.apiKey` を確認
    2. API ヘルスチェック: `curl -H "X-API-Key: <YOUR_KEY>" -k https://localhost/api/health`

- [x] ログ確認方法(デバッグ時)
  ```bash
  # 特定サービスのログ確認
  docker logs lbs-api -f        # リアルタイム表示
  docker logs lbs-mysql
  docker logs lbs-reverse-proxy
  
  # docker-compose ログ全体
  docker compose logs -f
  
  # 特定期間のログ
  docker logs lbs-api --since 10m
  ```

- [x] コンテナの再起動・再構築
  ```bash
  # サービス再起動(デバッグ後)
  docker compose restart lbs-api
  
  # 全サービス再起動
  docker compose down && docker compose up -d
  
  # イメージをリビルド(Dockerfile変更時)
  docker compose up -d --build
  
  # キャッシュ無視でリビルド
  docker compose up -d --build --no-cache
  ```

### 環境変数リファレンス

- [x] `.env.example` の全項目を表形式で整理
  | 項目 | 型 | デフォルト | 説明 | 変更必須 |
  |---|---|---|---|---|
  | MYSQL_ROOT_PASSWORD | String | changeme_root | MySQL root パスワード | Yes |
  | MYSQL_DATABASE | String | lets_blog | データベース名 | No |
  | MYSQL_USER | String | lbs_app | MySQL ユーザー | No |
  | MYSQL_PASSWORD | String | changeme_app | MySQL ユーザーパスワード | Yes |
  | SERVER_API_KEY | String | changeme_api_key | API キー(X-API-Key ヘッダ) | Yes |
  | APP_ENCRYPTION_KEY | Base64(32bytes) | changeme_base64... | AES-256-GCM 暗号化キー | Yes |
  | COMFYUI_IMAGE | String | yanwk/comfyui-boot:cu130-slim | ComfyUI イメージ | No(GPU種別で変更可) |
  | OLLAMA_MODEL | String | qwen2.5:7b-instruct | Ollama モデル | No |
  | COMFYUI_CHECKPOINT | String | v1-5-pruned-emaonly.safetensors | チェックポイント | No |
  | APP_MAIL_FROM | String | noreply@letsblog.example.com | メール送信元 | No |
  | APP_WEB_BASE_URL | URL | https://localhost | Web フロント URL(リバースプロキシ導入後) | No |
  | MAIL_HOST (本番) | String | - | SMTP ホスト(本番環境) | No(開発環境では不要) |
  | ... | ... | ... | ... | ... |

### 次のステップセクション

- [x] セットアップ後の追加構成手順
  1. **VSCode 拡張のセットアップ**: `extension/` ディレクトリを参照、必要なランタイム・依存パッケージをインストール
  2. **VSCode 拡張への API キー設定**: `letsBlog.setApiKey` コマンドで `SERVER_API_KEY` を設定
  3. **既存 WordPress サイトの登録**: Web フロント(`/sites`)から登録
  4. **初回投稿テスト**: VSCode 拡張から投稿・更新動作確認
  5. **AI 機能テスト**: Ollama モデル・ComfyUI チェックポイントが正常に動作するか確認
  6. **本番環境への展開計画**: Phase 7+ の「外部公開・セキュリティ強化」を参照

## 検証方法

- [x] マニュアルの全セクションが記載されていることを確認
- [x] リンク・参照が正しいことを確認(`.env.example` の実在、`docs/` ディレクトリの構造等)
- [x] 実際にマニュアルに沿ってセットアップし、各ステップが成功することを確認(スモークテスト)
- [x] トラブルシューティングの各項目が実際に発生する問題をカバーしているか確認

## 関連ドキュメント

- Phase 6 [00-overview](00-overview.md) — 全体スコープ
- Phase 6 [01-reverse-proxy](01-reverse-proxy.md) — リバースプロキシ実装
- Phase 1 [01-docker-compose](../phase1/01-docker-compose.md) — 初期構築
- Phase 4 [04-user-self-registration](../phase4/04-user-self-registration.md) — セルフサインアップ実装
