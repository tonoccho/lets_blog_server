# Phase 6: リバースプロキシ導入・サブパスルーティング・セットアップマニュアル整備

## 目的

Phase 5までで認証・監査ログ・2FA・RBAC・真のプロビジョニング機能が完備された。現状は各サービス(API:8080, phpMyAdmin:8081, Ollama:11434, ComfyUI:8188, PlantUML:8085, Mailhog:8025, Webフロント:3000)が個別ポートで直接公開されており、ユーザーは複数のURLを覚える必要がある。

Phase 6では、運用・利便性を向上させるため、以下3つの要件を実装する:

1. **リバースプロキシサーバーのコンテナを追加**: すべてのサービスに `https://localhost` 経由でアクセス可能にし、個別ポート公開を廃止する
2. **ComfyUI/Ollamaのサブパスアクセス対応**: サブドメインではなく、パスベースのルーティング(`/ollama`, `/comfyui` 等)でアクセスできるようにする
3. **セットアップマニュアルの整備**: 初回セットアップ・環境構築の手順を統一したドキュメントにまとめる

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| リバースプロキシ実装 | **nginx** (設定がシンプルで軽量、パスベースルーティングの実績が豊富) |
| TLS証明書 | **openssl による自己署名証明書** (開発環境向け、ブラウザ側で例外承認が必要) |
| 既存個別ポート公開 | **廃止・リバースプロキシ経由に一本化** (要件1「すべてのURL」を厳密に満たすため、逆アクセスは許可しない) |
| ルーティング方式 | **パスベース**(サブドメイン不使用。localhost はワイルドカードSAN対応が困難なため) |
| 既存サービスのURL一覧 | `/` (web) / `/api/` (API) / `/phpmyadmin/` (phpMyAdmin) / `/ollama/` (Ollama) / `/comfyui/` (ComfyUI) / `/plantuml/` (PlantUML) / `/mailhog/` (Mailhog, 開発時のみ) |

## アーキテクチャ

```
クライアント(ブラウザ)
    ↓ HTTPS/443 (https://localhost)
[nginx reverse-proxy コンテナ]
    ├─ / → [web コンテナ:3000]
    ├─ /api/ → [api コンテナ:8080]
    ├─ /phpmyadmin/ → [phpmyadmin コンテナ:80]
    ├─ /ollama/ → [ollama コンテナ:11434]
    ├─ /comfyui/ → [comfyui コンテナ:8188]
    ├─ /plantuml/ → [plantuml コンテナ:8080]
    └─ /mailhog/ → [mailhog コンテナ:8025] (開発時のみ)

【注】nginx は docker-compose の lbs-net 内部ネットワークで各サービスと通信。
外部(ブラウザ)には 443(https) と 80(http→https リダイレクト)のみを公開。
```

## Phase 6 のスコープ

実装項目:

### 1. リバースプロキシコンテナ追加・全URL https://localhost 一本化
- [01-reverse-proxy](01-reverse-proxy.md)
  - `docker-compose.yml` に nginx reverse-proxy サービス追加
    - ポート公開: `443:443` (HTTPS) / `80:80` (HTTP→HTTPSリダイレクト)
    - `lbs-net` 内で各サービスと通信
  - 既存各サービスの `ports:` マッピング廃止・削除
    - api / phpmyadmin / ollama / comfyui / plantuml / mailhog の個別ポート公開を停止
  - `nginx.conf` 作成(パスベースのリバースプロキシ設定)
    - `proxy_pass` でバックエンド URL へリダイレクト
    - `proxy_set_header` で X-Forwarded-* ヘッダ設定(バックエンドが正しいプロトコル/ホストを認識するため)
    - ComfyUI/Ollama の WebSocket 通信対応 (`/ws` パス)
  - 自己署名証明書の生成・管理
    - openssl でコンテナ起動時に証明書を生成するスクリプト(例: `generate-certs.sh`)
    - 既存証明書があれば再利用、なければ自動生成
  - テスト・動作検証
    - 全サービスへ `https://localhost/...` でアクセス可能なことを確認
    - 個別ポート(`8080`, `8081` 等)への直接アクセスが遮断されていることを確認
    - ComfyUI の WebSocket 通信・Ollama API の動作確認

### 2. ComfyUI/Ollama のサブパスルーティング対応
- [01-reverse-proxy](01-reverse-proxy.md)に統合(前項のルーティング設定に含まれる)
  - nginx での path prefix strip・rewrite 設定
  - ComfyUI/Ollama が元々パスプレフィックスを想定していない UI を持つ場合、`sub_filter` または アプリ側設定で対応
    - ComfyUI の静的アセット(JavaScript/CSS)の絶対パス参照を相対パスに修正、またはプロキシレイヤーで `sub_filter` して書き換える
    - Ollama は主に API のみなので対応の必要性は低い
  - WebSocket パス(`/ws`)の正しいリダイレクト確認

### 3. セットアップマニュアルの整備
- [02-setup-manual](02-setup-manual.md)
  - リポジトリ内に `docs/setup.md` (またはそれ相当のドキュメント)を新規作成
  - 内容:
    - システム要件(GPU / NVIDIA Container Toolkit / Docker / Docker Compose バージョン等)
    - `.env` ファイルの初期化・設定項目の説明
    - 自己署名証明書のブラウザ例外承認手順(ブラウザ別・OS別)
    - `docker compose up -d` による起動手順
    - Ollama モデルの pull 手順
    - ComfyUI チェックポイント配置の手順
    - 初回管理者アカウント作成手順(Phase 4 のセルフサインアップ方式)
    - 各サービスのアクセス URL 一覧(新形式: `https://localhost/...`)
    - トラブルシューティング(よくある問題・対処法)

対象外・スコープ外:

- 本番環境での正式な TLS 証明書(Let's Encrypt 等)運用・自動更新・ACME 対応 → Phase 7 以降の「外部公開」タスクで検討
- nginx 以外のリバースプロキシ(Traefik, Caddy等)の検証 → 今回は nginx に決定済み
- Webフロント(Next.js)のコンテナ化 → 現状はホスト上の dev server を前提、別途の検討課題とする
- ComfyUI/Ollama のコンテナ内での path-prefix 設定オプション調査 → 必要に応じて実装フェーズで調査
- HTTP Basic認証・API Key認証等、nginx レイヤーでの追加認証 → Phase 7+ の検討課題

## タスク一覧

1. [01-reverse-proxy](01-reverse-proxy.md) — リバースプロキシコンテナ追加・https://localhost 一本化・パスベースルーティング実装
2. [02-setup-manual](02-setup-manual.md) — セットアップマニュアル整備

## 実装順序

1. アーキテクチャ確認・nginx 設定ファイル設計(ルーティングテーブル確定)
2. 自己署名証明書生成スクリプト実装
3. `docker-compose.yml` に nginx サービス追加・既存サービスのポート公開廃止
4. nginx 設定ファイル(`nginx.conf`, `conf.d/`)実装
5. ComfyUI/Ollama のサブパス動作検証・必要に応じ sub_filter 等で調整
6. 全サービスの `https://localhost` 経由アクセス確認
7. 既存ドキュメント(Phase 1-5)の設定方法等を集約し、セットアップマニュアル作成
8. 初回管理者アカウント作成・各サービスへのアクセス検証
9. エンドツーエンド検証(環境破壊→再構築、全機能動作確認)

## 未決事項

- **ComfyUI/Ollama の静的アセット path 修正**: 元々サブパス配信を想定していない UI のため、フロントエンド側の絶対パス参照(`/js`, `/css` 等)がプレフィックス配下で正しく読み込まれるか要検証。nginx の `sub_filter` で HTML を書き換えるか、各アプリの base-path 設定オプションを使うか、要判断
- **Webフロント(Next.js)のコンテナ化**: 現状は `docker compose up` の外で `npm run dev` を起動している。コンテナ化後に nginx でプロキシするか、現状のまま maintain するか(ホスト側 3000 でリッスン)
- **自己署名証明書の再生成タイミング**: コンテナ再起動時に毎回生成するか、既存ファイルがあれば再利用するか
- **ブラウザの自己署名証明書警告対応**: ユーザーに「例外承認」を求めるのか、スクリプト側で環境変数等で証明書パスを指定可能にするのか(CI/CD 環境での検証用)
- **セットアップマニュアルの設置場所・形式**: リポジトリ直下に `docs/setup.md` とするか、`README.md` に組み込むか(ただし既存禁止事項に「README・ドキュメントを勝手に変更しない」とあるため、別ファイルの新規作成を推奨)
