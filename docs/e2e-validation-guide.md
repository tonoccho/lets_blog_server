# エンドツーエンド動作検証ガイド

## 概要

本ドキュメントは、Let's Blog Server の全コンポーネント（Penpot・MCP・Ollama・nginx等）が正しく起動・連携することを確認するための検証ガイドです。

対象：
- Docker Compose 構成全体
- Ollama ↔ MCP サーバー連携
- Penpot プラグイン動作
- ダークモード・レスポンシブ確認

---

## 1. 事前確認

### 1.1 システム要件

```bash
# Docker 確認
docker --version
# Docker version 20.10 以上

# Docker Compose 確認
docker compose version
# Docker Compose version 1.29 以上

# ディスク容量確認（最小 20GB）
df -h
```

### 1.2 ポート確認

```bash
# 使用ポート
# - 3000: MCP Server
# - 5432: PostgreSQL (Penpot)
# - 11434: Ollama API
# - 443/80: nginx reverse proxy
# - 8000: Let's Blog (アプリケーション)

# ポート競合確認
lsof -i :3000
lsof -i :5432
lsof -i :11434
lsof -i :443
```

### 1.3 環境変数確認

```bash
# .env ファイルの存在・内容確認
cat .env

# 必須変数
# - OLLAMA_API_URL=http://ollama:11434
# - MCP_SERVER_PORT=3000
# - POSTGRES_PASSWORD=... (Penpot)
```

---

## 2. Docker Compose 起動テスト

### 2.1 段階的な起動

```bash
# Step 1: Ollama 起動（時間がかかる）
docker compose up -d ollama
docker compose logs -f ollama

# 期待出力:
# ollama_1 | time=... level=INFO msg="Listening on ..."

# Step 2: PostgreSQL 起動
docker compose up -d penpot_postgres
docker compose logs -f penpot_postgres

# 期待出力:
# PostgreSQL init process complete. Ready for start up.

# Step 3: Penpot 起動
docker compose up -d penpot
docker compose logs -f penpot

# 期待出力:
# penpot_1 | [INFO] Application server started...

# Step 4: MCP Server 起動
docker compose up -d mcp-penpot
docker compose logs -f mcp-penpot

# 期待出力:
# mcp-penpot_1 | [INFO] Server running on http://localhost:3000

# Step 5: nginx 起動
docker compose up -d nginx
docker compose logs -f nginx

# 期待出力:
# nginx_1 | ... nginx ... is running
```

### 2.2 全サービス起動

```bash
# すべてのサービスを一度に起動
docker compose up -d

# 起動状態確認
docker compose ps

# 期待出力:
# NAME                 COMMAND                  SERVICE             STATUS
# lbs-ollama           "ollama serve"           ollama              Up 3 minutes
# lbs-postgres         "docker-entrypoint..."   penpot_postgres     Up 2 minutes
# lbs-penpot           "docker-entrypoint..."   penpot              Up 1 minute
# lbs-mcp-server       "node src/server.js"     mcp-penpot          Up 30 seconds
# lbs-nginx            "nginx -g daemon off"    nginx               Up 10 seconds
```

### 2.3 ヘルスチェック

```bash
# MCP Server
curl http://localhost:3000/health
# 期待: {"status":"ok"}

# Ollama API
curl http://localhost:11434/api/tags
# 期待: {"models":[{"name":"qwen2.5:7b-instruct","size":...}]}

# PostgreSQL
docker compose exec penpot_postgres psql -U penpot -d penpot -c "SELECT 1"
# 期待: 1

# nginx (localhost/penpot)
curl -k https://localhost/penpot
# 期待: Penpot ページが返される
```

---

## 3. Ollama × MCP 連携テスト

### 3.1 MCP Server API テスト

#### Design Suggestion エンドポイント

```bash
curl -X POST http://localhost:3000/api/design-suggestion \
  -H "Content-Type: application/json" \
  -d '{
    "design_brief": "Primary button, blue, medium size",
    "brand_style": "modern",
    "audience": "technical"
  }'

# 期待: JSON レスポンス
# {
#   "component_name": "Button",
#   "design_suggestions": [...],
#   "implementation_code": "..."
# }
```

#### Improve Component エンドポイント

```bash
curl -X POST http://localhost:3000/api/improve-component \
  -H "Content-Type: application/json" \
  -d '{
    "component_info": {
      "name": "LoginForm",
      "description": "Login form with email and password"
    },
    "focus_areas": ["accessibility", "dark-mode"]
  }'

# 期待: 改善提案の JSON
```

#### Color Palette エンドポイント

```bash
curl -X POST http://localhost:3000/api/generate-color-palette \
  -H "Content-Type: application/json" \
  -d '{
    "brand_description": "tech startup, modern, trustworthy",
    "accessibility_level": "AA",
    "dark_mode": true
  }'

# 期待: カラーパレット JSON
# {
#   "palette": {
#     "primary": {"light": "#...", "dark": "#..."},
#     ...
#   },
#   "wcag_compliance": {...}
# }
```

#### Typography エンドポイント

```bash
curl -X POST http://localhost:3000/api/suggest-typography \
  -H "Content-Type: application/json" \
  -d '{
    "design_context": "web admin dashboard",
    "content_type": "structured"
  }'

# 期待: フォント提案 JSON
```

### 3.2 エラーハンドリング

```bash
# Ollama が応答しない場合
curl -X POST http://localhost:3000/api/design-suggestion \
  -H "Content-Type: application/json" \
  -d '{"design_brief":"test"}'

# 期待エラー:
# {"error":"Ollama service unavailable"}

# MCP logs で詳細確認
docker compose logs mcp-penpot | tail -20
```

---

## 4. Penpot プラグイン動作テスト

### 4.1 ブラウザでアクセス

```bash
# URL
https://localhost/penpot

# 初回アクセス
1. ユーザー登録・ログイン
2. 新規プロジェクト作成
3. 新規ファイルを開く
```

### 4.2 プラグイン インストール

```bash
# 前提: penpot-plugin をビルド済み
cd penpot-plugin
npm run build
npm run build:ui

# Penpot UI から:
1. Plugins → Add Plugin
2. manifest.json を選択
3. Install をクリック
```

### 4.3 プラグイン 機能テスト

#### デザイン提案機能

```
1. Penpot でキャンバスを開く
2. プラグインパネルを開く（右側）
3. "Design Suggestion" タブをクリック
4. 以下を入力:
   - Design Brief: "Primary button, blue, medium"
   - Brand Style: "Modern"
   - Audience: "Technical"
5. "Generate" ボタンをクリック
6. 提案が表示される
7. "Apply to Canvas" をクリック
8. キャンバスにコンポーネントが追加される

期待: MCP API が呼ばれて、Ollama が提案を生成
```

#### コンポーネント改善

```
1. キャンバス上でコンポーネントを選択
2. プラグインの "Improve Component" タブへ
3. Focus Areas を選択（Accessibility など）
4. "Get Improvements" をクリック
5. 改善提案が表示される

期待: 選択したコンポーネント情報がMCP に送信される
```

#### カラーパレット生成

```
1. "Color Palette" タブをクリック
2. Brand Description を入力
3. "Generate Palette" をクリック
4. パレットが生成される

期待: カラーパレットが表示・使用可能になる
```

### 4.4 プラグイン デバッグ

```bash
# ブラウザコンソール（DevTools）で確認
F12 → Console

# 期待される操作:
1. コンソールエラーがないか確認
2. ネットワークリクエスト（http://localhost:3000/api/...）が表示されるか確認
3. レスポンスが JSON か確認

# MCP Server ログ確認
docker compose logs -f mcp-penpot
```

---

## 5. UI コンポーネント テスト

### 5.1 Let's Blog Web UI テスト（デスクトップ）

```bash
# アプリケーション URL
http://localhost:8000

# テスト項目
1. ログインページ
   - 入力フィールドにフォーカス時の outline 表示
   - エラー表示の見え方
   - ボタンのホバー・active 状態

2. ダッシュボード
   - Stats Card の表示
   - Chart の描画
   - Table の行ホバー効果

3. 投稿一覧
   - テーブルの responsiveness
   - セレクトボックスの見え方
   - ページネーション

4. 投稿編集
   - Form input の見え方
   - Rich editor の操作
   - Sidebar の sticky 位置
```

### 5.2 ダークモード テスト

```bash
# OS 設定でダークモード を有効
# → Web UI が自動的にダークモードに切り替わる

# テスト項目
1. 背景色が暗くなるか
2. テキストコントラストが WCAG AA 以上か
3. すべてのコンポーネントが正しく表示されるか
4. グラフ・チャートの色が見やすいか

# CSS 変数確認
F12 → Computed → filter by: color
```

### 5.3 レスポンシブ テスト

```bash
# DevTools で各ブレークポイントをテスト
F12 → Toggle device toolbar

# テストサイズ
- Mobile: 375px（iPhone SE）
- Tablet: 768px（iPad）
- Desktop: 1440px（フル幅）

# テスト項目
1. Navigation（Hamburger menu に切り替わるか）
2. Sidebar（Drawer/Off-canvas に変わるか）
3. Grid layout（1カラムに切り替わるか）
4. Font size（読みやすいか）
5. Button（タップサイズ 44px × 44px 以上か）
```

---

## 6. アクセシビリティ テスト

### 6.1 キーボード ナビゲーション

```bash
# テスト項目
1. Tab キーで全フォーム要素をフォーカス可能か
2. Shift+Tab で逆方向移動可能か
3. Enter / Space でボタン・チェックボックス操作可能か
4. Escape でモーダル閉じられるか
5. Arrow Keys でメニュー操作可能か
```

### 6.2 スクリーンリーダー テスト（NVDA / JAWS）

```bash
# 必須テスト
1. ページタイトル読み上げ
2. フォームラベル読み上げ
3. ボタンラベル読み上げ
4. エラーメッセージ読み上げ
5. リンク テキスト読み上げ

# NVDA のインストール（Windows）
https://www.nvaccess.org/

# テスト方法
1. NVDA を起動
2. Penpot / Let's Blog にアクセス
3. ナビゲーション動作を確認
```

### 6.3 色コントラスト テスト

```bash
# Chrome DevTools で確認
F12 → Elements → Computed

# 或は axe DevTools（ブラウザ拡張）
# 期待: WCAG AA 以上（最小 4.5:1）

# 手動テスト
https://www.tpadesign.com/color-contrast-checker/
# 背景色・テキスト色を入力して検証
```

---

## 7. パフォーマンス テスト

### 7.1 Lighthouse

```bash
# DevTools Lighthouse を実行
F12 → Lighthouse

# テスト対象
- Performance
- Accessibility
- Best Practices
- SEO

# 期待スコア
- Performance: ≥ 90
- Accessibility: ≥ 95
- Best Practices: ≥ 90
- SEO: ≥ 90
```

### 7.2 ロード時間

```bash
# デスクトップ環境での計測
DevTools → Network タブ

# 期待
- FCP (First Contentful Paint): < 1.8s
- LCP (Largest Contentful Paint): < 2.5s
- CLS (Cumulative Layout Shift): < 0.1
```

### 7.3 バンドルサイズ

```bash
# React app バンドルサイズ確認
npm run build
# dist/ フォルダのサイズ: < 500KB (gzip)

# CSS ファイルサイズ
# tokens.css: < 50KB
# global.css: < 20KB
```

---

## 8. ダークモード 完全テスト

### 8.1 色検証

```bash
# Design Tokens で定義された色が正しく適用されているか

# テスト項目
1. Primary 色の 50-900 レベル全て表示可能か
2. Neutral グレースケール全て表示可能か
3. Semantic 色（Success/Warning/Error/Info）が見分けやすいか
4. テキストコントラスト WCAG AA クリアしているか

# 手動チェック
CSS variables を確認
F12 → Computed → --color-*
```

### 8.2 Light → Dark 切り替え

```bash
# 方法 1: OS 設定で toggle
- Windows: 設定 → 個人用設定 → 色
- macOS: システム環境設定 → 一般 → 外観
- Linux: GNOME Settings → 外観

# 方法 2: アプリ内 toggle（実装時）
ヘッダーの テーマ切り替えボタン
```

### 8.3 各ページの確認

```bash
Light モード で見た後、Dark モード で以下を確認:

ページ単位:
- [ ] ログインページ
- [ ] ダッシュボード
- [ ] サイト一覧
- [ ] 投稿編集
- [ ] 設定ページ

コンポーネント単位:
- [ ] Button (全 variant)
- [ ] Card
- [ ] Modal
- [ ] Table
- [ ] Alert / Toast
- [ ] Form elements
```

---

## 9. トラブルシューティング

### 9.1 Docker Compose エラー

#### エラー: "Cannot connect to PostgreSQL"

```bash
# 原因: PostgreSQL が起動していない
# 解決:
docker compose logs penpot_postgres
docker compose up -d penpot_postgres
# 数秒待機
sleep 10
```

#### エラー: "MCP Server connection refused"

```bash
# 原因: MCP サーバーが起動していない or ポート被り
# 解決:
docker compose logs mcp-penpot
docker compose up -d mcp-penpot
lsof -i :3000  # ポート確認
```

#### エラー: "Ollama model not found"

```bash
# 原因: モデルをダウンロードしていない
# 解決:
docker compose exec ollama ollama pull qwen2.5:7b-instruct
# 数分待機（ダウンロード時間）
```

### 9.2 Penpot トラブル

#### トラブル: "プラグインが読み込まれない"

```bash
# 原因: manifest.json パスが誤っている
# 解決:
1. manifest.json が penpot-plugin/ ディレクトリに存在するか確認
2. plugin.js / ui.js がビルドされているか確認
3. ブラウザキャッシュをクリア (Ctrl+Shift+Delete)
4. Penpot を再読み込み
```

#### トラブル: "MCP API が応答しない"

```bash
# ブラウザコンソール確認
F12 → Console → Network errors

# MCP Server ログ確認
docker compose logs -f mcp-penpot

# CORS エラーの場合:
# → MCP Server の CORS 設定を確認
# → nginx の proxy_pass 設定を確認
```

### 9.3 UI テスト失敗

#### テキストが小さすぎる

```bash
# 原因: font-size が小さく設定されている
# 確認:
F12 → Elements → Computed styles
# font-size が 12px 以上か確認（WCAG 要件）
```

#### ボタンがクリックできない

```bash
# 原因: Z-index または display: none
# 確認:
F12 → Elements → Computed styles
# display: none / visibility: hidden がないか
# z-index が正しいか
# pointer-events が auto か
```

---

## 10. テスト チェックリスト

### Docker 起動テスト

- [ ] Ollama 起動・モデルロード完了
- [ ] PostgreSQL 起動・データベース初期化完了
- [ ] Penpot 起動・Web UI アクセス可能
- [ ] MCP Server 起動・health endpoint 応答
- [ ] nginx 起動・SSL 証明書有効

### API テスト

- [ ] Design Suggestion エンドポイント動作
- [ ] Improve Component エンドポイント動作
- [ ] Color Palette エンドポイント動作
- [ ] Typography エンドポイント動作
- [ ] エラーハンドリング動作

### Penpot プラグインテスト

- [ ] プラグインインストール成功
- [ ] Design Suggestion 機能動作
- [ ] Improve Component 機能動作
- [ ] Color Palette 機能動作
- [ ] Typography 機能動作
- [ ] キャンバスへの反映動作

### UI コンポーネントテスト

- [ ] ログインページ表示・入力動作
- [ ] ダッシュボード表示・レイアウト
- [ ] 投稿一覧表示・pagination
- [ ] 投稿編集フォーム動作
- [ ] 設定ページ表示

### ダークモードテスト

- [ ] ライトモード表示正常
- [ ] ダークモード切り替え正常
- [ ] 全ページダークモード対応
- [ ] コントラスト WCAG AA クリア

### レスポンシブテスト

- [ ] モバイル (375px) 表示正常
- [ ] タブレット (768px) 表示正常
- [ ] デスクトップ (1440px) 表示正常
- [ ] タッチ操作 (タブレット・モバイル)

### アクセシビリティテスト

- [ ] キーボードナビゲーション
- [ ] スクリーンリーダー対応
- [ ] 色コントラスト WCAG AA
- [ ] フォームラベル関連付け
- [ ] フォーカス表示

### パフォーマンステスト

- [ ] Lighthouse スコア ≥ 90
- [ ] FCP < 1.8s
- [ ] LCP < 2.5s
- [ ] バンドルサイズ < 500KB

---

## 11. テスト完了条件

✅ **すべてのチェックリスト項目が確認できた場合**

→ エンドツーエンド動作検証成功！

---

**完成日:** 2024-08-07  
**バージョン:** 1.0  
**次ステップ:** D-2 ドキュメント作成
