# Phase 8-5: 管理画面メニューのアイコン化

## 目的

管理画面のナビゲーションメニュー(現在テキストのみ)をアイコン+ラベルのビジュアルに改善する。これは独立したUI改善タスクで、バックエンド変更なし。`lucide-react` アイコンライブラリを導入し、全メニュー項目にアイコンを付与する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| アイコンライブラリ選定 | `lucide-react` を採用。軽量・TypeScript対応・Next.js との親和性が高い |
| メニュー構造 | 既存の `web/src/app/layout.tsx` の `NAV_ITEMS` 配列をアイコンフィールド対応に拡張 |
| メニュー項目一覧 | ダッシュボード、プロジェクト(新規)、ユーザー、投稿履歴、AIジョブ、カスタムタグ、監査ログ、ロール管理、システム |
| レスポンシブ対応 | デスクトップ: アイコン + ラベル。モバイル(任意、検討): アイコンのみ or ドロワー |
| アクセシビリティ | aria-label 付与、キーボードナビゲーション継承 |

## アーキテクチャ・実装詳細

### 1. `lucide-react` 導入

`web/package.json` に依存追加:
```json
{
  "lucide-react": "^latest"
}
```

### 2. メニュー構造改修

#### `web/src/app/layout.tsx` の `NAV_ITEMS` 拡張
既存(省略):
```typescript
const NAV_ITEMS = [
  { label: 'ダッシュボード', href: '/' },
  { label: 'サイト', href: '/sites' },
  ...
];
```

改修後:
```typescript
import { Home, Folder, Users, FileText, Zap, Tag, LogBook, Shield, Settings } from 'lucide-react';

const NAV_ITEMS = [
  { label: 'ダッシュボード', href: '/', icon: Home },
  { label: 'プロジェクト', href: '/projects', icon: Folder },  // 新規
  { label: 'ユーザー', href: '/users', icon: Users, adminOnly: true },
  { label: '投稿履歴', href: '/posts', icon: FileText },
  { label: 'AIジョブ', href: '/ai-jobs', icon: Zap },
  { label: 'カスタムタグ', href: '/custom-tags', icon: Tag, adminOnly: true },
  { label: '監査ログ', href: '/audit-logs', icon: LogBook, adminOnly: true },
  { label: 'ロール管理', href: '/admin/roles', icon: Shield, adminOnly: true },
  { label: 'システム', href: '/system', icon: Settings, adminOnly: true },
];
```

- `icon`: lucide-react の React コンポーネント
- `adminOnly`: 管理者のみ表示フラグ(既存の条件分岐継承)

#### `layout.tsx` のメニューレンダリング部分改修
既存(省略):
```tsx
<nav className="...">
  {NAV_ITEMS.filter(item => !item.adminOnly || session.user.role === 'admin').map(item => (
    <Link key={item.href} href={item.href}>
      {item.label}
    </Link>
  ))}
</nav>
```

改修後:
```tsx
import { LucideIcon } from 'lucide-react';

type NavItem = {
  label: string;
  href: string;
  icon: LucideIcon;
  adminOnly?: boolean;
};

<nav className="flex items-center gap-6">
  {NAV_ITEMS.filter(item => !item.adminOnly || session.user.role === 'admin').map(item => {
    const Icon = item.icon;
    return (
      <Link
        key={item.href}
        href={item.href}
        className="flex items-center gap-2 px-3 py-2 rounded-lg hover:bg-neutral-100 transition-colors"
        title={item.label}
      >
        <Icon className="w-5 h-5" aria-hidden="true" />
        <span className="text-sm font-medium">{item.label}</span>
      </Link>
    );
  })}
</nav>
```

### 3. フロントエンド実装詳細

#### `layout.tsx` の全体構造
- `web/src/app/layout.tsx` は Server Component
- `NAV_ITEMS` 定数をファイル内で定義 or 専用ファイル `lib/navigation.ts` に抽出(整理性を考慮して後者推奨)
- Tailwind CSS で スタイリング(既存の neutral-200 など色パレット踏襲)
- 暗いモード対応(`dark:` プレフィックス使用、既存パターン踏襲)

#### `web/src/lib/navigation.ts` (新規、オプション)
```typescript
import { LucideIcon, Home, Folder, Users, ... } from 'lucide-react';

export type NavItem = {
  label: string;
  href: string;
  icon: LucideIcon;
  adminOnly?: boolean;
};

export const NAV_ITEMS: NavItem[] = [
  { label: 'ダッシュボード', href: '/', icon: Home },
  ...
];
```

#### スタイリングの考慮事項
- アイコン + ラベルを横並び配置、ギャップ設定
- ホバー時の背景色・テキスト色変更(`hover:bg-neutral-100 dark:hover:bg-neutral-800`)
- アクティブメニュー項目の強調(現在のパスと照合、下線 or 背景色)
- モバイル対応: 狭い画面ではメニューをドロワー化またはアイコンのみ表示(検討項目)

### 4. 追加の UI 改善(オプション、段階的)

#### ページタイトル・見出しへのアイコン付与
- 各ページの `<h1>` にアイコン追加(任意、実装時に判断)
  - 例: `/users` ページの見出し「ユーザー管理」に Users アイコン付与

#### ブレッドクラムにアイコン付与(実装時判断)
- 現在未実装の場合はスコープ外

## スコープ・実装項目

実装対象:

- [x] `lucide-react` を `package.json` に追加
- [x] `web/src/app/layout.tsx` の `NAV_ITEMS` にアイコンフィールド追加
- [x] メニュー レンダリング部分改修(アイコン+ラベル表示)
- [x] Tailwind CSS でスタイリング(ホバー、暗いモード対応)
- [x] `web/src/lib/navigation.ts` 作成(オプション、整理性重視)

対象外・スコープ外:

- メニューの再配置・リオーガナイズ(現在の階層構造継承)
- モバイル用ドロワー/ハンバーガーメニュー実装(現在は横並び継承、後続フェーズで検討)
- 各ページの見出しへのアイコン付与(段階的、実装時判断)

## 実装順序

1. `lucide-react` インストール
2. `NAV_ITEMS` にアイコンフィールド追加
3. `layout.tsx` のメニューレンダリング改修
4. Tailwind スタイリング調整(ホバー・暗いモード)
5. ブラウザで確認(デスクトップ・モバイル)

## テスト・実機検証

テスト対象(自動テストはスコープ外、ブラウザ手動確認):

- メニュー項目すべてがアイコン+ラベルで表示されることを確認
- ホバー時の背景色・テキスト色が正しく変わることを確認
- 管理者のみ表示項目(ユーザー、監査ログなど)が適切にフィルタリングされることを確認
- 暗いモードに切り替えた時、メニューが正しくレンダリングされることを確認
- 各メニュー項目をクリック → 対象ページに正しく遷移することを確認
- モバイル(レスポンシブ)でのメニュー表示(現在は横並び継承、確認のみ)

