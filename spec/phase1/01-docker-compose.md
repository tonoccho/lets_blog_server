# 01. Docker Compose 構成

## 目的

Phase 1で必要な全サービス(API/DB/管理ツール/AI関連)を、ローカル環境でDocker Composeにより一括起動・管理できるようにする。

## 対象サービス一覧

| サービス名 | 役割 | 想定イメージ | 公開ポート | GPU |
|---|---|---|---|---|
| `api` | Spring Boot 仲介API | 自前Dockerfile(Maven/Gradleマルチステージビルド) | 8080 | 不要 |
| `mysql` | データストア | `mysql:8.0` | 3306 | 不要 |
| `phpmyadmin` | MySQL管理UI | `phpmyadmin/phpmyadmin` | 8081 | 不要 |
| `ollama` | ローカルLLM | `ollama/ollama:latest` | 11434 | 推奨 |
| `comfyui` | 画像生成 | 要選定(下記「未決事項」参照) | 8188 | 必須 |
| `plantuml` | 図表レンダリング | `plantuml/plantuml-server:jetty` | 8085 | 不要 |
| `web`(任意) | 管理フロントエンド | 自前Dockerfile(Node.js) | 3000 | 不要 |

`web` はPhase 1の早い段階ではローカルの `npm run dev` で直接起動し、後でコンテナ化する想定でも良い(詳細は [05-web-frontend](05-web-frontend.md) に委ねる)。

## ネットワーク設計

- 単一のブリッジネットワーク `lbs-net` を定義し、全サービスを参加させる。
- サービス間通信はコンテナ名で解決する(例: `api` → `http://mysql:3306`, `http://ollama:11434`)。
- ホストへの公開は上表のポートのみ。localhost限定運用のため、外部公開は行わない。

## ボリューム設計

| ボリューム | マウント先 | 用途 |
|---|---|---|
| `mysql_data` | `/var/lib/mysql` | DB永続化 |
| `ollama_models` | `/root/.ollama` | pull済みLLMモデル |
| `comfyui_models` | `/root/ComfyUI/models` | チェックポイント/LoRA等 |
| `comfyui_output` | `/root/ComfyUI/output` | 生成画像の出力先 |

## 環境変数(`.env`)設計

```env
# MySQL
MYSQL_ROOT_PASSWORD=changeme_root
MYSQL_DATABASE=lets_blog
MYSQL_USER=lbs_app
MYSQL_PASSWORD=changeme_app

# API server
SERVER_API_KEY=changeme_api_key

# ComfyUI(未決: 実イメージ確定後に更新)
COMFYUI_IMAGE=yanwk/comfyui-boot:latest
```

`.env` はGit管理外(`.gitignore`対象)とし、`.env.example` をリポジトリに含める。

## GPU設定(Ollama / ComfyUI)

- 前提: ホストにNVIDIA GPU + NVIDIA Container Toolkit がインストール済みであること。
- `docker-compose.yml` では `deploy.resources.reservations.devices` でGPUを予約する(Compose v2 / Docker Engine 19.03+の `--gpus` 相当)。
- GPUなし環境で動かす場合は、当該 `deploy` ブロックを削除すればCPUモードで起動可能(ComfyUIは実用に耐えない速度になる点に注意)。

## docker-compose.yml ドラフト

```yaml
services:
  api:
    build:
      context: ./api
      dockerfile: Dockerfile
    container_name: lbs-api
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE}?useSSL=false&serverTimezone=UTC
      SPRING_DATASOURCE_USERNAME: ${MYSQL_USER}
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_PASSWORD}
      APP_API_KEY: ${SERVER_API_KEY}
      OLLAMA_BASE_URL: http://ollama:11434
      COMFYUI_BASE_URL: http://comfyui:8188
      PLANTUML_BASE_URL: http://plantuml:8080
    depends_on:
      mysql:
        condition: service_healthy
    networks:
      - lbs-net

  mysql:
    image: mysql:8.0
    container_name: lbs-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE}
      MYSQL_USER: ${MYSQL_USER}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
    volumes:
      - mysql_data:/var/lib/mysql
    ports:
      - "3306:3306"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - lbs-net

  phpmyadmin:
    image: phpmyadmin/phpmyadmin
    container_name: lbs-phpmyadmin
    restart: unless-stopped
    environment:
      PMA_HOST: mysql
      PMA_USER: ${MYSQL_USER}
      PMA_PASSWORD: ${MYSQL_PASSWORD}
    ports:
      - "8081:80"
    depends_on:
      - mysql
    networks:
      - lbs-net

  ollama:
    image: ollama/ollama:latest
    container_name: lbs-ollama
    restart: unless-stopped
    ports:
      - "11434:11434"
    volumes:
      - ollama_models:/root/.ollama
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
    networks:
      - lbs-net

  comfyui:
    image: ${COMFYUI_IMAGE:-yanwk/comfyui-boot:latest}
    container_name: lbs-comfyui
    restart: unless-stopped
    ports:
      - "8188:8188"
    volumes:
      - comfyui_models:/root/ComfyUI/models
      - comfyui_output:/root/ComfyUI/output
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
    networks:
      - lbs-net

  plantuml:
    image: plantuml/plantuml-server:jetty
    container_name: lbs-plantuml
    restart: unless-stopped
    ports:
      - "8085:8080"
    networks:
      - lbs-net

networks:
  lbs-net:
    driver: bridge

volumes:
  mysql_data:
  ollama_models:
  comfyui_models:
  comfyui_output:
```

## 起動順序・依存関係

1. `mysql` が healthy になるまで `api` は起動待ち(`depends_on.condition: service_healthy`)。
2. `ollama` / `comfyui` はモデル/チェックポイントの初回ダウンロードに時間がかかるため、`api` からの疎通は起動直後にリトライ前提で実装する([03-api-server](03-api-server.md)側でヘルスチェック・リトライを検討)。
3. `phpmyadmin` / `plantuml` は他サービスと疎結合。

## 実装状況(更新: Phase1 Docker Compose 完了時点)

全サービスの起動・疎通を実機で確認済み。

| サービス | 確認内容 | 結果 |
|---|---|---|
| mysql | ヘルスチェック、Flywayマイグレーション自動適用 | OK(healthy) |
| phpmyadmin | `GET /` → 200 | OK |
| api | `/api/health`、`/api/sites`(登録・一覧・重複409) | OK |
| ollama | コンテナ内 `nvidia-smi` でGPU認識、`GET /` → `Ollama is running` | OK |
| plantuml | `GET /` → 302(正常なリダイレクト) | OK |
| comfyui | `yanwk/comfyui-boot:cu130-slim` で起動、`GET /system_stats` → 200、`torch.cuda.is_available()=True`(RTX 5070 Ti認識) | OK |

**ComfyUIの配布イメージは `yanwk/comfyui-boot:cu130-slim` に確定**(ホストのCUDA 13.2ドライバに合わせてcu130系タグを採用。`latest`タグは存在しないため注意)。CPU機では`:cpu`、AMD機では`:rocm`系タグに差し替える。

**運用メモ: ディスク容量**
ComfyUIイメージは約4.9GB。Dockerのビルドキャッシュ(`docker builder prune`で削除可能)が肥大化しやすく、今回も35GB近く溜まっていて容量不足でpullが失敗した。他サービスと共有のDocker環境で運用する場合は、定期的に `docker builder prune` でビルドキャッシュを掃除すること(イメージ・ボリュームは消さない安全な範囲の掃除)。

## タスクチェックリスト

- [x] `docker-compose.yml` を実際にリポジトリ直下に作成
- [x] `.env.example` を作成
- [x] `api/Dockerfile`(マルチステージビルド)を作成
- [x] ComfyUIの実イメージを確定し、上記ドラフトを更新 → `yanwk/comfyui-boot:cu130-slim`
- [x] GPU動作確認(`nvidia-smi` をコンテナ内から実行できるか)→ ollama/comfyui両方で確認
- [x] 全サービス `docker compose up -d` → ヘルスチェック確認
- [x] `docker compose down` → `up -d` での再作成後もボリュームによりMySQLの登録データが永続化されることを確認(未実施)

## 未決事項

- Ollamaで利用するモデル(サイズ・日本語対応)の選定は [06-ollama-integration](06-ollama-integration.md) 側で確定する。
- API/Webのコンテナ化タイミング(開発中はホットリロードのためホスト直接実行にするか)。
- ComfyUIのカスタムノード/チェックポイントモデルの配置・永続化方法([07-comfyui-integration](07-comfyui-integration.md)で検討)。
