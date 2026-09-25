# Let's Blog デザインシステム仕様書

## 1. デザイン対象の明確化

### 1.1 対象コンポーネント

#### Web 管理画面（Next.js）
**現在の構成:**
- ログイン・認証フロー
- ダッシュボード
- サイト管理画面
- 投稿管理画面（一覧・編集・削除）
- ユーザー管理画面
- プロジェクト管理画面
- システム設定画面

**ターゲットユーザー:**
- テックサビーなユーザー
- 管理者・プロジェクトマネージャー
- ブログ著者

**デバイス:**
- デスクトップ（主流）
- タブレット（サポート）
- モバイル（基本対応）

#### VSCode 拡張
**対象:**
- クイックピック（サイト選択）
- AI コマンド出力パネル
- 設定パネル

**ターゲット:**
- 開発者・ブログ著者

### 1.2 スコープ（Phase C で対象）

- ✅ Penpot でのコンポーネント定義
- ✅ デザインシステムドキュメント
- ✅ React コンポーネント実装ガイド
- ✅ CSS 変数・Tailwind カスタマイズ
- 🔄 既存 React コンポーネントの refactor（Phase D）

---

## 2. デザイン原則

### 2.1 品質属性

| 原則 | 説明 | 具体例 |
|------|------|--------|
| **シンプル** | 不要な装飾を排除 | ミニマルなアイコン、段落的なレイアウト |
| **明確** | ユーザーの意図を明確にする | CTA ボタンのラベルは動詞で始まる |
| **アクセシブル** | WCAG AA 準拠・すべてのユーザーに対応 | 十分なカラーコントラスト、キーボード操作可能 |
| **一貫性** | パターンの再利用・予測可能性 | 同じ役割なら同じスタイル |
| **効率的** | スムーズな操作フロー・短いロード時間 | 深さ 3 クリック以内、レスポンシブ画像 |

### 2.2 ビジュアル原則

| 原則 | 詳細 |
|------|------|
| **色彩** | モダン・テック系（青系プライマリ）、ダークモード対応 |
| **タイポグラフィ** | ロボットフォント + 日本語フォント、可読性重視 |
| **スペーシング** | 8px グリッド（4px 補助）、統一的な余白 |
| **コーナー半径** | sm: 4px, md: 8px, lg: 12px（統一） |
| **シャドウ** | 奥行き感・軽い影（Elevation）、過度な影は避ける |

### 2.3 ユーザーエクスペリエンス原則

- **段階的な明示**: 重要度による視覚的階層化
- **フィードバック**: ホバー・フォーカス・クリック時の視覚的応答
- **エラーハンドリング**: 明確なエラーメッセージ・解決案提示
- **ダークモード**: 昼夜問わず快適な作業環境

---

## 3. 既存コード分析

### 3.1 現在の Tailwind CSS 設定

**ファイル:** `apps/web/tailwind.config.ts`

現在の設定を確認：
```bash
grep -A 20 "colors:" apps/web/tailwind.config.ts
```

**確認項目:**
- [ ] 既存色定義の確認
- [ ] スペーシング設定の確認
- [ ] タイポグラフィ設定の確認
- [ ] プラグイン（forms, typography等）

### 3.2 既存 React コンポーネント

**ファイル構成:**
```
apps/web/src/components/
├── auth/
│   ├── LoginForm.tsx
│   ├── SignupForm.tsx
│   └── ...
├── common/
│   ├── Header.tsx
│   ├── Sidebar.tsx
│   ├── Button.tsx
│   └── ...
├── pages/
│   ├── Dashboard.tsx
│   ├── SiteList.tsx
│   └── ...
└── ...
```

### 3.3 Next.js ページ・レイアウト

- `app/layout.tsx` - ルートレイアウト
- `app/(dashboard)/layout.tsx` - ダッシュボードレイアウト
- `app/(dashboard)/page.tsx` - ダッシュボードページ
- `app/(dashboard)/sites/page.tsx` - サイト一覧
- など

---

## 4. 色彩体系設計方針

### 4.1 カラーパレット構成

```
Primary (ブランドカラー)
├─ primary-50: #F0F7FF (背景)
├─ primary-100: #E0EFFF
├─ primary-500: #0066CC (メインカラー)
├─ primary-600: #0052A3 (ホバー)
└─ primary-700: #003D7A (アクティブ)

Secondary (補助カラー)
├─ secondary-500: #FF6633
└─ ...

Semantic (意味的色)
├─ success: #10B981
├─ warning: #FBBF24
├─ error: #EF4444
└─ info: #3B82F6

Neutral (グレースケール)
├─ neutral-50: #FAFAFA
├─ neutral-100: #F5F5F5
├─ neutral-200: #EEEEEE
├─ neutral-300: #E0E0E0
├─ neutral-400: #BDBDBD
├─ neutral-500: #9E9E9E
├─ neutral-600: #757575
├─ neutral-700: #616161
├─ neutral-800: #424242
└─ neutral-900: #212121
```

### 4.2 ダークモード

各色に dark-mode 対応色を定義（逆転ロジック）。

---

## 5. タイポグラフィ設計方針

### 5.1 フォント選定

| 用途 | フォント | 理由 |
|------|---------|------|
| 見出し | Inter, Segoe UI, sans-serif | モダン・明確・Web最適化 |
| 本文 | Inter, Segoe UI, sans-serif | 可読性・長文対応 |
| コード | Fira Code, Monaco, monospace | 等幅・開発者向け |
| 日本語 | Noto Sans JP, Hiragino Sans | 美しさ・可読性 |

### 5.2 テキストスタイル体系

```
Display 1: 48px, 700, 1.1
Display 2: 40px, 700, 1.1

Heading 1: 32px, 700, 1.2
Heading 2: 24px, 700, 1.2
Heading 3: 20px, 700, 1.3

Body Large: 16px, 400, 1.6
Body: 14px, 400, 1.6
Body Small: 12px, 400, 1.6

Caption: 11px, 400, 1.5
Overline: 12px, 600, 1.4

Button: 14px, 600, 1.0
Label: 12px, 500, 1.0
```

---

## 6. スペーシング・グリッド設計

### 6.1 スペーシングスケール

**ベース: 8px グリッド**

```
0px (0)
4px (0.25)
8px (0.5)
12px (0.75)
16px (1)
20px (1.25)
24px (1.5)
32px (2)
40px (2.5)
48px (3)
56px (3.5)
64px (4)
```

### 6.2 パディング・マージンルール

- **コンテナ**: padding 24px（デスクトップ）, 16px（モバイル）
- **カード**: padding 16px
- **ボタン**: padding-y 8px, padding-x 16px
- **段落**: margin-bottom 16px

### 6.3 グリッド・レイアウト

- **デスクトップ**: 12 カラムグリッド, ガター 24px
- **タブレット**: 8 カラムグリッド, ガター 16px
- **モバイル**: 4 カラムグリッド, ガター 12px

---

## 7. コンポーネント分類

### 7.1 基本コンポーネント

#### Input Components
- `Button` (variant: primary, secondary, danger, ghost)
- `Input` (text, email, password, number)
- `Textarea`
- `Select`
- `Checkbox`
- `Radio`
- `Toggle`
- `Slider`

#### Display Components
- `Badge`
- `Tag`
- `Icon`
- `Avatar`
- `Image`
- `Progress`

#### Feedback Components
- `Alert`
- `Toast`
- `Tooltip`
- `Skeleton`
- `Spinner`

### 7.2 複合コンポーネント

- `Card`
- `Modal`
- `Drawer`
- `Dropdown`
- `Menu`
- `Breadcrumb`
- `Pagination`
- `Stepper`
- `Tabs`
- `Accordion`

### 7.3 レイアウトコンポーネント

- `Header` / `Navbar`
- `Sidebar`
- `Footer`
- `Container`
- `Grid`
- `Stack` (Flex の便利ラッパー)

---

## 8. Ollama プロンプトエンジニアリング

### 8.1 デザイン評価プロンプト

```
あなたはデザインシステムアーキテクトです。

以下のデザイン要素が「Let's Blog Server」のデザイン原則に
準拠しているか評価してください：

原則:
- シンプル（不要な装飾排除）
- 明確（ユーザー意図の明確化）
- アクセシブル（WCAG AA準拠）
- 一貫性（パターン再利用）
- 効率的（スムーズなフロー）

評価対象:
{design_element}

評価レポート（JSON形式）:
{
  "compliance_score": 0-10,
  "strengths": [...],
  "improvements": [...],
  "wcag_issues": [...],
  "consistency_notes": "..."
}
```

### 8.2 コンポーネント設計プロンプト

```
Let's Blog のデザインシステムでコンポーネントを設計してください。

コンポーネント: {component_name}
用途: {use_case}
バリエーション: {variations}

以下を含むJSON形式で提案してください:
- サイズバリエーション（小・中・大）
- 状態（デフォルト・ホバー・フォーカス・無効・ローディング）
- ダークモード対応色
- アクセシビリティ考慮事項
- CSS実装例（Tailwind）
```

---

## 9. ダークモード戦略

### 9.1 実装方式

**方式: CSS クラス + CSS 変数**

```html
<!-- light mode (default) -->
<html class="light">

<!-- dark mode -->
<html class="dark">
```

### 9.2 色の定義

```css
:root {
  --color-bg: #ffffff;
  --color-text: #212121;
  --color-primary: #0066CC;
}

.dark {
  --color-bg: #121212;
  --color-text: #ffffff;
  --color-primary: #66CCFF;
}
```

### 9.3 自動切り替え

システム設定を検知：
```typescript
if (window.matchMedia('(prefers-color-scheme: dark)').matches) {
  document.documentElement.classList.add('dark');
}
```

---

## 10. アクセシビリティ基準

### 10.1 WCAG AA 準拠

| 要件 | チェック項目 |
|------|-----------|
| **色コントラスト** | 大文本 3:1、通常文本 4.5:1 以上 |
| **キーボード操作** | Tab キーで全機能利用可能 |
| **フォーカス表示** | 明確な focus outline |
| **画像代替テキスト** | alt 属性・aria-label 設定 |
| **リスト構造** | セマンティック HTML（ul, li, ol） |
| **フォームラベル** | label 要素・aria-label 関連付け |
| **スクリーンリーダー** | role・aria-* 属性で意味付け |

### 10.2 テスト方法

- **自動テスト**: axe-core, Pa11y
- **手動テスト**: スクリーンリーダー（NVDA, JAWS）
- **キーボード操作**: Tab / Shift+Tab / Enter / Arrow Keys

---

## 11. デザイン成果物

### 11.1 Penpot アセット

- **Pages**: ページデザイン（Dashboard, Sites, Posts 等）
- **Components**: 再利用可能なコンポーネント
- **Styles**: 色・タイポグラフィ・エフェクト
- **Prototypes**: インタラクション定義

### 11.2 ドキュメント

- `design-system-spec.md` （本ドキュメント）
- `color-system.md`
- `typography-guide.md`
- `component-guide.md`
- `page-design-guide.md`
- `design-to-code-guide.md` (CSS/React マッピング)

### 11.3 実装ガイド

- Tailwind config カスタマイズ
- CSS 変数定義
- React コンポーネント実装パターン

---

## 12. デザインプロセス

### 12.1 Phase C スケジュール

```
C-1: 要件定義 (4-6h)
  ↓
C-2: 色設計 (3-4h)
  ↓
C-3: タイポ設計 (2-3h)
  ↓
C-4: コンポーネント設計 (8-12h)
  ↓
C-5: ページデザイン (10-15h)
  ↓
C-6: Tokens/CSS 同期 (6-8h)
```

### 12.2 品質保証

- [ ] Ollama による提案レビュー
- [ ] WCAG AA コントラストチェック
- [ ] Penpot コンポーネント実装チェック
- [ ] React マッピング確認
- [ ] 実機でのダークモード・レスポンシブ確認

---

## 13. 参考リソース

- WCAG 2.1: https://www.w3.org/WAI/WCAG21/quickref/
- Material Design 3: https://m3.material.io/
- Tailwind CSS: https://tailwindcss.com/
- Penpot Docs: https://help.penpot.app/
- Figma Design System Guide: https://www.designsystems.com/

---

## 14. 今後の検討項目

- [ ] コンポーネント Storybook 統合（React doc）
- [ ] Design Tokens JSON 自動生成
- [ ] Penpot → Figma マイグレーション戦略
- [ ] 多言語対応（日本語・英語・中国語）
- [ ] アニメーション・トランジション ガイドライン
- [ ] マイクロインタラクション設計（ローディング等）
