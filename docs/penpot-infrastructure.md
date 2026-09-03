# Penpot インフラストラクチャ設計書

> **この文書の位置づけ**: Penpot 導入時の**設計記録**である。導入の判断と構成の意図を残すもので、
> 現行の稼働構成そのものではない。ただし §3.1 のサブパス構成図だけは「どの経路が外部に開いて
> いるか」を伝えるため、`infra/nginx/conf.d/default.conf` の実態と一致させて維持する
> （`scripts/test_docs_reverse_proxy_diagram.py` が機械的に検査する。#1010）。
>
> 他の節に出てくる構成（コンテナ名・ファイル名・Ollama 連携など）は当時の設計であり、
> 現行と食い違いうる。実態は `docker-compose.yml` と `infra/nginx/conf.d/default.conf` を見ること。

## 概要

Let's Blog Server に Penpot（オープンソースデザインツール）を Docker コンテナとしてセットアップ。Ollama との MCP 連携により、AI 支援デザイン機能を実現。

---

## 1. アーキテクチャ決定

### 1.1 セットアップ方式

**選定: オンプレミス（セルフホスト）**

Penpot 公式の Docker イメージを使用し、ローカル Docker Compose 環境で実行。クラウド版（penpot.app）ではなく、セルフホストすることで以下を実現：

- ✅ Ollama MCP との緊密な統合（内部ネットワーク）
- ✅ オフライン利用（インターネット不要）
- ✅ プライバシー・セキュリティ確保
- ✅ 低レイテンシな AI デザイン支援

### 1.2 データベース戦略

**PostgreSQL をPenpot専用DB として新規追加**

| 項目 | 決定 | 理由 |
|------|------|------|
| DBMS | PostgreSQL 15 | Penpot 公式推奨、Let's Blog (MySQL) との分離 |
| 用途 | Penpot のみ | 独立した永続化、バックアップ戦略 |
| ボリューム | `penpot_postgres` | Docker named volume |
| ネットワーク | `lbs-net` | 既存サービスと統合 |

### 1.3 キャッシュ戦略

**Redis: 不要（Penpot 最新版は Optional）**

Penpot 公式イメージ（`penpot/penpot:latest`）は Redis なしで動作可能。本フェーズでは省略。

**将来:** 大規模デザイン・複数ユーザー同時編集時に Redis 導入を検討。

### 1.4 ストレージ戦略

| 対象 | 永続化方法 | 用途 |
|------|-----------|------|
| Penpot DB | PostgreSQL volume (`penpot_postgres`) | プロジェクト・ファイル・メタデータ |
| Penpot ファイル | コンテナ内部 (no explicit volume) | デザインファイル（DB に格納） |
| ユーザー uploads | コンテナ内部 | アップロード画像等 |

---

## 2. コンテナ構成

### 2.1 Penpot コンテナ

```yaml
penpot:
  image: penpot/penpot:latest
  container_name: lbs-penpot
  ports:
    - "8989:80"  # nginx リバースプロキシで /penpot にマップ
  environment:
    PENPOT_POSTGRES_URI: "postgresql://penpot_user:${PENPOT_DB_PASSWORD}@penpot_postgres:5432/penpot"
    PENPOT_PUBLIC_URI: "https://localhost/penpot"
    # その他の Penpot 設定は defaults で OK
  depends_on:
    - penpot_postgres
  networks:
    - lbs-net
```

**環境変数:**
- `PENPOT_POSTGRES_URI` - PostgreSQL 接続文字列
- `PENPOT_PUBLIC_URI` - ブラウザからアクセスする公開 URL

### 2.2 PostgreSQL コンテナ（Penpot用）

```yaml
penpot_postgres:
  image: postgres:15-alpine
  container_name: lbs-penpot-postgres
  environment:
    POSTGRES_DB: penpot
    POSTGRES_USER: penpot_user
    POSTGRES_PASSWORD: ${PENPOT_DB_PASSWORD}
  volumes:
    - penpot_postgres:/var/lib/postgresql/data
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U penpot_user"]
    interval: 10s
    timeout: 5s
    retries: 5
  networks:
    - lbs-net
```

**理由:**
- Alpine ベース (軽量、≈40MB)
- PostgreSQL 15 (安定版)
- Penpot の公式推奨

---

## 3. ネットワーク・ルーティング構成

### 3.1 サブパス構成

```
https://localhost/
├─ /             ← Web UI (Next.js, reverse-proxy → web:3000)
├─ /api          ← Let's Blog API (reverse-proxy → gateway:8080)
├─ /auth         ← Keycloak (reverse-proxy → keycloak:8080)
├─ /penpot       ← Penpot (reverse-proxy → penpot:80)
├─ /drawio       ← draw.io (reverse-proxy → drawio:8080)
├─ /phpmyadmin   ← PhpMyAdmin (reverse-proxy → phpmyadmin)
└─ /sites        ← 管理対象 WordPress サイト (reverse-proxy → 各サイトのコンテナ)
```

この図は要約であり、内部向けの `location`（`/nginx-health` など）は載せていない。
網羅的な一覧は `infra/nginx/conf.d/default.conf` を参照。

**外部に開いていない経路**（過去に図へ載っていたもの）:

| 経路 | 現状 |
| --- | --- |
| `/ollama` | **存在しない。** `lbs-ollama` コンテナごと廃止済み |
| `/comfyui` | **#979 で `location` を削除した。** ブラウザからは到達しない。ComfyUI へはサービス間通信でのみ到達する |

`/api` の中継先は `gateway` である。`api` という名前のサービスは `docker-compose.yml` に存在しない。

> `/penpot` の中継先 `penpot:80` は、`docker-compose.yml` のサービス名が `penpot-frontend` で
> あるため名前解決できず、常に 502 になる（#1012）。ここは nginx 側の不具合であり、
> 図は nginx の記述をそのまま反映している。

### 3.2 nginx ルーティング設定

ファイル: `infra/nginx/conf.d/penpot.conf` (新規作成)

```nginx
location /penpot {
    proxy_pass http://penpot:80/penpot;
    
    # HTTP/1.1 upgrade for WebSocket
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    
    # Headers
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    
    # Timeouts (real-time collaboration のため長めに設定)
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    
    # Buffers
    proxy_buffering on;
    proxy_buffer_size 4k;
    proxy_buffers 8 4k;
    proxy_busy_buffers_size 8k;
}
```

**設定ポイント:**
- WebSocket upgrade サポート（リアルタイム協調編集用）
- 長タイムアウト（大規模デザイン操作用）
- バッファ設定（静的アセット転送最適化）

---

## 4. 環境変数設定

### 4.1 .env に追加する変数

```bash
# Penpot PostgreSQL
PENPOT_DB_PASSWORD=changeme_penpot_db_password
```

### 4.2 .env.example に追加

```bash
# Penpot Database (PostgreSQL)
PENPOT_DB_PASSWORD=changeme_penpot_db_password
```

---

## 5. リソース要件

### 5.1 システムリソース（追加分）

| 項目 | 必要値 | 説明 |
|------|--------|------|
| CPU | 2コア以上 | Penpot コンテナ |
| メモリ | 2-4GB | Penpot + PostgreSQL |
| ディスク | 500MB (image) + 1GB (DB) | イメージ + データベース |

### 5.2 既存との合計

現在の Let's Blog Server:
- CPU: 4コア推奨
- メモリ: 8GB以上
- ディスク: 20GB+

Penpot 追加後（推奨構成）:
- CPU: 6コア以上
- メモリ: 12GB以上
- ディスク: 30GB+

---

## 6. バージョン・イメージ選定

| コンポーネント | イメージ | バージョン | 選定理由 |
|----------------|----------|----------|----------|
| Penpot | `penpot/penpot:latest` | Latest stable | 公式推奨、自動更新 |
| PostgreSQL | `postgres:15-alpine` | 15 Alpine | 軽量、安定版 |

---

## 7. 依存関係・起動順序

```
docker-compose up
└─ reverse-proxy (nginx)
└─ penpot_postgres (PostgreSQL)
   └─ penpot (Penpot)
```

**depends_on 設定:**
```yaml
penpot:
  depends_on:
    penpot_postgres:
      condition: service_healthy
```

---

## 8. バックアップ・リカバリ戦略

### 8.1 PostgreSQL バックアップ

**定期バックアップ（オプション）:**

```bash
# 手動バックアップ
docker exec lbs-penpot-postgres pg_dump -U penpot_user penpot > penpot_backup.sql

# リストア
docker exec -i lbs-penpot-postgres psql -U penpot_user penpot < penpot_backup.sql
```

**ボリュームバックアップ:**

```bash
# ボリューム全体をバックアップ
docker run --rm -v penpot_postgres:/data -v $(pwd):/backup \
  alpine tar czf /backup/penpot_postgres.tar.gz -C /data .
```

### 8.2 リカバリ手順

1. ボリュームを削除: `docker volume rm penpot_postgres`
2. 新規ボリューム作成・リストア（または新規初期化）
3. Penpot コンテナ再起動

---

## 9. セキュリティ考慮事項

| 項目 | 対応 | 説明 |
|------|------|------|
| DB パスワード | 環境変数管理 | `.env` に安全に保存 |
| ネットワーク分離 | `lbs-net` bridge | 内部ネットワーク内のみ通信 |
| TLS/HTTPS | nginx termination | 自己署名証明書で保護 |
| ポート公開 | 非公開 | ローカルホストのみ(localhost) |

---

## 10. ヘルスチェック・監視

### 10.1 PostgreSQL ヘルスチェック

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U penpot_user"]
  interval: 10s
  timeout: 5s
  retries: 5
```

### 10.2 Penpot ヘルスチェック

Penpot イメージのデフォルト healthcheck を使用（ある場合）。

### 10.3 ログ監視

```bash
# Penpot ログ確認
docker logs lbs-penpot

# PostgreSQL ログ確認
docker logs lbs-penpot-postgres
```

---

## 11. トラブルシューティング

### 11.1 Penpot が 起動しない

**症状:** `docker logs lbs-penpot` にエラー表示

**対応:**
1. PostgreSQL が正常起動しているか確認
   ```bash
   docker logs lbs-penpot-postgres
   ```
2. 接続文字列（`PENPOT_POSTGRES_URI`）を確認
3. PostgreSQL ボリュームが破損していないか確認

### 11.2 nginx で /penpot にアクセスできない

**症状:** 404 または接続拒否

**対応:**
1. nginx 設定をリロード
   ```bash
   docker exec lbs-reverse-proxy nginx -t
   docker exec lbs-reverse-proxy nginx -s reload
   ```
2. Penpot コンテナが起動しているか確認
   ```bash
   docker ps | grep penpot
   ```

### 11.3 WebSocket 接続エラー

**症状:** ブラウザコンソールに WebSocket エラー

**対応:**
1. nginx の WebSocket 設定を確認（penpot.conf）
2. ファイアウォール・プロキシ設定を確認

---

## 12. 今後の検討項目

- [ ] Redis キャッシュ層の導入（複数ユーザー大規模デザイン対応）
- [ ] Penpot API トークン認証（プラグイン連携）
- [ ] 定期自動バックアップスクリプト
- [ ] Penpot プラグイン開発環境の構築
- [ ] 本番環境への移行手順書

---

## 参考資料

- Penpot 公式ドキュメント: https://help.penpot.app/
- Penpot Docker セットアップ: https://github.com/penpot/penpot/tree/main/docker
- PostgreSQL Alpine: https://hub.docker.com/_/postgres
