# Let's Blog Server 総合ドキュメント

---

## 目次

1. [セットアップガイド](#セットアップガイド)
2. [ユーザーガイド](#ユーザーガイド)
3. [開発者ガイド](#開発者ガイド)
4. [MCP 拡張ガイド](#mcp-拡張ガイド)
5. [FAQ](#faq)

---

## セットアップガイド

### 必要な環境

```bash
# macOS / Linux / Windows (WSL2)
- Docker Desktop 20.10以上
- Docker Compose 1.29以上
- Node.js 18以上 (ローカル開発時)
- 最小 20GB ディスク空容量
```

### インストール手順

#### 1. リポジトリをクローン

```bash
git clone https://github.com/tonoccho/lets-blog-server.git
cd lets-blog-server
```

#### 2. 環境変数設定

```bash
# .env ファイルを作成
cp .env.example .env

# 内容を確認・編集
cat .env

# 重要: 本番環境ではセキュアな値に変更
# - POSTGRES_PASSWORD
# - PENPOT_SECRET_KEY
# - JWT_SECRET
```

#### 3. Docker Compose で起動

```bash
# すべてのサービスを起動（初回は数分かかります）
docker compose up -d

# 起動状況確認
docker compose ps

# ログ確認（問題がないか確認）
docker compose logs -f
```

#### 4. アクセス確認

```bash
# Penpot（デザインツール）
https://localhost/penpot

# Let's Blog アプリ
http://localhost:8000

# MCP Server（AI サポート）
curl http://localhost:3000/health
```

#### 5. 初期セットアップ

```bash
# Penpot ユーザーアカウント作成
1. https://localhost/penpot にアクセス
2. "Sign Up" をクリック
3. メール・パスワード入力
4. プロジェクト作成

# Let's Blog アカウント作成
1. http://localhost:8000 にアクセス
2. "サインアップ" をクリック
3. アカウント情報入力
4. メール確認
```

### よくある起動エラー

#### ポート競合エラー

```bash
# エラー: "Address already in use"
# 原因: ポート 3000, 5432, 11434 が既に使用されている

# 解決:
lsof -i :3000   # プロセス確認
kill -9 <PID>   # プロセス終了
# または docker-compose.yml のポートマッピングを変更
```

#### ディスク容量不足

```bash
# エラー: "No space left on device"
# 原因: Docker イメージ・データが大きい（Ollama は 5GB以上）

# 解決:
docker system df          # 使用量確認
docker system prune       # 不要なイメージ削除
df -h /var/lib/docker    # ディスク使用量確認
```

#### PostgreSQL 初期化失敗

```bash
# エラー: "FATAL: password authentication failed"
# 原因: パスワードが設定されていない・不正

# 解決:
docker compose down -v   # コンテナ・ボリューム削除
docker compose up -d     # 再起動
```

---

## ユーザーガイド

### Penpot（デザイン）の使用

#### プロジェクト・ファイル作成

```
1. Penpot にログイン
2. "+ New Project" をクリック
3. プロジェクト名入力
4. "+ New File" をクリック
5. ファイル名入力
6. デザイン開始
```

#### AI デザインアシスタント プラグイン

```
1. ファイルを開く
2. Plugins → "AI Design Assistant" をクリック
3. パネルが右側に表示される

4つの機能タブ:

【Design Suggestion】
- 説明からコンポーネント自動生成
- 例: "Primary button, blue, medium"
- → AI が最適なボタンデザインを提案

【Improve Component】
- 既存コンポーネントを改善
- Focus areas: Accessibility / Responsiveness / Dark Mode
- → 改善提案を確認

【Color Palette】
- ブランドに合わせたカラーパレット生成
- WCAG AA 準拠の色を自動選定
- ダークモード対応

【Typography】
- 日本語・英語フォント提案
- 見出し・本文・UI等の最適なサイズ提案
```

### Let's Blog（ブログプラットフォーム）の使用

#### ダッシュボード

```
メインページ: http://localhost:8000/dashboard

表示内容:
- 投稿数・訪問者数・エンゲージメント
- 訪問者推移グラフ
- カテゴリ分布
- 最近の投稿一覧
```

#### サイト管理

```
サイト一覧: /dashboard/sites

操作:
- 新規サイト作成
- サイト名・URL・説明編集
- 投稿管理へ
- サイト削除
```

#### 投稿管理・編集

```
投稿一覧: /dashboard/sites/:siteId/posts

操作:
- 新規投稿作成 → 編集ページへ
- 一覧から検索・フィルター
- ステータス変更（下書き / 公開）
- 削除

編集ページ:
- タイトル・本文入力
- Featured image アップロード
- カテゴリ・タグ付け
- SEO メタデータ
- 自動保存機能
```

#### ユーザー管理（Admin）

```
ユーザー一覧: /dashboard/users

操作:
- ユーザー権限変更
- 2FA 設定
- パスワード初期化
- ユーザー削除
```

#### システム設定（Admin）

```
設定ページ: /dashboard/settings

設定項目:
- サービス名・ロゴ
- SMTP メール設定
- セキュリティ（2FA・パスワードポリシー）
- ストレージ（S3・ファイル上限）
- バックアップ設定
```

### ダークモード・言語設定

```
ユーザーメニュー → Settings

設定:
- ダークモード: ON / OFF / Auto
- 言語: 日本語 / English / 中文
- タイムゾーン
- メール通知
```

---

## 開発者ガイド

### ローカル開発セットアップ

#### リポジトリ構成

```
lets-blog-server/
├── docker-compose.yml
├── .env.example
├── 
├── docs/                          # ドキュメント
│   ├── design-system-spec.md
│   ├── component-guide.md
│   ├── page-design-guide.md
│   ├── design-tokens.json
│   ├── design-tokens-generator.md
│   ├── e2e-validation-guide.md
│   └── DOCUMENTATION.md (本ファイル)
│
├── apps/                          # 利用者が直接触るアプリケーション(#963)
│   ├── extension/                 # VSCode 拡張
│   │
│   ├── mcp-server/                # MCP サーバー（Ollama 連携）
│   │   ├── src/
│   │   │   ├── server.js
│   │   │   ├── clients/
│   │   │   ├── tools/
│   │   │   └── utils/
│   │   ├── package.json
│   │   ├── Dockerfile
│   │   └── README.md
│   │
│   ├── penpot-plugin/             # Penpot AI プラグイン
│   │   ├── src/
│   │   │   ├── plugin.ts
│   │   │   ├── ui.ts
│   │   ├── manifest.json
│   │   ├── ui.html
│   │   ├── styles.css
│   │   └── package.json
│   │
│   └── web/                       # Next.js Web App
│       ├── src/
│       │   ├── app/
│       │   ├── components/
│       │   ├── constants/
│       │   ├── hooks/
│       │   └── styles/
│       ├── tailwind.config.ts
│       ├── package.json
│       └── ...
│
├── services/                      # バックエンドの10サービス(Spring Boot、#963)
│   ├── gateway/                   # 単一入口。ルーティング・JWT検証・レート制限
│   ├── identity/                  # ユーザー・ロール・権限・プロジェクトメンバー
│   ├── project/                   # プロジェクト・サイト・SSH鍵ペア・デザイン設定
│   ├── content/                   # 投稿本文・カスタムタグ・コンテンツキャッシュ
│   ├── media/                     # 画像生成(ComfyUI/ChatGPT)・生成画像・図
│   ├── ai/                        # LLM生成(下書き/校正/タグ/セクション)・記事プラン
│   ├── publishing/                # WordPressへの公開・削除・一括管理
│   ├── analytics/                 # Google Analytics / AdSense のレポート
│   ├── platform/                  # システム設定・バックアップ・VSCode拡張の配布
│   └── log-writer/                # 監査ログ・操作ログ・フロントエンドエラーログ
│
├── packages/                      # サービス・アプリ間で共有するライブラリ(#963)
│   ├── lbs-common/                # Java 共通ライブラリ
│   └── api-client/                # OpenAPI から生成する TypeScript クライアント
│
└── infra/                         # ミドルウェアの設定(#963)
    ├── nginx/                     # Reverse Proxy
    │   └── conf.d/
    │       ├── penpot.conf
    │       └── mcp.conf
    ├── keycloak/                  # 認証基盤(Keycloak)の設定
    ├── mysql/                     # MySQL の初期化・設定
    ├── wordpress/                 # WordPress の設定
    ├── e2e-stubs/                 # E2E用スタブサービス
    ├── penpot/                    # Penpot の設定
    └── shared-host/               # 共有ホスト向けの構成
```

### コンポーネント開発

#### Button コンポーネント例

```typescript
// apps/web/src/components/ui/Button.tsx

import { ReactNode } from 'react';
import { cn } from '@/utils/cn';

interface ButtonProps {
  variant?: 'primary' | 'secondary' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  loading?: boolean;
  children: ReactNode;
  onClick?: () => void;
  className?: string;
}

export function Button({
  variant = 'primary',
  size = 'md',
  disabled = false,
  loading = false,
  children,
  className,
  ...props
}: ButtonProps) {
  const variantClasses = {
    primary: 'bg-primary-500 text-white hover:bg-primary-600',
    secondary: 'bg-secondary-500 text-white hover:bg-secondary-600',
    danger: 'bg-error-500 text-white hover:bg-error-600',
  };

  const sizeClasses = {
    sm: 'px-3 py-1.5 text-sm h-8',
    md: 'px-4 py-2 text-sm h-10',
    lg: 'px-6 py-3 text-base h-12',
  };

  return (
    <button
      className={cn(
        'font-semibold rounded-md transition-colors',
        variantClasses[variant],
        sizeClasses[size],
        disabled && 'opacity-50 cursor-not-allowed',
        className
      )}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? <Spinner size="sm" /> : children}
    </button>
  );
}
```

### ページ開発

#### ダッシュボードページ例

```typescript
// apps/web/src/app/(dashboard)/page.tsx

import { Header } from '@/components/layout/Header';
import { Sidebar } from '@/components/layout/Sidebar';
import { StatsCard } from '@/components/dashboard/StatsCard';
import { Chart } from '@/components/dashboard/Chart';
import { RecentPostsTable } from '@/components/dashboard/RecentPostsTable';

export default function DashboardPage() {
  return (
    <div className="grid grid-cols-12 gap-6">
      <Header />
      <Sidebar />
      
      <main className="col-span-9 space-y-6">
        <h1 className="text-display-2 font-bold">ダッシュボード</h1>
        
        {/* Stats Cards */}
        <div className="grid md:grid-cols-3 gap-4">
          <StatsCard
            title="投稿数"
            value="1,234"
            trend="+12%"
          />
          <StatsCard
            title="訪問者数"
            value="45.2K"
            trend="+8%"
          />
          <StatsCard
            title="エンゲージメント"
            value="3.8K"
            trend="-2%"
          />
        </div>
        
        {/* Charts */}
        <div className="grid md:grid-cols-2 gap-6">
          <Chart type="line" title="訪問者数推移" />
          <Chart type="pie" title="カテゴリ別" />
        </div>
        
        {/* Recent Posts */}
        <RecentPostsTable />
      </main>
    </div>
  );
}
```

### API 開発

#### エンドポイント例（Express）

```typescript
// apps/mcp-server/src/server.js

app.post('/api/design-suggestion', async (req, res) => {
  try {
    const { design_brief, brand_style, audience } = req.body;
    
    // Ollama へ プロンプト送信
    const suggestion = await ollamaClient.generateJSON(
      `以下のデザイン提案をしてください...\n${design_brief}`
    );
    
    res.json(suggestion);
  } catch (error) {
    logger.error('Design suggestion error:', error);
    res.status(500).json({ error: error.message });
  }
});
```

### テスト

#### Jest テスト例

```typescript
// apps/web/src/components/ui/__tests__/Button.test.tsx

import { render, screen } from '@testing-library/react';
import { Button } from '../Button';

describe('Button', () => {
  it('renders button with text', () => {
    render(<Button>Click me</Button>);
    expect(screen.getByText('Click me')).toBeInTheDocument();
  });

  it('applies variant styles', () => {
    render(<Button variant="danger">Delete</Button>);
    const button = screen.getByText('Delete');
    expect(button).toHaveClass('bg-error-500');
  });

  it('disables button when disabled prop is true', () => {
    render(<Button disabled>Disabled</Button>);
    expect(screen.getByText('Disabled')).toBeDisabled();
  });
});
```

#### E2E テスト（Playwright）

```typescript
// tests/e2e/login.spec.ts

import { test, expect } from '@playwright/test';

test.describe('Login Flow', () => {
  test('should login with valid credentials', async ({ page }) => {
    await page.goto('http://localhost:8000/auth/login');
    
    // Fill login form
    await page.fill('input[type="email"]', 'test@example.com');
    await page.fill('input[type="password"]', 'password123');
    
    // Submit
    await page.click('button:has-text("ログイン")');
    
    // Verify redirect to dashboard
    await expect(page).toHaveURL('http://localhost:8000/dashboard');
  });
});
```

### デプロイメント

#### 本番環境への デプロイ

本番専用の compose ファイルや環境変数ファイルはまだ整備されていない。リポジトリに存在する
compose ファイルは `docker-compose.yml` / `docker-compose.e2e-stubs.yml` /
`docker-compose.host-tests.yml` / `docker-compose.shared-host.yml` の4本で、いずれも本番向け
ではない。

データベース マイグレーションは web（Next.js）側に手動実行コマンドがあるわけではなく、
`identity` / `content` / `platform` / `analytics` / `ai` / `project` の各サービスが
Spring Boot 起動時に Flyway で自動的に適用する。

---

## MCP 拡張ガイド

### MCP とは

Model Context Protocol（MCP）は、AI モデル（Ollama）と アプリケーション間の 標準的な通信プロトコルです。

Let's Blog では、MCP を使用して Penpot プラグインが Ollama にデザイン提案を依頼します。

```
Penpot Plugin
    ↓ (HTTP)
MCP Server (Node.js)
    ↓ (HTTP)
Ollama (LLM)
    ↑ (JSON)
MCP Server
    ↑ (HTTP)
Penpot Plugin
```

### 新しい MCP Tool を追加

#### 1. ツール関数を作成

```typescript
// apps/mcp-server/src/tools/generateLayoutSuggestions.ts

export async function generateLayoutSuggestions(brief: string) {
  const prompt = `
    ページレイアウトを提案してください:
    
    要件: ${brief}
    
    以下の形式で JSON を返してください:
    {
      "layout_type": "2-column" | "3-column" | "hero-section" | ...,
      "sections": [
        { "name": "header", "height": "64px", "content": "..." },
        ...
      ],
      "css": "..."
    }
  `;

  const response = await ollamaClient.generateJSON(prompt);
  return response;
}
```

#### 2. API エンドポイント を作成

```typescript
// apps/mcp-server/src/server.js

app.post('/api/generate-layout', async (req, res) => {
  try {
    const { brief } = req.body;
    const layout = await generateLayoutSuggestions(brief);
    res.json(layout);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});
```

#### 3. Penpot プラグイン に追加

```typescript
// apps/penpot-plugin/src/ui.ts

async function getLayoutSuggestion() {
  const brief = document.getElementById('brief').value;
  
  const response = await fetch(`${MCP_API_BASE}/generate-layout`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ brief }),
  });
  
  const layout = await response.json();
  displayLayoutSuggestion(layout);
}
```

### Ollama プロンプト チューニング

モデルからより質の高い提案を得るためのプロンプト設計：

```typescript
// 効果的なプロンプト例

const prompt = `
あなたはプロのUX/UIデザイナーです。
Let's Blog Server のデザインシステムに従ってください。

デザインシステム:
- Primary Color: #0066CC
- Secondary Color: #FF6633
- Neutral: グレースケール
- Typography: Inter (英語), Noto Sans JP (日本語)
- Spacing: 8px グリッド
- Border Radius: 4-12px
- Shadow: elevation-1 to elevation-4

要件: ${userBrief}

提案内容:
1. コンポーネント名
2. サイズ・色・スペーシング
3. 状態（ホバー・フォーカス・無効等）
4. ダークモード対応
5. CSS 実装例

JSON 形式で返してください。
`;
```

---

## FAQ

### インストール・セットアップ

**Q: Docker がインストールされていません。何をすればいいですか？**

A: 公式サイトから Docker Desktop をダウンロード・インストールしてください：
- macOS: https://www.docker.com/products/docker-desktop
- Windows: https://www.docker.com/products/docker-desktop (WSL2 必須)
- Linux: `sudo apt install docker.io docker-compose`

---

**Q: "Address already in use" エラーが出ます**

A: ポート競合が発生しています。以下で確認・対処してください：
```bash
lsof -i :3000    # MCP Server
lsof -i :5432    # PostgreSQL
lsof -i :11434   # Ollama
```
既存プロセスを終了するか、docker-compose.yml でポートマッピングを変更してください。

---

**Q: Ollama のモデルダウンロードが遅いです**

A: 初回ダウンロード時に 5GB 以上必要です。
- ネットワーク接続を確認
- ダウンロード完了を待つ（数十分）
- `docker compose logs ollama` でダウンロード進捗確認

---

### Penpot 使用方法

**Q: プラグインがPenpotで読み込まれません**

A: 以下を確認してください：
1. `apps/penpot-plugin/manifest.json` が存在するか
2. `npm run build` でビルド済みか
3. `plugin.js` が dist/ に存在するか
4. ブラウザキャッシュをクリア（Ctrl+Shift+Delete）

---

**Q: AI 提案が表示されません**

A: MCP Server が起動しているか確認：
```bash
curl http://localhost:3000/health
# 期待: {"status":"ok"}

# エラーの場合:
docker compose logs mcp-penpot
```

---

### Let's Blog 使用方法

**Q: 投稿を公開できません**

A: 以下を確認：
1. ログイン状態か
2. サイトが選択されているか
3. タイトル・本文が入力されているか
4. ステータスが「公開」に設定されているか

---

**Q: ダークモードが自動で切り替わりません**

A: OS 設定を確認：
- macOS: システム環境設定 → 一般 → 外観 → ダーク
- Windows: 設定 → 個人用設定 → 色 → ダーク
- Linux: GNOME Settings → Appearance → Dark

ブラウザの設定で自動がオンになっているか確認：
```javascript
// DevTools Console
window.matchMedia('(prefers-color-scheme: dark)').matches
// true = Dark Mode
```

---

### 開発・カスタマイズ

**Q: 新しい UI コンポーネントを作成したいです**

A: 以下の手順で作成してください：

1. `docs/design-tokens.json` で色・タイポを確認
2. `apps/web/src/components/ui/YourComponent.tsx` を作成
3. Tailwind CSS クラスを使用（CSS-in-JS 不推奨）
4. Storybook に追加（オプション）
5. PR でレビュー依頼

---

**Q: Ollama の別のモデルを使いたいです**

A: `docker-compose.yml` で変更：

```yaml
ollama:
  environment:
    - OLLAMA_MODEL=mistral:7b
    # または
    - OLLAMA_MODEL=llama2:13b
```

その後、モデルをプル：
```bash
docker compose exec ollama ollama pull mistral:7b
```

---

### パフォーマンス

**Q: ページの読み込みが遅いです**

A: 以下を確認：

1. Lighthouse スコア確認
```bash
DevTools → Lighthouse → Generate report
```

2. ボトルネック確認
```bash
DevTools → Network → 遅いリクエストを確認
```

3. コード分割を有効化
```typescript
// 動的インポート
import dynamic from 'next/dynamic';
const HeavyComponent = dynamic(() => import('./HeavyComponent'), {
  loading: () => <div>Loading...</div>,
});
```

---

**Q: CSS が大きくなりすぎています**

A: Tailwind CSS の PurgeCSS を確認：

```typescript
// tailwind.config.ts
module.exports = {
  content: [
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  // → 使用されていないスタイルが削除される
};
```

ビルド後のファイルサイズ確認：
```bash
npm run build
ls -lh .next/static/css/
```

---

### セキュリティ

**Q: パスワードを忘れました**

A: パスワードリセットの手順：

1. ログインページで "パスワードをリセット" をクリック
2. メールアドレス入力
3. 受け取ったリセットリンクをクリック
4. 新しいパスワード設定

メール受信できない場合は、Admin に連絡してください。

---

**Q: 2FA（二段階認証）を設定したいです**

A: ユーザー設定ページ：

1. ユーザーメニュー → Settings
2. Security セクション
3. "Enable 2FA" をクリック
4. QR コード をスキャン（Google Authenticator等）
5. 確認コード入力

---

### トラブルシューティング

**Q: エラーログが表示されます**

A: ログを確認して問題を特定：

```bash
# Web app ログ
docker compose logs -f web

# MCP Server ログ
docker compose logs -f mcp-penpot

# Penpot ログ
docker compose logs -f penpot

# Ollama ログ
docker compose logs -f ollama
```

---

**Q: Docker コンテナが起動しません**

A: ステップバイステップで確認：

```bash
# 1. エラー確認
docker compose logs <service-name>

# 2. ポート確認
lsof -i :<port>

# 3. コンテナを再起動
docker compose restart <service-name>

# 4. 完全に削除して再起動
docker compose down -v
docker compose up -d
```

---

## サポート・貢献

### バグ報告

GitLab の Issue で報告してください(`bug` ラベル)：
https://server.tonoccho.local/gitlab/seiji/lets_blog_server/-/issues

テンプレート：
```
タイトル: [Bug] <簡潔な説明>

説明: <詳細な説明>
再現手順: <ステップバイステップ>
期待: <期待される動作>
実際: <実際の動作>

環境:
- OS: macOS / Windows / Linux
- Docker Version: ...
- Browser: ...
```

### 機能リクエスト

GitLab の Issue で提案してください(`enhancement` ラベル。質問は `question` ラベル)：
https://server.tonoccho.local/gitlab/seiji/lets_blog_server/-/issues

---

## ライセンス

MIT License - 詳細は LICENSE ファイルを参照

---

**最終更新:** 2024-08-07  
**バージョン:** 1.0
