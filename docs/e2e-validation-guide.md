# エンドツーエンド動作検証ガイド

## 概要

本ドキュメントは、Let's Blog Server の全コンポーネントが正しく起動・連携することを
手動で確認するための検証ガイドです。自動化された E2E テスト(Playwright)の実行手順は
`docs/e2e-testing.md` を参照してください。

対象：

- Docker Compose 構成全体(マルチサービス構成)
- Keycloak による認証フロー
- サービス間連携(gateway → 各ドメインサービス)
- 複数スキーマに跨るデータ
- サービス障害時の縮退表示
- Penpot プラグイン / ComfyUI 等の周辺コンポーネント
- ダークモード・レスポンシブ・アクセシビリティ

> **更新(issue #588)**: サービス分割・Keycloak 移行に伴い、単一アプリケーション時代の
> 前提(`http://localhost:8000`、`ollama` / `mcp-penpot` コンテナ等)を全面的に改めています。

---

## 1. 事前確認

### 1.1 システム要件

```bash
docker --version          # 20.10 以上
docker compose version    # v2 以上
df -h                     # ディスク空き容量(最小 20GB。不足すると原因不明のテスト失敗を招く)
```

### 1.2 公開URLとポート

外部公開は reverse-proxy(nginx)経由に統一されており、アプリ利用時に個別ポートを開く必要はありません。

| URL | 内容 |
| --- | --- |
| `https://localhost/` | Web 管理画面(Next.js) |
| `https://localhost/auth/` | Keycloak(realm: `letsblog`) |
| `https://localhost/api/` | gateway(各ドメインサービスへルーティング) |
| `https://localhost/sites/{siteKey}/` | ManagedWordPress サイト |
| `https://localhost/phpmyadmin/` | phpMyAdmin |
| `https://localhost/comfyui/` | ComfyUI |
| `https://localhost/penpot` | Penpot |
| `https://localhost/plantuml/` `https://localhost/drawio/` | 図生成ツール |

```bash
# ポート競合確認(80/443 のみ外部公開)
sudo lsof -i :80
sudo lsof -i :443
```

### 1.3 環境変数確認

```bash
# .env の存在確認(未作成なら .env.example からコピーして値を埋める)
ls -l .env

# 代表的な必須変数
# - MYSQL_ROOT_PASSWORD / MYSQL_USER / MYSQL_PASSWORD
# - LBS_*_DB_PASSWORD(サービス別スキーマのユーザー。#570)
# - KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD / KEYCLOAK_DB_PASSWORD
# - KEYCLOAK_WEB_CLIENT_SECRET / KEYCLOAK_SERVICES_CLIENT_SECRET
# - NEXTAUTH_SECRET / APP_ENCRYPTION_KEY / RABBITMQ_USER / RABBITMQ_PASSWORD
```

### 1.4 証明書

```bash
./scripts/generate-certs.sh   # 初回のみ。reverse-proxy の自己署名証明書を生成する
```

---

## 2. Docker Compose 起動テスト

### 2.1 全サービス起動

```bash
docker compose up -d

# 起動状態確認(サービス名 / 状態 / ヘルス)
docker compose ps
```

主要サービス:

| サービス | 役割 | ヘルスチェック |
| --- | --- | --- |
| `reverse-proxy` | nginx。80/443 の単一入口 | `/nginx-health` |
| `web` | Next.js(BFF 兼 管理画面) | `/api/auth/csrf` |
| `gateway` | `/api/**` のルーティング・JWT 検証・レート制限 | actuator |
| `keycloak` / `keycloak-postgres` | 認証基盤 | `/auth/health/ready` / `pg_isready` |
| `identity` | ユーザー・ロール | actuator |
| `project` | サイト・プロジェクト・SSH 鍵 | actuator |
| `content` | 記事本文・カスタムタグ・投稿履歴 | actuator |
| `publishing` | 記事公開・一括管理・プレビュー | actuator |
| `media` | 画像生成・ダイアグラム | actuator |
| `ai` | LLM 呼び出し・記事プラン | actuator |
| `analytics` | GA / AdSense レポート | actuator |
| `platform` | システム設定・バックアップ・状態監視 | actuator |
| `log-writer` | 監査・操作ログ | actuator |
| `mysql` / `rabbitmq` | データストア / メッセージング | `mysqladmin ping` / `rabbitmq-diagnostics` |
| `wordpress` | ManagedWordPress の実体 | - |

### 2.2 全サービスが healthy になるまで待つ

```bash
# E2E に必要なサービス群を対象に待機する(Playwright の globalSetup と同じ判定)
./scripts/wait-for-stack-healthy.sh

# compose プロジェクト内の全コンテナを対象にする場合
./scripts/wait-for-stack-healthy.sh --all --timeout 900
```

期待結果: `OK: 対象サービスは全てhealthyです。`

失敗した場合は、未 healthy のサービス名が列挙されるので該当ログを見ます。

```bash
docker compose logs <service> | tail -50
```

### 2.3 個別ヘルスチェック

```bash
# gateway(および各 Spring Boot サービス)
docker compose exec gateway curl -fs http://localhost:8080/actuator/health

# Keycloak(realm の discovery document)
curl -sk https://localhost/auth/realms/letsblog/.well-known/openid-configuration | head -c 200

# Web(NextAuth の CSRF エンドポイント。認証不要で 200 が返る)
curl -sk https://localhost/api/auth/csrf

# reverse-proxy
docker compose exec reverse-proxy wget -q -O - http://127.0.0.1/nginx-health

# MySQL(全スキーマの存在確認)
docker compose exec mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES;"
# 期待: lbs_identity, lbs_project, lbs_content, lbs_media,
#       lbs_ai, lbs_analytics, lbs_platform, lbs_publishing, lbs_log
#       (分割前の lets_blog は #785 で廃止済み)
```

### 2.4 クリーンなボリュームからの起動検証

```bash
# 注意: MySQL データを削除します
./scripts/verify-clean-volume-boot.sh
```

---

## 3. 認証フロー(Keycloak)の検証

### 3.1 テストユーザーの用意

```bash
E2E_PROVISION_ADMIN_EMAIL='<letsblog realm の管理者アカウント>' \
E2E_PROVISION_ADMIN_PASSWORD='<その管理者のパスワード>' \
E2E_TEST_PASSWORD='...' E2E_ADMIN_PASSWORD='...' \
  ./scripts/provision-e2e-keycloak-users.sh
```

- 対象は `e2e-test@letsblog.local`(role: user)と `e2e-admin@letsblog.local`(role: admin)のみ
- ローカル開発の Keycloak コンテナ(`lbs-keycloak`)専用。共有 / 本番環境では実行しない
- 実ユーザーは**認証にだけ使い**、作成・変更・削除の対象にはしない
- **前提**: letsblog realm に admin ロールのユーザーが既に存在すること。ユーザー作成
  (`POST /api/users`)は issue #796 で admin 限定になったため、スクリプトはその管理者の
  トークンで API を呼ぶ。まだ管理者がいない環境では、先に初回セットアップ
  (legacy-api の `POST /api/auth/setup`)を済ませること。詳細は
  [docs/e2e-testing.md](e2e-testing.md) を参照

### 3.2 ログイン手順の確認

```
1. https://localhost/login を開く
   期待: 即座に https://localhost/auth/realms/letsblog/... のホスト型ログイン画面へ遷移する
2. e2e-admin@letsblog.local でサインインする
   期待: https://localhost/ (ダッシュボード)へ戻り、ヘッダーに「ログアウト」が表示される
3. https://localhost/users を開く
   期待: admin なので閲覧できる(e2e-test@letsblog.local の場合は "/" へリダイレクトされる)
4. ログアウトする
   期待: /login へ戻り、保護ページへ再アクセスすると再度 Keycloak へリダイレクトされる
```

### 3.3 トークン検証の確認

```bash
# 不正な JWT は gateway が 401 で拒否する
curl -sk -o /dev/null -w '%{http_code}\n' \
  -H 'Authorization: Bearer invalid.token.value' https://localhost/api/sites
# 期待: 401
```

---

## 4. サービス間連携の検証

### 4.1 主要シナリオ(サイト登録 → 記事公開 → 履歴確認)

自動テストは `web/e2e/main-scenario.spec.ts`。手動で確認する場合:

```
1. https://localhost/sites → 「WordPressを新規構築」で ManagedWordPress サイトを作成する
   期待: 数分で「構築しました。」と表示され、一覧にサイトが追加される(project-service)
2. 作成したサイトの「疎通確認」を押す
   期待: SUCCESS / FAILED のいずれかが確定的に表示される
3. VSCode 拡張(または API)から記事を公開する
   期待: WordPress に記事が作成される(publishing-service)
4. https://localhost/posts を開く
   期待: 投稿履歴に公開した記事が表示される(content-service)
```

API で公開する場合の例(Keycloak からトークンを取得して gateway 経由で呼ぶ)。
`admin-cli` は使わないこと — Keycloak既定で lightweight access token が有効になっており、
発行されるトークンから `sub` と `realm_access.roles` が欠落し、identity-service の
`/api/identity/me` が 403 になって記事公開が失敗する(issue #588)。代わりに E2E 専用の
`letsblog-e2e` クライアント(`scripts/provision-e2e-keycloak-users.sh` で作成済み)を使う:

```bash
TOKEN=$(curl -sk -X POST \
  https://localhost/auth/realms/letsblog/protocol/openid-connect/token \
  -d grant_type=password -d client_id=letsblog-e2e \
  -d username=e2e-admin@letsblog.local -d password="$E2E_ADMIN_PASSWORD" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -sk -X POST https://localhost/api/posts/publish \
  -H "Authorization: Bearer $TOKEN" \
  -F site=<siteKey> -F title='E2E manual check' \
  -F status=publish -F markdown='# E2E manual check'
```

### 4.2 複数スキーマに跨るデータの確認

サービス別スキーマ分離(#570 / ADR-0004)により、1つのプロジェクト / サイトは複数スキーマに
行を持ちます。サイト・プロジェクトを1件作成したあとで、想定どおりに書き込まれているかを確認します。

```bash
docker compose exec mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "
  SELECT COUNT(*) FROM lbs_project.sites;
  SELECT COUNT(*) FROM lbs_project.projects;
  SELECT COUNT(*) FROM lbs_content.posts;
  SELECT COUNT(*) FROM lbs_media.generated_images;
  SELECT COUNT(*) FROM lbs_ai.project_ai_settings;"
```

E2E が残したテストデータの掃除:

```bash
./scripts/e2e-cleanup-test-data.sh          # ドライラン(件数表示のみ)
./scripts/e2e-cleanup-test-data.sh --yes    # 実際に削除する
```

### 4.3 サービス障害時の縮退表示

```bash
# 下流サービスを1つ落とす
docker compose stop content

# 期待: https://localhost/posts は 500 にならず、
#       「全0件を表示」「投稿履歴はまだありません...」の空状態で描画される。
#       https://localhost/ のダッシュボードも投稿数 0 として表示され、他の情報は生きている。

# 復旧
docker compose start content
./scripts/wait-for-stack-healthy.sh --services content
```

同じ検証は `web/e2e/service-degradation.spec.ts` で自動化されています
(実際にコンテナを停止するテストは `E2E_ALLOW_SERVICE_DISRUPTION=1` のときのみ実行)。

### 4.4 Penpot / ComfyUI 等の周辺コンポーネント

```bash
# Penpot(penpot-frontend / penpot-backend / penpot-exporter / penpot-postgres / penpot-valkey)
docker compose up -d penpot-frontend
# ブラウザ: https://localhost/penpot

# ComfyUI(画像生成。media-service が利用する)
docker compose up -d comfyui
# ブラウザ: https://localhost/comfyui/

# ダッシュボードの「接続サービス状態」パネル(platform-service)で
# ComfyUI / PlantUML / WordPress プロビジョニング / Penpot の状態を確認できる
```

Penpot プラグインの導入・動作確認手順は `docs/penpot-setup.md` を参照してください。

---

## 5. UI コンポーネント テスト

### 5.1 Let's Blog Web UI テスト（デスクトップ）

```bash
# アプリケーション URL(reverse-proxy 経由。自己署名証明書のため警告を許可する)
https://localhost

# テスト項目
1. ログインページ(Keycloak のホスト型ログイン画面)
   - /login にアクセスすると Keycloak へリダイレクトされること
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

### 9.1 起動しない / healthy にならない

```bash
./scripts/wait-for-stack-healthy.sh --all --timeout 900   # 未 healthy のサービスを特定する
docker compose logs <service> | tail -50
df -h                                                     # ディスク不足は誤解を招く失敗の原因になる
```

よくある原因:

| 症状 | 原因 | 対処 |
| --- | --- | --- |
| いずれかのサービスがクラッシュループ | 自スキーマの Flyway 移行に失敗 | `docker compose logs <service>` を確認。#668 の起動デッドロックは #583/#786 で解消済み |
| `gateway` が起動しない | 依存サービス(各ドメインサービス)が未 healthy | 個別に `docker compose logs` を確認 |
| `web` が healthy にならない | `next dev` の初回コンパイルが遅い | `start_period` 経過まで待つ。低速環境では `E2E_HEALTH_TIMEOUT` を伸ばす |
| MySQL のスキーマが無い | 既存ボリュームでは init スクリプトが再実行されない | `docs/SERVICE_SCHEMA_MIGRATION.md` を参照 |

### 9.2 認証まわり

| 症状 | 原因 | 対処 |
| --- | --- | --- |
| ログイン後にコールバックで失敗する | redirect_uri 不一致(`https://localhost` 以外で開いている) | `https://localhost` でアクセスする |
| `Invalid username or password` | テストユーザー未発行 / パスワード不一致 | `./scripts/provision-e2e-keycloak-users.sh` を再実行 |
| API が 401 | トークン期限切れ・issuer 不一致 | Keycloak の `KC_HOSTNAME` 設定と再ログインを確認 |
| admin 操作が 403 | ローカル DB に `keycloak_sub` 付きユーザーが無い、またはその行の `role` が admin でない | identity-service 経由(`POST /api/users`。admin のトークンが必要。issue #796)で作成する |

### 9.3 縮退表示の確認で戻せなくなった

```bash
docker compose start content     # 停止したサービスを起動し直す
./scripts/wait-for-stack-healthy.sh
```

### 9.4 UI テスト失敗

#### テキストが小さすぎる

```bash
F12 → Elements → Computed styles
# font-size が 12px 以上か確認（WCAG 要件）
```

#### ボタンがクリックできない

```bash
F12 → Elements → Computed styles
# display: none / visibility: hidden がないか
# z-index が正しいか / pointer-events が auto か
```

---

## 10. テスト チェックリスト

### Docker 起動テスト

- [ ] `docker compose up -d` 後、`./scripts/wait-for-stack-healthy.sh` が OK で終了する
- [ ] `mysql` に全スキーマ(`lbs_*` の9個)が存在する
- [ ] `reverse-proxy` の `/nginx-health` が 200 を返す
- [ ] `https://localhost/` が表示される(自己署名証明書の警告は許容)

### 認証テスト(Keycloak)

- [ ] `/login` から Keycloak のホスト型ログイン画面へリダイレクトされる
- [ ] 正しい資格情報でログインでき、セッションが確立する
- [ ] 誤ったパスワードでは Keycloak 側でエラーになる
- [ ] 非 admin は `/users` へアクセスできない / admin はアクセスできる
- [ ] ログアウト後、保護ページで再度ログインを求められる
- [ ] 不正な JWT を付けた API 呼び出しが 401 になる

### サービス連携テスト

- [ ] サイト登録(ManagedWordPress の自動構築)が完了する
- [ ] 疎通確認が SUCCESS / FAILED を確定的に返す
- [ ] 記事公開が成功する(publishing-service → WordPress)
- [ ] `/posts` の投稿履歴に反映される(content-service)
- [ ] 生成画像が `/image-gallery` に表示される(media-service)
- [ ] 複数スキーマに想定どおり行が作成されている

### 縮退表示テスト

- [ ] 下流サービスを1つ停止しても画面が 500 にならない
- [ ] 取得できないデータが空状態として表示される
- [ ] サービス復旧後、再読み込みで正常表示に戻る

### テストデータ

- [ ] E2E 実行後に `./scripts/e2e-cleanup-test-data.sh` のドライランで残骸を確認した
- [ ] 必要に応じて `--yes` で削除し、実データが消えていないことを確認した

### UI コンポーネントテスト

- [ ] ログインページ(Keycloak)表示・入力動作
- [ ] ダッシュボード表示・レイアウト
- [ ] サイト一覧 / 投稿履歴 / プロジェクト一覧の表示
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

### 自動 E2E

- [ ] `cd web && npm run test:e2e` が完走する(手順は `docs/e2e-testing.md`)

---

## 11. テスト完了条件

✅ **すべてのチェックリスト項目が確認できた場合** → エンドツーエンド動作検証成功

---

**最終更新:** 2026-08-29(issue #588 マルチサービス構成対応)
**関連ドキュメント:** `docs/e2e-testing.md` / `docs/DOCKER_COMPOSE_ARCHITECTURE.md` / `docs/TEST_DOCUMENTATION.md`
