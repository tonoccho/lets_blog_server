# Let's Blog ページデザインガイド

## 概要

本ドキュメントは Let's Blog Server の実際のページレイアウト・ユーザーフロー・コンポーネント構成を定義します。

対象：Web 管理画面（Next.js）全ページ

各ページについて以下を記載：
- ページ構成・レイアウト
- コンポーネント配置
- ユーザーフロー・インタラクション
- レスポンシブ対応
- Penpot フレーム定義
- React 実装ガイド

---

## 1. 認証フロー

### 1.1 ログインページ

**URL:** `/auth/login`  
**対象ユーザー:** 未認証ユーザー  
**デバイス:** デスクトップ・モバイル両対応

#### ページレイアウト

```
┌─────────────────────────────────────────┐
│           Left: Branding / Hero         │  デスクトップのみ（50%）
│  ・大きなタイトル・スローガン           │  モバイル: 非表示
│  ・ブランド画像                         │
├──────────────────────┬──────────────────┤
│  Right: Login Form   │                  │
│  ・Logo              │                  │
│  ・"ログイン"タイトル│                  │
│  ・メール入力        │                  │
│  ・パスワード入力    │                  │
│  ・"ログイン"ボタン  │                  │
│  ・"サインアップ"    │                  │
│  ・"パスワード忘れ"  │                  │
└──────────────────────┴──────────────────┘
```

#### コンポーネント構成

```
Container (max-width: 1280px)
├─ Flex (gap: 24px)
│  ├─ Left Section (50% / Desktop only)
│  │  ├─ Logo (small)
│  │  ├─ Heading (Display 1)
│  │  │  "シンプルで強力なブログプラットフォーム"
│  │  ├─ Paragraph (Body Large)
│  │  │  説明文
│  │  └─ Features (List)
│  │     ・複数の機能紹介
│  │
│  └─ Right Section (50% / Full on mobile)
│     ├─ Card (p: 24px)
│     │  ├─ Logo (centered)
│     │  ├─ Heading (H3) "ログイン"
│     │  ├─ Form (space-y: 16px)
│     │  │  ├─ Input (label: "メールアドレス")
│     │  │  ├─ Input (label: "パスワード", type: password)
│     │  │  ├─ Checkbox + Label
│     │  │  │  "ログイン状態を保持する"
│     │  │  └─ Button (Primary, Large, Full Width)
│     │  │     "ログイン"
│     │  │
│     │  └─ Divider + Alternative Methods
│     │     ├─ Text "または"
│     │     └─ Google OAuth Button (outline)
│     │
│     └─ Footer Links (text-center, text-caption)
│        ├─ Link "アカウントを作成"
│        ├─ Link "パスワードをリセット"
│        └─ Link "ヘルプ"
```

#### 状態管理

```
States:
├─ Default: 通常表示
├─ Loading: 送信ボタン disabled、spinner 表示
├─ Error: 上部に alert 表示
│  └─ メッセージ: "メールアドレスまたはパスワードが異なります"
└─ Success: ダッシュボードにリダイレクト（自動）
```

#### レスポンシブ

```
Desktop (≥1024px):
├─ 2カラムレイアウト（50:50）
├─ フォント: 見出し 32px / 本文 16px
└─ カード幅: 400px

Tablet (640px - 1024px):
├─ 1カラム（スタック）
├─ 左セクション: 非表示
└─ フォント: 見出し 28px / 本文 14px

Mobile (< 640px):
├─ 全幅（padding: 16px）
├─ フォント: 見出し 24px / 本文 14px
└─ ボタン: 全幅
```

#### Tailwind実装例

```html
<div class="min-h-screen bg-gradient-to-br from-primary-50 to-white
            dark:from-neutral-900 dark:to-neutral-800
            flex items-center justify-center p-4">
  
  <div class="max-w-6xl w-full grid md:grid-cols-2 gap-12">
    
    <!-- Left Section (Desktop Only) -->
    <div class="hidden md:flex flex-col justify-center gap-8">
      <div>
        <div class="w-10 h-10 bg-primary-500 rounded-lg mb-6"></div>
        <h1 class="text-display-1 font-bold text-neutral-900 dark:text-white mb-4">
          シンプルで強力なブログプラットフォーム
        </h1>
        <p class="text-body-lg text-neutral-700 dark:text-neutral-300">
          Let's Blog なら、誰でも簡単に美しいブログを作成・運営できます
        </p>
      </div>
      
      <ul class="space-y-4">
        <li class="flex gap-3">
          <span class="text-success-500 font-bold">✓</span>
          <span class="text-body text-neutral-700">シンプルな操作性</span>
        </li>
        <li class="flex gap-3">
          <span class="text-success-500 font-bold">✓</span>
          <span class="text-body text-neutral-700">AI デザインアシスタント</span>
        </li>
        <li class="flex gap-3">
          <span class="text-success-500 font-bold">✓</span>
          <span class="text-body text-neutral-700">複数サイト管理</span>
        </li>
      </ul>
    </div>
    
    <!-- Right Section (Login Form) -->
    <div class="flex items-center">
      <div class="w-full bg-white dark:bg-neutral-800 rounded-lg shadow-lg p-8">
        
        <!-- Logo -->
        <div class="text-center mb-8">
          <div class="inline-block w-12 h-12 bg-primary-500 rounded-lg mb-4"></div>
          <h2 class="text-h3 font-bold text-neutral-900 dark:text-white">
            ログイン
          </h2>
        </div>
        
        <!-- Login Form -->
        <form class="space-y-4" novalidate>
          
          <!-- Email Input -->
          <div>
            <label for="email" class="text-label text-primary block mb-2">
              メールアドレス
            </label>
            <input
              id="email"
              type="email"
              placeholder="you@example.com"
              class="w-full px-3 py-2 border border-neutral-300 rounded-md
                     focus:border-primary-500 focus:ring-2 focus:ring-primary-100
                     dark:bg-neutral-900 dark:border-neutral-600"
              required
            />
          </div>
          
          <!-- Password Input -->
          <div>
            <label for="password" class="text-label text-primary block mb-2">
              パスワード
            </label>
            <input
              id="password"
              type="password"
              placeholder="••••••••"
              class="w-full px-3 py-2 border border-neutral-300 rounded-md
                     focus:border-primary-500 focus:ring-2 focus:ring-primary-100
                     dark:bg-neutral-900 dark:border-neutral-600"
              required
            />
          </div>
          
          <!-- Remember Me -->
          <div class="flex items-center gap-2">
            <input id="remember" type="checkbox" class="w-5 h-5" />
            <label for="remember" class="text-body text-neutral-700">
              ログイン状態を保持する
            </label>
          </div>
          
          <!-- Submit Button -->
          <button class="w-full px-4 py-3 bg-primary-500 text-white font-semibold
                         rounded-md hover:bg-primary-600 transition-colors mt-6">
            ログイン
          </button>
        </form>
        
        <!-- Divider -->
        <div class="flex items-center gap-3 my-6">
          <div class="flex-1 border-t border-neutral-200 dark:border-neutral-700"></div>
          <span class="text-caption text-neutral-500">または</span>
          <div class="flex-1 border-t border-neutral-200 dark:border-neutral-700"></div>
        </div>
        
        <!-- OAuth -->
        <button class="w-full px-4 py-2 border border-neutral-300 rounded-md
                       hover:bg-neutral-50 dark:border-neutral-600 dark:hover:bg-neutral-700
                       flex items-center justify-center gap-2 font-medium">
          <svg class="w-5 h-5" viewBox="0 0 24 24"><!-- Google logo --></svg>
          Google で続行
        </button>
        
        <!-- Footer Links -->
        <div class="text-center mt-6 space-y-2 text-caption">
          <p>
            アカウントがない？
            <a href="/auth/signup" class="text-primary-600 hover:underline">
              サインアップ
            </a>
          </p>
          <p>
            <a href="/auth/forgot-password" class="text-primary-600 hover:underline">
              パスワードをリセット
            </a>
          </p>
        </div>
      </div>
    </div>
  </div>
</div>
```

#### React実装ガイド

```typescript
// pages/auth/login.tsx

import { useState } from 'react';
import { useRouter } from 'next/router';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';

export default function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    
    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });

      if (!response.ok) {
        throw new Error('ログインに失敗しました');
      }

      router.push('/dashboard');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'エラーが発生しました');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-primary-50 to-white">
      <form onSubmit={handleSubmit}>
        {error && <Alert variant="error">{error}</Alert>}
        <Input
          label="メールアドレス"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <Input
          label="パスワード"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <Button loading={loading}>ログイン</Button>
      </form>
    </div>
  );
}
```

---

### 1.2 サインアップページ

**URL:** `/auth/signup`  
**対象ユーザー:** 新規ユーザー  
**デバイス:** デスクトップ・モバイル両対応

#### ページレイアウト

ログインページと同じ 2カラムレイアウト

#### コンポーネント構成

```
Right Section: Signup Form
├─ Title: "アカウント作成"
├─ Form Fields:
│  ├─ Input (label: "フルネーム")
│  ├─ Input (label: "メールアドレス")
│  ├─ Input (label: "パスワード") - 最小8文字表示
│  ├─ Input (label: "パスワード確認")
│  ├─ Checkbox + Label "利用規約に同意"
│  └─ Button (Primary, Full Width) "アカウント作成"
│
├─ Info Alert (mt: 16px)
│  "登録時に確認メールが送信されます"
│
└─ Footer Links
   "アカウントをお持ち? ログイン"
```

#### バリデーション表示

```
入力中:
├─ メール: リアルタイムバリデーション
├─ パスワード: 強度インジケータ
│  └─ 弱い（赤）/ 中程度（黄）/ 強い（緑）
└─ 確認: 不一致で error state

送信後:
├─ Error: 上部に Alert 表示
└─ Success: 確認メール送信ページへリダイレクト
```

---

### 1.3 パスワードリセット

**URL:** `/auth/forgot-password`  
**フロー:** メール入力 → リンク送信確認 → 新パスワード設定

#### 3ステップフロー

```
Step 1: メール入力
├─ "メールアドレスを入力してください"
└─ Input + Button (Primary) "リセットリンクを送信"

Step 2: 確認画面
├─ Alert (Success) "確認メールを送信しました"
├─ "メールを確認して..."
└─ Button "ログインページへ戻る"

Step 3: リセット（メール内のリンク）
├─ Input (label: "新しいパスワード")
├─ Input (label: "パスワード確認")
└─ Button "パスワードをリセット"
```

---

## 2. ダッシュボード

### 2.1 ダッシュボードメインページ

**URL:** `/dashboard`  
**対象ユーザー:** 認証済みユーザー全員  
**権限:** User 以上  
**デバイス:** デスクトップ・タブレット優先

#### ページレイアウト

```
┌──────────────────────────────────────────────────────────────┐
│  Header (sticky)                                             │
│  ├─ ロゴ                                                    │
│  ├─ ナビメニュー (ダッシュボード / サイト / ...)            │
│  └─ ユーザーメニュー                                        │
├──────────────────────────┬──────────────────────────────────┤
│ Sidebar (sticky)         │ Main Content                     │
│ ├─ ナビメニュー        │ ┌──────────────────────────────┐ │
│ └─ ユーザー情報        │ │ Heading: ダッシュボード      │ │
│                          │ │                            │ │
│                          │ │ Stats Grid (3カラム)       │ │
│                          │ │ ├─ Card: 投稿数            │ │
│                          │ │ ├─ Card: 訪問者数          │ │
│                          │ │ └─ Card: エンゲージメント  │ │
│                          │ │                            │ │
│                          │ │ Charts (2カラム)           │ │
│                          │ │ ├─ Line Chart: 訪問者数推移│ │
│                          │ │ └─ Pie Chart: カテゴリ分類│ │
│                          │ │                            │ │
│                          │ │ Recent Items               │ │
│                          │ │ └─ Table: 最近の投稿      │ │
│                          │ │                            │ │
│                          │ └──────────────────────────────┘ │
└──────────────────────────┴──────────────────────────────────┘
```

#### コンポーネント構成

```
Layout (Grid)
├─ Header (position: sticky)
│  └─ [共通 Header コンポーネント]
│
└─ Main (Grid, gap: 16px)
   ├─ Sidebar (w: 256px, position: sticky)
   │  └─ [共通 Sidebar コンポーネント]
   │
   └─ Content (flex-1)
      ├─ Section 1: ヘッダー
      │  ├─ Heading (H1) "ダッシュボード"
      │  └─ Button (Outline, sm) "データをリフレッシュ"
      │
      ├─ Section 2: 統計情報（Stats Cards）
      │  └─ Grid (3カラム / 2カラム on tablet / 1カラム on mobile)
      │     ├─ Card (with number, label, trend)
      │     │  ├─ Number: "1,234"
      │     │  ├─ Label: "投稿数"
      │     │  └─ Badge: "+12% from last month"
      │     ├─ Card (訪問者数)
      │     └─ Card (エンゲージメント)
      │
      ├─ Section 3: グラフ（Charts）
      │  └─ Grid (2カラム / 1カラム on mobile)
      │     ├─ Card (Title: 訪問者数推移)
      │     │  └─ LineChart (6ヶ月のデータ)
      │     └─ Card (Title: カテゴリ別)
      │        └─ PieChart
      │
      └─ Section 4: 最近の投稿（Table）
         ├─ Card
         │  ├─ Header: 
         │  │  ├─ Heading (H3) "最近の投稿"
         │  │  └─ Link "すべて表示 →"
         │  └─ Table
         │     ├─ Head: [タイトル | 著者 | 公開日 | ステータス | アクション]
         │     └─ Body (max 5 rows)
         │        ├─ Row + Hover effect
         │        └─ Actions: [編集 | 削除]
```

#### Tailwind実装例

```html
<div class="grid grid-cols-1 md:grid-cols-12 gap-6">
  
  <!-- Sidebar -->
  <aside class="hidden md:block md:col-span-3">
    <!-- Sidebar Component -->
  </aside>
  
  <!-- Main Content -->
  <main class="md:col-span-9 space-y-6">
    
    <!-- Header -->
    <div class="flex items-center justify-between">
      <h1 class="text-display-2 font-bold">ダッシュボード</h1>
      <button class="px-3 py-2 text-sm border rounded hover:bg-neutral-50">
        🔄 リフレッシュ
      </button>
    </div>
    
    <!-- Stats Grid -->
    <div class="grid md:grid-cols-3 gap-4">
      <div class="bg-white rounded-lg p-6 border border-neutral-200 shadow">
        <p class="text-caption text-neutral-600">投稿数</p>
        <p class="text-h2 font-bold mt-2">1,234</p>
        <p class="text-caption text-success-600 mt-1">+12% from last month</p>
      </div>
      
      <div class="bg-white rounded-lg p-6 border border-neutral-200 shadow">
        <p class="text-caption text-neutral-600">訪問者数</p>
        <p class="text-h2 font-bold mt-2">45.2K</p>
        <p class="text-caption text-success-600 mt-1">+8% from last week</p>
      </div>
      
      <div class="bg-white rounded-lg p-6 border border-neutral-200 shadow">
        <p class="text-caption text-neutral-600">エンゲージメント</p>
        <p class="text-h2 font-bold mt-2">3.8K</p>
        <p class="text-caption text-warning-600 mt-1">-2% from last month</p>
      </div>
    </div>
    
    <!-- Charts -->
    <div class="grid md:grid-cols-2 gap-6">
      <div class="bg-white rounded-lg p-6 border border-neutral-200 shadow">
        <h3 class="text-h4 font-bold mb-4">訪問者数推移</h3>
        <!-- Chart Component -->
      </div>
      
      <div class="bg-white rounded-lg p-6 border border-neutral-200 shadow">
        <h3 class="text-h4 font-bold mb-4">カテゴリ別</h3>
        <!-- Chart Component -->
      </div>
    </div>
    
    <!-- Recent Posts Table -->
    <div class="bg-white rounded-lg border border-neutral-200 shadow overflow-hidden">
      <div class="px-6 py-4 border-b border-neutral-200 flex justify-between items-center">
        <h3 class="text-h4 font-bold">最近の投稿</h3>
        <a href="/dashboard/posts" class="text-primary-600 text-sm hover:underline">
          すべて表示 →
        </a>
      </div>
      
      <table class="w-full">
        <thead>
          <tr class="bg-neutral-50 border-b">
            <th class="px-6 py-3 text-left text-label">タイトル</th>
            <th class="px-6 py-3 text-left text-label">著者</th>
            <th class="px-6 py-3 text-left text-label">公開日</th>
            <th class="px-6 py-3 text-left text-label">ステータス</th>
            <th class="px-6 py-3 text-label">アクション</th>
          </tr>
        </thead>
        <tbody>
          <tr class="border-b hover:bg-neutral-50 transition-colors">
            <td class="px-6 py-4 text-body font-medium">React 19 解説</td>
            <td class="px-6 py-4 text-body">著者名</td>
            <td class="px-6 py-4 text-body-sm text-neutral-600">2024-08-07</td>
            <td class="px-6 py-4">
              <span class="inline-flex px-2 py-1 bg-success-100 text-success-700 
                           text-caption rounded">
                公開済み
              </span>
            </td>
            <td class="px-6 py-4 text-center">
              <button class="text-primary-600 hover:underline text-sm">編集</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </main>
</div>
```

---

### 2.2 ダッシュボード統計ページ

**URL:** `/dashboard/analytics`  
**対象ユーザー:** Site Owner / Admin  
**コンテンツ:** 詳細な分析データ・レポート

#### ページレイアウト

```
Layout: 2カラム（Sidebar + Content）

Content Area:
├─ Filters (top)
│  ├─ Date Range Picker
│  ├─ Site Selector
│  └─ Button "フィルター適用"
│
├─ Charts (full-width)
│  ├─ Line Chart (訪問者推移 / 30日)
│  ├─ Bar Chart (トップページ)
│  ├─ Pie Chart (トラフィックソース)
│  └─ Table (詳細データ)
│
└─ Export
   └─ Button "レポートをエクスポート (PDF)"
```

---

## 3. サイト管理

### 3.1 サイト一覧ページ

**URL:** `/dashboard/sites`  
**対象ユーザー:** User 以上  
**コンテンツ:** 管理中のサイト一覧

#### ページレイアウト

```
┌───────────────────────────────────────────┐
│ Header (with search & action)             │
│ ├─ Heading: "サイト管理"                  │
│ ├─ Search Input                           │
│ └─ Button (Primary) "新規サイト作成"      │
│                                           │
├───────────────────────────────────────────┤
│ Sites Grid (2カラム / 1カラム on mobile) │
│                                           │
│ ┌───────────────┐ ┌───────────────┐      │
│ │ Site Card 1   │ │ Site Card 2   │      │
│ │ ・画像        │ │ ・画像        │      │
│ │ ・タイトル    │ │ ・タイトル    │      │
│ │ ・URL         │ │ ・URL         │      │
│ │ ・投稿数      │ │ ・投稿数      │      │
│ │ ・Menu [...]  │ │ ・Menu [...]  │      │
│ └───────────────┘ └───────────────┘      │
│                                           │
│ ┌───────────────┐ ┌───────────────┐      │
│ │ Site Card 3   │ │ Site Card 4   │      │
│ └───────────────┘ └───────────────┘      │
│                                           │
└───────────────────────────────────────────┘
```

#### コンポーネント構成

```
Layout (flex-col)
├─ Header Section (sticky)
│  ├─ Flex (justify-between, items-center)
│  │  ├─ Heading (H1) "サイト管理"
│  │  └─ Button (Primary) "新規サイト作成"
│  │
│  └─ Search Bar (mt: 16px)
│     └─ Input (placeholder: "サイト名で検索...")
│
├─ Filters (optional)
│  └─ Tabs: [すべて | 公開中 | 下書き]
│
└─ Sites Grid
   └─ Grid (2カラム / 1カラム on mobile, gap: 24px)
      └─ Card (with image, clickable)
         ├─ Image (h: 180px, object-cover)
         ├─ Content (p: 16px)
         │  ├─ Heading (H4) "サイト名"
         │  ├─ Text (caption) "example.com"
         │  ├─ Text (caption) "投稿数: 42"
         │  └─ Text (caption) "最終更新: 2024-08-07"
         │
         └─ Actions (footer)
            ├─ Button (sm, outline) "開く"
            └─ Dropdown Menu
               ├─ "編集"
               ├─ "設定"
               ├─ "訪問者分析"
               ├─ Divider
               └─ "削除" (danger)
```

#### Tailwind実装例

```html
<main class="space-y-6">
  
  <!-- Header with Actions -->
  <div class="flex items-center justify-between">
    <h1 class="text-display-2 font-bold">サイト管理</h1>
    <button class="px-4 py-2 bg-primary-500 text-white rounded-md
                   hover:bg-primary-600 font-medium">
      + 新規サイト作成
    </button>
  </div>
  
  <!-- Search -->
  <input
    type="text"
    placeholder="サイト名で検索..."
    class="w-full px-4 py-2 border border-neutral-300 rounded-md"
  />
  
  <!-- Sites Grid -->
  <div class="grid md:grid-cols-2 gap-6">
    
    <!-- Site Card -->
    <div class="bg-white rounded-lg shadow border border-neutral-200
                hover:shadow-md transition-shadow cursor-pointer">
      
      <!-- Image -->
      <div class="w-full h-48 bg-neutral-200 overflow-hidden rounded-t-lg">
        <img src="site-thumbnail.jpg" alt="サイト画像"
             class="w-full h-full object-cover" />
      </div>
      
      <!-- Content -->
      <div class="p-4 space-y-2">
        <h3 class="text-h4 font-bold text-neutral-900">サイト名</h3>
        <p class="text-caption text-neutral-600">example.com</p>
        <p class="text-caption text-neutral-600">投稿数: 42</p>
        <p class="text-caption text-neutral-600">最終更新: 2024-08-07</p>
      </div>
      
      <!-- Actions -->
      <div class="px-4 py-3 border-t border-neutral-200 flex gap-2">
        <button class="flex-1 px-2 py-1 text-sm text-primary-600
                       border border-primary-600 rounded hover:bg-primary-50">
          開く
        </button>
        <button class="px-2 py-1 text-sm text-neutral-600 border rounded hover:bg-neutral-100">
          ⋮
        </button>
      </div>
    </div>
  </div>
</main>
```

---

### 3.2 サイト設定ページ

**URL:** `/dashboard/sites/:siteId/settings`  
**対象ユーザー:** Site Owner  
**コンテンツ:** サイト名・URL・テーマ・SEO等の設定

#### ページレイアウト

```
Layout: Tabs + Content

Tabs:
├─ 一般設定
├─ 公開設定
├─ デザイン
├─ SEO / メタデータ
└─ 危険設定

Content (各タブ):
└─ Form with Input/Select/Textarea
   ├─ Submit Button (Primary)
   └─ Cancel Button (Secondary)
```

#### コンポーネント構成（各タブ）

```
Tab: 一般設定

Form (space-y: 24px)
├─ Input
│  ├─ label: "サイト名"
│  ├─ value: "My Blog"
│  └─ helper: "表示されるタイトル"
│
├─ Input
│  ├─ label: "サイトURL"
│  ├─ prefix: "https://"
│  ├─ value: "example.com"
│  └─ helper: "購入済みドメインを指定"
│
├─ Textarea
│  ├─ label: "説明"
│  └─ rows: 3
│
├─ Select
│  ├─ label: "言語"
│  ├─ options: [日本語 | English | 中国語]
│  └─ value: "日本語"
│
└─ Actions
   ├─ Button (Primary) "保存"
   └─ Button (Secondary) "キャンセル"
```

#### Toast/Alert 表示

```
Save Success:
├─ Toast (bottom-right, auto-hide 3s)
├─ Message: "設定を保存しました"
└─ Icon: ✓

Save Error:
├─ Alert (top)
├─ Message: "保存に失敗しました: ..."
└─ Icon: ⚠
```

---

## 4. 投稿管理

### 4.1 投稿一覧ページ

**URL:** `/dashboard/sites/:siteId/posts`  
**対象ユーザー:** User 以上  
**コンテンツ:** サイト内の投稿一覧（表 / グリッド）

#### ページレイアウト

```
┌─────────────────────────────────────────────┐
│ Header with Actions                         │
│ ├─ Heading + View Toggle (Table/Grid)     │
│ ├─ Search + Filters                        │
│ └─ "新規投稿" ボタン                       │
│                                             │
├─────────────────────────────────────────────┤
│ Toolbar                                     │
│ ├─ Checkbox (select all)                   │
│ ├─ Sort Selector (最新順 / 人気順 / ...)   │
│ └─ Batch Actions (visible if selected)    │
│    ├─ Button "一括公開"                   │
│    ├─ Button "一括削除"                   │
│    └─ selected count: "5件選択"           │
│                                             │
├─────────────────────────────────────────────┤
│ Table View (Default)                       │
│                                             │
│ ┌──┬───────────┬─────┬──────────┬────────┐ │
│ │□ │ タイトル  │ 著者 │  公開日  │アクション│ │
│ ├──┼───────────┼─────┼──────────┼────────┤ │
│ │□ │ React 19  │名前  │2024-08-07│ ✎ 🗑  │ │
│ │□ │ ...       │...  │...       │...    │ │
│ └──┴───────────┴─────┴──────────┴────────┘ │
│                                             │
│ Pagination                                  │
│ (1-10 of 42)                               │
│                                             │
└─────────────────────────────────────────────┘
```

#### コンポーネント構成

```
Layout (flex-col, gap: 16px)
├─ Header
│  ├─ Flex (justify-between)
│  │  ├─ Heading (H1) "投稿管理"
│  │  ├─ Flex (gap: 8px)
│  │  │  ├─ Button (icon: table, toggle) "表"
│  │  │  └─ Button (icon: grid, toggle) "グリッド"
│  │  └─ Button (Primary) "+ 新規投稿"
│  │
│  └─ Search + Filters (row, gap: 12px)
│     ├─ Input (search: "タイトル検索")
│     ├─ Select (status: すべて / 公開 / 下書き)
│     ├─ Select (category: すべて / ...)
│     └─ Button "フィルター"
│
├─ Toolbar (sticky, bg: neutral-50)
│  ├─ Checkbox (select-all)
│  ├─ Text (body-sm) "5件選択"
│  ├─ Select (sort: 最新順 / 人気順)
│  └─ Flex (hidden if no selection)
│     ├─ Button "一括公開"
│     └─ Button (danger) "一括削除"
│
├─ Table
│  ├─ Header Row (sticky)
│  │  └─ [Checkbox | タイトル | 著者 | 公開日 | ステータス | アクション]
│  │
│  └─ Body Rows (max-height: 70vh, overflow-y: auto)
│     ├─ Row (hover: bg-neutral-50)
│     │  ├─ Checkbox
│     │  ├─ Link (タイトル → 編集ページ)
│     │  ├─ Text (著者名)
│     │  ├─ Text (公開日)
│     │  ├─ Badge (status: 公開 / 下書き)
│     │  └─ Dropdown Menu
│     │     ├─ "編集"
│     │     ├─ "プレビュー"
│     │     ├─ "統計"
│     │     ├─ Divider
│     │     └─ "削除" (danger)
│     │
│     └─ ... (multiple rows)
│
└─ Pagination
   ├─ Text "1 - 10 件を表示（全 42 件）"
   └─ Nav (Previous | Page Numbers | Next)
```

#### Tailwind実装例

```html
<main class="space-y-6">
  
  <!-- Header -->
  <div class="flex items-center justify-between">
    <h1 class="text-display-2 font-bold">投稿管理</h1>
    <div class="flex gap-2">
      <button class="px-3 py-2 rounded text-primary-600 hover:bg-primary-50">
        📊 表
      </button>
      <button class="px-3 py-2 rounded text-neutral-600 hover:bg-neutral-100">
        📱 グリッド
      </button>
      <button class="px-4 py-2 bg-primary-500 text-white rounded hover:bg-primary-600">
        + 新規投稿
      </button>
    </div>
  </div>
  
  <!-- Search & Filters -->
  <div class="flex gap-3 items-center">
    <input
      type="text"
      placeholder="タイトルで検索..."
      class="flex-1 px-3 py-2 border border-neutral-300 rounded-md"
    />
    <select class="px-3 py-2 border border-neutral-300 rounded-md">
      <option>すべて</option>
      <option>公開</option>
      <option>下書き</option>
    </select>
    <button class="px-3 py-2 text-primary-600 font-medium">
      フィルター
    </button>
  </div>
  
  <!-- Table -->
  <div class="bg-white rounded-lg border overflow-hidden">
    <table class="w-full">
      <thead class="bg-neutral-50 border-b">
        <tr>
          <th class="px-4 py-3 text-left">
            <input type="checkbox" />
          </th>
          <th class="px-4 py-3 text-left text-label">タイトル</th>
          <th class="px-4 py-3 text-left text-label">著者</th>
          <th class="px-4 py-3 text-left text-label">公開日</th>
          <th class="px-4 py-3 text-label">ステータス</th>
          <th class="px-4 py-3 text-label">アクション</th>
        </tr>
      </thead>
      <tbody>
        <tr class="border-b hover:bg-neutral-50">
          <td class="px-4 py-3"><input type="checkbox" /></td>
          <td class="px-4 py-3">
            <a href="#" class="text-primary-600 hover:underline font-medium">
              React 19 新機能解説
            </a>
          </td>
          <td class="px-4 py-3 text-body">著者名</td>
          <td class="px-4 py-3 text-body-sm text-neutral-600">2024-08-07</td>
          <td class="px-4 py-3">
            <span class="px-2 py-1 bg-success-100 text-success-700 text-caption rounded">
              公開
            </span>
          </td>
          <td class="px-4 py-3">
            <button class="text-neutral-600 hover:text-neutral-900">⋮</button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
  
  <!-- Pagination -->
  <div class="flex justify-between items-center">
    <p class="text-caption text-neutral-600">
      1 - 10 件を表示（全 42 件）
    </p>
    <div class="flex gap-1">
      <button class="px-3 py-2 border rounded hover:bg-neutral-50">← 前へ</button>
      <button class="px-3 py-2 bg-primary-500 text-white rounded">1</button>
      <button class="px-3 py-2 border rounded hover:bg-neutral-50">2</button>
      <button class="px-3 py-2 border rounded hover:bg-neutral-50">3</button>
      <button class="px-3 py-2 border rounded hover:bg-neutral-50">次へ →</button>
    </div>
  </div>
</main>
```

---

### 4.2 投稿編集ページ

**URL:** `/dashboard/sites/:siteId/posts/:postId/edit`  
**対象ユーザー:** 投稿作成者 / Site Owner  
**コンテンツ:** 投稿本文・メタデータ編集

#### ページレイアウト

```
┌─────────────────────────────────────────┐
│ Header (sticky)                         │
│ ├─ Heading: "投稿編集"                  │
│ ├─ Status Badge: 下書き / 公開         │
│ └─ Actions: [プレビュー | 保存 | 公開] │
│                                         │
├─────────────────────────────────────────┤
│ 2カラムレイアウト                       │
│                                         │
│ Left (70%): Editor                     │
│ ├─ Title Input                         │
│ ├─ Editor (RichText / Markdown)        │
│ ├─ Featured Image Upload               │
│ └─ Auto-save indicator                 │
│                                         │
│ Right (30%): Sidebar                   │
│ ├─ Publish Section                     │
│ │  ├─ Status selector                 │
│ │  ├─ Publish date picker              │
│ │  └─ Button [公開]                   │
│ │                                      │
│ ├─ Categories                          │
│ │  └─ Checkbox list                   │
│ │                                      │
│ ├─ Tags                                │
│ │  └─ Tag input                       │
│ │                                      │
│ └─ SEO                                 │
│    ├─ slug                            │
│    └─ meta-description               │
│                                         │
└─────────────────────────────────────────┘
```

#### コンポーネント構成

```
Layout (Grid, 2カラム)
├─ Header (sticky, full-width)
│  └─ Flex (justify-between)
│     ├─ Flex (items-center, gap: 16px)
│     │  ├─ Heading (H2) "投稿編集"
│     │  └─ Badge (status)
│     └─ Flex (gap: 8px)
│        ├─ Button (outline) "プレビュー"
│        ├─ Button "自動保存中..."
│        └─ Button (Primary) "公開"
│
├─ Left Column (70%)
│  ├─ Card
│  │  ├─ Input (size: lg, autofocus)
│  │  │  └─ label: "タイトル"
│  │  │
│  │  ├─ Editor Component
│  │  │  ├─ Toolbar: [Bold, Italic, Link, ...]
│  │  │  └─ Editable Content Area
│  │  │
│  │  └─ Image Upload Section
│  │     ├─ DropZone (drag-drop)
│  │     └─ Uploaded image preview
│  │
│  └─ Auto-save Indicator
│     └─ Text "自動保存: 今から2分前"
│
└─ Right Column (30%)
   ├─ Card (sticky top)
   │  ├─ Heading (H4) "公開設定"
   │  ├─ Select (status: 下書き / 公開)
   │  ├─ DatePicker (publish date)
   │  └─ Button (Primary, full-width) "公開"
   │
   ├─ Card
   │  ├─ Heading (H4) "カテゴリ"
   │  └─ Checkbox list
   │     ├─ ☐ テクノロジー
   │     ├─ ☐ デザイン
   │     └─ ☐ ライフスタイル
   │
   ├─ Card
   │  ├─ Heading (H4) "タグ"
   │  └─ TagInput
   │     └─ [tag1] [tag2] [+add]
   │
   └─ Card
      ├─ Heading (H4) "SEO"
      ├─ Input (label: "Slug")
      │  └─ value: "react-19-guide"
      └─ Textarea (label: "説明")
         └─ placeholder: "Google検索結果に表示される説明"
```

#### React実装ガイド

```typescript
// pages/dashboard/sites/[siteId]/posts/[postId]/edit.tsx

import { useState, useEffect } from 'react';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { RichTextEditor } from '@/components/composite/RichTextEditor';
import { useAutoSave } from '@/hooks/useAutoSave';

export default function PostEditPage() {
  const [post, setPost] = useState({
    title: '',
    content: '',
    excerpt: '',
    featured_image: '',
    categories: [],
    tags: [],
    status: 'draft',
    published_at: null,
  });

  const { isSaving, lastSaved } = useAutoSave(post);

  const handleSave = async () => {
    // Save post...
  };

  return (
    <div className="grid grid-cols-12 gap-6">
      
      {/* Editor Area */}
      <main className="col-span-8">
        <div className="space-y-6">
          <Input
            label="タイトル"
            value={post.title}
            onChange={(e) => setPost({ ...post, title: e.target.value })}
            size="lg"
            autoFocus
          />
          
          <RichTextEditor
            value={post.content}
            onChange={(value) => setPost({ ...post, content: value })}
          />
        </div>
      </main>
      
      {/* Sidebar */}
      <aside className="col-span-4">
        <div className="space-y-4 sticky top-20">
          
          {/* Publish Card */}
          <div className="bg-white p-4 rounded-lg border">
            <h3 className="font-bold mb-3">公開設定</h3>
            <select
              value={post.status}
              onChange={(e) => setPost({ ...post, status: e.target.value })}
              className="w-full px-3 py-2 border rounded mb-3"
            >
              <option value="draft">下書き</option>
              <option value="published">公開</option>
            </select>
            <Button className="w-full">公開</Button>
          </div>
          
          {/* Categories */}
          <div className="bg-white p-4 rounded-lg border">
            <h3 className="font-bold mb-3">カテゴリ</h3>
            <div className="space-y-2">
              {['テクノロジー', 'デザイン', 'ライフスタイル'].map((cat) => (
                <label key={cat} className="flex items-center gap-2">
                  <input
                    type="checkbox"
                    checked={post.categories.includes(cat)}
                  />
                  <span>{cat}</span>
                </label>
              ))}
            </div>
          </div>
        </div>
      </aside>
    </div>
  );
}
```

---

## 5. ユーザー管理（Admin 用）

### 5.1 ユーザー一覧ページ

**URL:** `/dashboard/users`  
**対象ユーザー:** Admin のみ  
**コンテンツ:** 全ユーザー一覧・権限管理

#### ページレイアウト

```
Similar to Posts List:
├─ Header + Search
├─ Table with columns:
│  ├─ ユーザー名
│  ├─ メール
│  ├─ 権限 (User / Site Owner / Admin)
│  ├─ 登録日
│  ├─ 最後のログイン
│  └─ アクション (編集 / 削除)
│
└─ Pagination
```

---

## 6. システム設定（Admin用）

### 6.1 設定ページ

**URL:** `/dashboard/settings`  
**対象ユーザー:** Admin のみ  
**コンテンツ:** システムグローバル設定

#### ページレイアウト

```
Tabs:
├─ 一般設定
│  ├─ サービス名
│  ├─ サポートメール
│  └─ ロゴアップロード
│
├─ メール設定
│  ├─ SMTP サーバー
│  ├─ ユーザー名
│  └─ テストメール送信
│
├─ セキュリティ
│  ├─ 2FA設定
│  ├─ パスワードポリシー
│  └─ API キー管理
│
└─ ストレージ
   ├─ S3 設定
   ├─ 最大ファイルサイズ
   └─ ストレージ使用量表示
```

---

## 7. モバイル対応

### 7.1 レスポンシブ設計

すべてのページについて以下の対応：

```
Desktop (≥1024px):
├─ Full Navigation (Navbar + Sidebar)
├─ Multi-column layouts (2-3カラム)
└─ Full tables

Tablet (640px - 1024px):
├─ Hamburger menu (Sidebar → Drawer)
├─ 2カラム → 1カラム転換
└─ Tables → Card view に切り替え可

Mobile (< 640px):
├─ Full-screen navigation (Drawer / Bottom Nav)
├─ Single column layout
├─ Stacked components
├─ Buttons: Full width
└─ Tables → Accordion / Card view
```

### 7.2 モバイルナビゲーション

```
Bottom Navigation (Mobile):
├─ Dashboard (icon + label)
├─ Sites (icon + label)
├─ Posts (icon + label)
├─ Profile (icon + label)
└─ Menu (icon + label)

或は

Hamburger Menu (Drawer):
├─ ロゴ + 閉じるボタン
├─ Navigation Items (full width)
└─ User profile + Logout
```

---

## 8. ダークモード対応

すべてのページで以下を実装：

```css
/* Light Mode */
--bg-primary: white
--text-primary: #212121
--border-color: #E0E0E0

/* Dark Mode */
@media (prefers-color-scheme: dark) or .dark class
--bg-primary: #121212
--text-primary: white
--border-color: #424242
```

### 検証チェックリスト

- [ ] テキストコントラスト WCAG AA (4.5:1)
- [ ] アイコンの見え方確認
- [ ] グラデーション・シャドウの調整
- [ ] チャート・グラフの色調整

---

## 9. アクセシビリティ

### 全ページ共通

- [ ] キーボード操作全対応 (Tab / Shift+Tab / Enter / Escape)
- [ ] Focus outline 表示 (`:focus-visible`)
- [ ] aria-label / aria-describedby 設定
- [ ] Semantic HTML (button / a / form / input)
- [ ] Form field errors: aria-invalid="true" + aria-describedby
- [ ] Tables: `<th scope="col/row">`
- [ ] Images: `alt` 属性必須
- [ ] Links: テキスト "ここをクリック" ではなく "投稿を編集"
- [ ] スキップリンク: "Skip to main content"

### フォーム

- [ ] Labels: `<label>` 要素で `input` と関連付け
- [ ] Required fields: `required` 属性 + `aria-required`
- [ ] Validation messages: `aria-describedby` で参照
- [ ] Error states: `aria-invalid="true"`

### テーブル

- [ ] Headers: `<th scope="col/row">`
- [ ] Data cells: `<td>`
- [ ] Sorting: `aria-sort` 属性
- [ ] Selection: `aria-label` (checkboxes)

---

## 10. パフォーマンス最適化

### 画像

- [ ] WebP + fallback (JPEG/PNG)
- [ ] レスポンシブ画像 (`srcset`)
- [ ] Lazy loading: `loading="lazy"`
- [ ] 適切な dimensions 指定

### コード分割

- [ ] 動的インポート (`dynamic()`)
- [ ] ルート単位のコード分割
- [ ] コンポーネント遅延ロード

### キャッシング

- [ ] HTTP キャッシュヘッダ
- [ ] Service Worker (if applicable)
- [ ] Browser cache optimization

---

## 11. Penpot実装ガイド

### ページフレーム作成手順

1. **フレームセットアップ**
   - Frame サイズ: Desktop (1440x900) / Tablet (768x1024) / Mobile (375x812)
   - Constraints: Fixed size (responsive variant は別途)
   - Grid: 8px base grid

2. **コンポーネント配置**
   - Header: Position sticky (locked to top)
   - Sidebar: Position sticky (locked to left)
   - Content: Flex layout
   - Footer: Position sticky (locked to bottom)

3. **Variants作成**
   - Desktop / Tablet / Mobile
   - Light / Dark mode
   - Loading / Error states

4. **Design Tokens 適用**
   - Colors: CSS変数に マップ
   - Typography: Text styles に マップ
   - Spacing: Component padding に 統一

---

## 12. React実装チェックリスト

各ページ作成時：

- [ ] Layout コンポーネント作成 (Header / Sidebar / Footer)
- [ ] Page コンポーネント作成
- [ ] Data fetching ロジック (API calls)
- [ ] Loading / Error states
- [ ] Form handling (validation / submission)
- [ ] Responsive styles (@media / Tailwind breakpoints)
- [ ] Dark mode support
- [ ] SEO（meta tags / Open Graph）
- [ ] Accessibility (WCAG AA)
- [ ] Testing (unit / integration / e2e)

---

## 13. 優先度・実装順

### Phase 1 (必須・高優先度)
1. ログインページ ✅
2. ダッシュボード ✅
3. サイト一覧 ✅
4. 投稿一覧 ✅
5. 投稿編集 ✅

### Phase 2 (重要)
6. サイト設定
7. ユーザー管理
8. システム設定

### Phase 3 (補助)
9. 分析ページ
10. モバイル最適化
11. 詳細なエラーページ

---

## 14. 参考資料

### デザイン参考
- Material Design 3: https://m3.material.io/
- Apple Human Interface Guidelines: https://developer.apple.com/design/
- Tailwind UI: https://tailwindui.com/

### ツール
- Penpot: https://penpot.app/
- Figma: https://figma.com/
- Storybook: https://storybook.js.org/

### ドキュメント
- Next.js Docs: https://nextjs.org/docs
- React Docs: https://react.dev/
- Tailwind CSS: https://tailwindcss.com/docs

---

## 15. 今後の検討事項

- [ ] Animation / Transition ガイドライン
- [ ] Microinteraction デザイン
- [ ] オンボーディングフロー
- [ ] A/B テスティング設定
- [ ] アナリティクス統合
- [ ] Internationalization (i18n) 対応

---

**完成日:** 2024-08-07  
**バージョン:** 1.0  
**次ステップ:** C-6 Design Tokens / CSS 同期
