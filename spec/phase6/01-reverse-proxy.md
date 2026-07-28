# 01-reverse-proxy: リバースプロキシコンテナ追加・パスベースルーティング実装

## 目的

nginx リバースプロキシコンテナを docker-compose に追加し、すべてのサービスへのアクセスを `https://localhost` 経由に統一する。既存の各サービス個別ポート公開(8080, 8081 等)を廃止し、443(HTTPS) / 80(HTTP→HTTPS リダイレクト)のみを外部公開する。同時に ComfyUI・Ollama をサブパス配信対応させる。

## 決定事項

| 項目 | 決定内容 |
|---|---|
| リバースプロキシ実装 | nginx (Alpine ベース軽量イメージ推奨) |
| TLS証明書方式 | openssl による自己署名証明書(コンテナ起動時に自動生成) |
| 証明書再生成 | マウント済みボリューム `/etc/nginx/certs/` に既存ファイルがあれば再利用、なければ生成 |
| ポート公開 | `80:80` (HTTP), `443:443` (HTTPS) のみ。既存各サービスのポート公開は全削除 |
| パスマッピング | 下記参照(サブドメイン不使用、パスベースのみ) |
| プロキシ設定 | upstream ブロックで各サービスの内部 IP/ポート指定、proxy_pass で振り分け |

### パスマッピング表

| パス | バックエンドサービス | ポート | 用途 |
|---|---|---|---|
| `/` (最後の砦) | web (Next.js) | 3000 | Webフロント管理画面 |
| `/api/` | api (Spring Boot) | 8080 | REST API |
| `/phpmyadmin/` | phpmyadmin | 80 | MySQL 管理ツール |
| `/ollama/` | ollama | 11434 | Ollama LLM API |
| `/comfyui/` | comfyui | 8188 | ComfyUI 画像生成 UI |
| `/plantuml/` | plantuml | 8080 | PlantUML レンダリング |
| `/mailhog/` | mailhog | 8025 | メール送信テスト UI(開発時) |

## 実装内容チェックリスト

### docker-compose.yml 変更

- [x] `reverse-proxy` (nginx) サービスを最初(依存関係が無いため)に追加
  - イメージ: `nginx:alpine`
  - コンテナ名: `lbs-reverse-proxy`
  - ポート: `80:80`, `443:443` のみを外部公開
  - ネットワーク: `lbs-net` に接続
  - ボリューム:
    - `./nginx.conf` → `/etc/nginx/nginx.conf:ro` (読み込み専用)
    - `./nginx/conf.d/` → `/etc/nginx/conf.d/:ro` (各種ルーティング設定)
    - `./certs/` → `/etc/nginx/certs/:rw` (TLS 証明書・秘密鍵)
  - healthcheck: `curl -k https://localhost/` で 200 返却確認
  - depends_on: なし(nginx は各サービス起動を待つ必要がないため「-1」戦略で OK)
  - restart: `unless-stopped`

- [x] 既存サービスの `ports:` 削除(全サービス対象):
  - api: `8080:8080` 削除
  - phpmyadmin: `8081:80` 削除
  - ollama: `11434:11434` 削除
  - comfyui: `8188:8188` 削除
  - plantuml: `8085:8080` 削除
  - mailhog: `1025:1025`, `8025:8025` 削除(内部通信のみ)
  - mysql: `3306:3306` **削除(重要: PHPMyAdmin からのアクセスのみ許可、直接 MySQL 接続は遮断)**
  - phpmyadmin の `depends_on` 設定は保持(nginx からは indirect に依存)

- [x] 既存サービスで環境変数が直接ポートを参照していないか確認
  - 例: `api` の `COMFYUI_BASE_URL: http://comfyui:8188` はそのまま(内部通信)
  - 例: web フロント環境変数で `API_BASE_URL` が `http://localhost:8080` を指していないか確認
    - もしそうなら `https://localhost/api` に修正

### nginx 設定ファイル作成

> **実装メモ**: 以下のコード例は設計時点のたたき台。実際の最終実装は
> [nginx/nginx.conf](../../nginx/nginx.conf) / [nginx/conf.d/default.conf](../../nginx/conf.d/default.conf)
> を正とする。主な相違点: `upstream {}` ブロックではなく `set $upstream_xxx host:port;` +
> `rewrite ^/prefix/(.*)$ /$1 break;` でプレフィックス除去(理由は後述の「問題が発生した場合の
> 調査・対応」参照)、`resolver 127.0.0.11` をnginx.confに追加(バックエンド未起動時の起動失敗回避)。

- [x] ディレクトリ構成(実際は `nginx.conf` を `nginx/` 配下に配置):
  ```
  .
  ├─ docker-compose.yml
  ├─ nginx/
  │  ├─ nginx.conf            (メインのnginx設定)
  │  └─ conf.d/
  │     └─ default.conf       (アップストリーム変数・ロケーション ルーティング)
  └─ certs/                   (TLS証明書・秘密鍵、scripts/generate-certs.shで生成)
     ├─ localhost.crt
     └─ localhost.key
  ```

- [x] `nginx.conf` 実装内容:
  ```nginx
  user nginx;
  worker_processes auto;
  error_log /var/log/nginx/error.log warn;
  pid /var/run/nginx.pid;
  
  events {
    worker_connections 1024;
  }
  
  http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" "$http_x_forwarded_for"';
    
    access_log /var/log/nginx/access.log main;
    
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    types_hash_max_size 2048;
    
    # Gzip 圧縮(オプション、性能向上)
    gzip on;
    gzip_vary on;
    gzip_proxied any;
    gzip_comp_level 6;
    gzip_types text/plain text/css text/xml text/javascript application/json application/javascript application/xml+rss;
    
    include /etc/nginx/conf.d/*.conf;
  }
  ```

- [x] `nginx/conf.d/default.conf` 実装内容:
  ```nginx
  # HTTP → HTTPS リダイレクト
  server {
    listen 80;
    server_name _;
    return 301 https://$host$request_uri;
  }
  
  # HTTPS メインサーバー
  server {
    listen 443 ssl http2;
    server_name localhost;
    
    ssl_certificate /etc/nginx/certs/localhost.crt;
    ssl_certificate_key /etc/nginx/certs/localhost.key;
    
    # SSL設定(HTTPS通信の安全性向上)
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    
    # クライアント最大リクエストサイズ(ファイルアップロード対応、必要に応じて変更)
    client_max_body_size 100M;
    
    # 上流サーバー定義
    upstream api_upstream {
      server api:8080;
    }
    
    upstream web_upstream {
      server host.docker.internal:3000;  # ホスト上の Next.js dev server
    }
    
    upstream phpmyadmin_upstream {
      server phpmyadmin:80;
    }
    
    upstream ollama_upstream {
      server ollama:11434;
    }
    
    upstream comfyui_upstream {
      server comfyui:8188;
    }
    
    upstream plantuml_upstream {
      server plantuml:8080;
    }
    
    upstream mailhog_upstream {
      server mailhog:8025;
    }
    
    # API ロケーション (X-Forwarded-* ヘッダ必須)
    location /api/ {
      proxy_pass http://api_upstream;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      proxy_set_header X-Forwarded-Path /api;
    }
    
    # Web フロント
    location / {
      proxy_pass http://web_upstream;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      # Next.js 対応: WebSocket サポート
      proxy_http_version 1.1;
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";
    }
    
    # PHPMyAdmin
    location /phpmyadmin/ {
      proxy_pass http://phpmyadmin_upstream/;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
    }
    
    # Ollama API (WebSocket対応)
    location /ollama/ {
      proxy_pass http://ollama_upstream/;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      # Ollama は API ベースなので、path rewrite は不要(ただし検証が必要)
    }
    
    # ComfyUI (WebSocket対応, 要path rewrite検証)
    location /comfyui/ {
      proxy_pass http://comfyui_upstream/;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      # WebSocket 対応
      proxy_http_version 1.1;
      proxy_set_header Upgrade $http_upgrade;
      proxy_set_header Connection "upgrade";
      # ComfyUI の /ws パスへのプロキシ(デバッグ等で要確認)
      # 必要に応じて sub_filter で path rewrite
    }
    
    # PlantUML
    location /plantuml/ {
      proxy_pass http://plantuml_upstream/;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
    }
    
    # Mailhog (開発時)
    location /mailhog/ {
      proxy_pass http://mailhog_upstream/;
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
    }
  }
  ```

### TLS 証明書生成スクリプト

- [x] スクリプト作成: `scripts/generate-certs.sh`
  ```bash
  #!/bin/bash
  # 自己署名証明書生成スクリプト
  
  CERTS_DIR="./certs"
  CERT_FILE="$CERTS_DIR/localhost.crt"
  KEY_FILE="$CERTS_DIR/localhost.key"
  
  # ディレクトリが存在しない場合は作成
  mkdir -p "$CERTS_DIR"
  
  # 既存証明書がある場合はスキップ
  if [ -f "$CERT_FILE" ] && [ -f "$KEY_FILE" ]; then
    echo "Certificate already exists: $CERT_FILE"
    exit 0
  fi
  
  # openssl で自己署名証明書を生成
  # 有効期限: 365日、2048ビット RSA
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout "$KEY_FILE" \
    -out "$CERT_FILE" \
    -subj "/C=JP/ST=Tokyo/L=Tokyo/O=LetsBlog/CN=localhost"
  
  echo "Self-signed certificate generated:"
  echo "  Certificate: $CERT_FILE"
  echo "  Key: $KEY_FILE"
  ```

- [x] `.env.example` に証明書生成手順を記載(コメント)
  ```bash
  # 初回起動時に以下を実行してください
  # bash scripts/generate-certs.sh
  ```

- [x] docker-compose 起動前に証明書を確認・生成する手順を検討
  - オプション A: `docker-compose up -d` 前に `scripts/generate-certs.sh` を手動実行
  - オプション B: init コンテナまたは nginx の entrypoint で自動生成(複雑性が増すため今回は見送り)

### 既存アプリの設定変更

- [x] Web フロント (Next.js) の環境変数確認・修正
  - 現状で `API_BASE_URL` が `http://localhost:8080` 等と直接ポートを参照していないか確認
  - もしそうなら `https://localhost/api` に修正(`web/.env.local` または `app.config` )

- [x] API サーバー (`application.yml`) の設定確認
  - `COMFYUI_BASE_URL`, `OLLAMA_BASE_URL` 等の内部参照 → Docker 内部通信(変更不要)
  - Web フロント向けの `APP_WEB_BASE_URL` → `https://localhost` に修正(存在する場合)

- [x] Web フロント内の管理画面リンク確認・修正
  - 特に `/system` ページで、各サービスへの直接リンク(`http://localhost:8080`, `http://localhost:8081` 等)を使用している場合は削除または `https://localhost/...` に修正

### 動作検証

- [x] 自己署名証明書を生成
  ```bash
  bash scripts/generate-certs.sh
  ```

- [x] docker-compose を起動
  ```bash
  docker compose up -d
  ```

- [x] 各サービスへの HTTPS アクセス確認(curl または ブラウザ)
  ```bash
  curl -k https://localhost/api/health     # API ヘルスチェック
  curl -k https://localhost/phpmyadmin/    # phpMyAdmin(HTMLリダイレクト確認)
  curl -k https://localhost/ollama/api/tags  # Ollama API
  # 以下、その他サービスも同様
  ```

- [x] 個別ポートへのアクセスが遮断されていることを確認
  ```bash
  curl -k https://localhost:8080/api/health     # 失敗(ポート 8080 公開なし)
  curl http://localhost:3000/                   # 失敗(ホスト上 dev server の外部アクセスも制限)
  ```

- [x] `https://localhost` へのアクセス確認(curlベース。ブラウザでの目視確認・自己署名証明書の例外承認操作自体は未実施)
  - Web フロント管理画面のトップ(`/`)が未ログイン時に `/login` へ307リダイレクトされることを確認
  - `/login` が200で応答することを確認

- [x] nginx ログの確認(必要に応じてデバッグ)
  ```bash
  docker logs lbs-reverse-proxy
  ```

### ComfyUI/Ollama のサブパス対応検証

- [x] ComfyUI の `/comfyui` パス配下での動作確認
  - ルートHTML(`GET /comfyui/`)が200で応答することを確認
  - 静的アセット(JS/CSS)は相対パス(`href="js/..."`, `src="./assets/..."`)で参照されており、`/comfyui/` 配下でも追加のpath書き換えなしで200が返ることを確認(実際に `js/vendor/jquery/jquery.min.js`、`assets/index-*.js` を個別に取得して確認)
  - WebSocket通信(`/comfyui/ws`)は、クライアントがHTTP/1.1でハンドシェイクした場合に101 Switching Protocolsで確立し、ComfyUIから実際のstatusメッセージ(`{"type":"status",...}`)を受信できることを確認
    - 注: curlをHTTP/2(既定のALPN交渉)のまま使うとUpgradeヘッダが正しく機能せず400になる。ブラウザのWebSocket APIは常にHTTP/1.1相当のUpgradeハンドシェイクを行うため、実際のブラウザ利用では問題にならない見込み(未確認)
  - `sub_filter` によるHTML内パス書き換えは現状のComfyUIビルドでは(相対パスのため)効果が薄いことが判明。将来のComfyUIバージョンアップで絶対パス参照が混入した場合の保険として設定は残す
  - 未検証: ComfyUI JS バンドル内部で `fetch("/prompt")` 等の絶対パスAPI呼び出しが行われていないか(実際にワークフローを投入して画像生成が完走するかは、モデル/チェックポイント配置を伴う実運用テストが必要なため未実施)

- [x] Ollama の `/ollama` パス配下での API 動作確認
  - `GET /ollama/api/tags` で実際にpull済みモデル(`qwen2.5:7b-instruct`)一覧のJSONが取得できることを確認
  - API レスポンスは正常(JSON Content-Type)

- [x] 問題が発生した場合の調査・対応
  - 実装中に発覚した実際の問題と対処:
    1. `proxy_pass` に変数(`set $upstream_xxx ...`)を使うと、location プレフィックスの自動除去(URI部分によるリライト)が効かず、常に固定URI(`/`)がバックエンドに渡ってしまう(Ollamaのアクセスログで全リクエストが`GET /`になっていたことで発覚)。`rewrite ^/prefix/(.*)$ /$1 break;` を明示的に追加して解消
    2. (当初のホスト上 `npm run dev` 方式で発生)`host.docker.internal` は Docker の組み込みDNS(`resolver 127.0.0.11`)では解決できず、`extra_hosts`(`/etc/hosts`)経由の静的解決のみ有効だった。後述の通りWebフロントをコンテナ化したことで、この制約自体が不要になった
    3. ホスト上の `npm run dev` を手動起動する運用は、プロセス停止・再起動忘れにより `https://localhost/` が502を返す障害を実機で誘発した(ユーザー報告により発覚)。Webフロントを`web`サービスとしてdocker-compose管理下に移し、`docker compose up -d`で自動起動・再起動されるように変更して解消(nginxの`location /`も`web:3000`への変数ベース転送に統一し、`host.docker.internal`/`extra_hosts`は削除)

## 未決事項・要検証事項

- **ComfyUI JS バンドル内の絶対パスAPI呼び出し**: `fetch("/prompt")` 等、`/comfyui` プレフィックスを考慮しないハードコードされた絶対パスがJS実行時に呼ばれていないか(静的アセット・WebSocket疎通は確認済みだが、実際にワークフローを実行してのエンドツーエンド検証は未実施)
- **ブラウザでの目視確認**: 自己署名証明書の警告表示・例外承認操作、実際のログイン〜各画面遷移はcurlでの疎通確認に留まり、ブラウザでの実機確認は未実施

## 関連ドキュメント

- Phase 6 [00-overview](00-overview.md) — 全体スコープ
- Phase 6 [02-setup-manual](02-setup-manual.md) — セットアップマニュアル
