# Let's Blog コンポーネント設計ガイド

## 概要

本ドキュメントは Let's Blog Server デザインシステムのコンポーネント詳細仕様です。

各コンポーネントについて以下を定義します：
- 用途・ユースケース
- バリエーション・サイズ
- 状態（デフォルト・ホバー・フォーカス・無効・ローディング）
- ダークモード対応
- アクセシビリティ要件
- Tailwind CSS実装例
- React実装パターン

---

## 1. 基本コンポーネント

### 1.1 Button

**用途:** ユーザーアクション（送信・キャンセル・削除等）

#### バリエーション

```
Primary (ブランド色・メインアクション)
├─ solid (標準)
├─ ghost (背景なし)
└─ outline (枠線のみ)

Secondary (補助アクション)
├─ solid
├─ ghost
└─ outline

Danger (破壊的操作)
├─ solid
├─ ghost
└─ outline

Ghost (最小限)
└─ テキストのみ
```

#### サイズ

| サイズ | 高さ | パディング | フォント | 用途 |
|--------|------|-----------|---------|------|
| xs | 24px | 4px 8px | 12px | コンパクトUI |
| sm | 32px | 6px 12px | 14px | フォーム補助 |
| md | 40px | 8px 16px | 14px | 標準（推奨） |
| lg | 48px | 12px 24px | 16px | プロモーション |

#### 状態

```
Default
├─ background: --color-primary-500
├─ color: white
└─ cursor: pointer

Hover
├─ background: --color-primary-600
└─ box-shadow: 0 4px 12px rgba(0,102,204,0.15)

Focus
├─ outline: 2px solid --color-primary-500
└─ outline-offset: 2px

Disabled
├─ background: --color-neutral-200
├─ color: --color-neutral-400
└─ cursor: not-allowed

Loading
├─ background: --color-primary-500
├─ color: transparent
└─ spinner: centered overlay
```

#### ダークモード

```css
.dark {
  background: var(--color-primary-400);
}

.dark:hover {
  background: var(--color-primary-300);
}

.dark:disabled {
  background: var(--color-neutral-700);
  color: var(--color-neutral-500);
}
```

#### アクセシビリティ

- [ ] aria-label / aria-labelledby 設定
- [ ] role="button" （アクションでない場合）
- [ ] disabled 属性使用時に aria-disabled も設定
- [ ] キーボード操作対応 (Enter / Space)
- [ ] Loading 状態時に aria-busy="true"

#### Tailwind実装例

```html
<!-- Primary Solid Medium（標準） -->
<button class="px-4 py-2 bg-primary-500 text-white font-semibold rounded-md 
             hover:bg-primary-600 focus:outline-2 focus:outline-offset-2 focus:outline-primary-500
             disabled:bg-neutral-200 disabled:text-neutral-400 disabled:cursor-not-allowed
             transition-colors duration-200">
  投稿する
</button>

<!-- Secondary Ghost Small -->
<button class="px-3 py-1 text-primary-600 text-sm font-medium rounded-md
             hover:bg-primary-50 focus:outline-2 focus:outline-primary-500
             dark:text-primary-400 dark:hover:bg-primary-900">
  キャンセル
</button>

<!-- Danger Solid Large -->
<button class="px-6 py-3 bg-error-500 text-white font-semibold rounded-md
             hover:bg-error-600 focus:outline-2 focus:outline-error-500
             disabled:bg-neutral-200">
  削除
</button>
```

#### React実装パターン

```typescript
interface ButtonProps {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
  style?: 'solid' | 'ghost' | 'outline';
  size?: 'xs' | 'sm' | 'md' | 'lg';
  disabled?: boolean;
  loading?: boolean;
  children: React.ReactNode;
  onClick?: () => void;
  className?: string;
}

export function Button({
  variant = 'primary',
  style = 'solid',
  size = 'md',
  disabled = false,
  loading = false,
  children,
  className,
  ...props
}: ButtonProps) {
  const baseClasses = 'font-semibold rounded-md transition-colors duration-200 flex items-center justify-center';
  
  const variantStyles = {
    primary: 'bg-primary-500 text-white hover:bg-primary-600 disabled:bg-neutral-200',
    secondary: 'bg-secondary-500 text-white hover:bg-secondary-600',
    danger: 'bg-error-500 text-white hover:bg-error-600',
    ghost: 'text-primary-600 hover:bg-primary-50 dark:text-primary-400',
  };

  const sizeClasses = {
    xs: 'px-2 py-1 text-xs h-6',
    sm: 'px-3 py-1.5 text-sm h-8',
    md: 'px-4 py-2 text-sm h-10',
    lg: 'px-6 py-3 text-base h-12',
  };

  return (
    <button
      className={`${baseClasses} ${variantStyles[variant]} ${sizeClasses[size]} ${className}`}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? <Spinner size="sm" /> : children}
    </button>
  );
}
```

---

### 1.2 Input

**用途:** テキスト入力（テキスト・メール・パスワード・数字）

#### バリエーション

```
Text Input
├─ text (標準)
├─ email (メールアドレス)
├─ password (パスワード)
├─ number (数字)
├─ tel (電話番号)
└─ url (URL)

States
├─ default
├─ focus
├─ error (エラーメッセージ表示)
├─ success (成功状態)
├─ disabled
└─ readonly
```

#### サイズ

| サイズ | 高さ | パディング | フォント | 備考 |
|--------|------|-----------|---------|------|
| sm | 32px | 6px 12px | 12px | コンパクト |
| md | 40px | 8px 12px | 14px | 標準 |
| lg | 48px | 12px 16px | 16px | プロモーション |

#### 状態

```
Default
├─ border: 1px solid --color-neutral-300
├─ background: white
└─ color: --color-neutral-900

Focus
├─ border: 2px solid --color-primary-500
├─ box-shadow: 0 0 0 3px --color-primary-50
└─ outline: none

Error
├─ border: 2px solid --color-error-500
├─ background: --color-error-50
└─ color: --color-error-700

Success
├─ border: 2px solid --color-success-500
└─ background: --color-success-50

Disabled
├─ border: 1px solid --color-neutral-200
├─ background: --color-neutral-100
├─ color: --color-neutral-400
└─ cursor: not-allowed
```

#### ダークモード

```css
.dark input {
  background: var(--color-neutral-900);
  border-color: var(--color-neutral-700);
  color: white;
}

.dark input:focus {
  border-color: var(--color-primary-400);
  box-shadow: 0 0 0 3px var(--color-primary-900);
}
```

#### アクセシビリティ

- [ ] label要素による関連付け（for属性）
- [ ] aria-label / aria-labelledby
- [ ] aria-invalid="true" （エラー状態）
- [ ] aria-describedby （ヘルプテキスト参照）
- [ ] placeholder はラベルの代替にしない
- [ ] 最小フォントサイズ 12px

#### Tailwind実装例

```html
<!-- Label付き -->
<div class="space-y-2">
  <label for="email" class="text-label text-primary block">
    メールアドレス
  </label>
  <input
    id="email"
    type="email"
    placeholder="example@example.com"
    class="w-full px-3 py-2 border border-neutral-300 rounded-md
           focus:border-primary-500 focus:ring-2 focus:ring-primary-100
           focus:outline-none
           disabled:bg-neutral-100 disabled:text-neutral-400 disabled:cursor-not-allowed
           dark:bg-neutral-900 dark:border-neutral-700 dark:text-white"
    aria-describedby="email-help"
  />
  <p id="email-help" class="text-caption text-neutral-600">
    ログインに使用します
  </p>
</div>

<!-- エラー状態 -->
<div class="space-y-2">
  <label for="password" class="text-label text-primary">
    パスワード
  </label>
  <input
    id="password"
    type="password"
    class="w-full px-3 py-2 border-2 border-error-500 rounded-md bg-error-50
           focus:outline-none focus:ring-2 focus:ring-error-200"
    aria-invalid="true"
    aria-describedby="password-error"
  />
  <p id="password-error" class="text-caption text-error-600 font-medium">
    パスワードは8文字以上である必要があります
  </p>
</div>
```

#### React実装パターン

```typescript
interface InputProps
  extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  success?: boolean;
  size?: 'sm' | 'md' | 'lg';
  helperText?: string;
}

export function Input({
  label,
  error,
  success,
  size = 'md',
  helperText,
  ...props
}: InputProps) {
  const sizeClasses = {
    sm: 'px-2 py-1.5 text-xs',
    md: 'px-3 py-2 text-sm',
    lg: 'px-4 py-3 text-base',
  };

  const borderClasses = error
    ? 'border-2 border-error-500 bg-error-50'
    : success
    ? 'border-2 border-success-500 bg-success-50'
    : 'border border-neutral-300';

  return (
    <div className="space-y-2">
      {label && (
        <label className="text-label text-primary block">
          {label}
        </label>
      )}
      <input
        className={`w-full rounded-md focus:outline-none transition-colors
          ${sizeClasses[size]}
          ${borderClasses}
          focus:border-primary-500 focus:ring-2 focus:ring-primary-100
          disabled:bg-neutral-100 disabled:text-neutral-400
          dark:bg-neutral-900 dark:border-neutral-700 dark:text-white`}
        aria-invalid={error ? 'true' : 'false'}
        aria-describedby={error || helperText ? 'helper-text' : undefined}
        {...props}
      />
      {(error || helperText) && (
        <p
          id="helper-text"
          className={`text-caption ${
            error ? 'text-error-600 font-medium' : 'text-neutral-600'
          }`}
        >
          {error || helperText}
        </p>
      )}
    </div>
  );
}
```

---

### 1.3 Textarea

**用途:** 複数行テキスト入力（記事・コメント・説明）

#### 仕様

```
Row数
├─ デフォルト: 4行（minHeight: 96px）
├─ 最小: 3行（minHeight: 72px）
└─ 最大: 20行（maxHeight: 480px）

リサイズ
├─ 垂直リサイズのみ許可
└─ 最小・最大高さ制限

状態
├─ default / focus / error / success / disabled
└─ Input と同じ
```

#### Tailwind実装例

```html
<textarea
  rows="4"
  placeholder="記事の本文を入力してください"
  class="w-full px-3 py-2 border border-neutral-300 rounded-md
         resize-y min-h-24 max-h-96
         focus:border-primary-500 focus:ring-2 focus:ring-primary-100
         focus:outline-none
         disabled:bg-neutral-100 disabled:text-neutral-400
         dark:bg-neutral-900 dark:border-neutral-700 dark:text-white">
</textarea>
```

---

### 1.4 Select

**用途:** ドロップダウン選択（カテゴリ・ステータス・フィルター）

#### 仕様

```
形式
├─ シングル選択
└─ マルチセレクト（チェックボックス型）

サイズ
├─ sm: 32px
├─ md: 40px (標準)
└─ lg: 48px

状態
├─ closed (デフォルト)
├─ open (展開)
├─ selected (選択状態)
├─ hover
├─ focus
├─ error
└─ disabled
```

#### Tailwind実装例

```html
<!-- シングル選択 -->
<div class="relative">
  <select
    class="w-full px-3 py-2 border border-neutral-300 rounded-md
           bg-white text-neutral-900
           focus:border-primary-500 focus:ring-2 focus:ring-primary-100
           focus:outline-none
           disabled:bg-neutral-100 disabled:text-neutral-400
           appearance-none cursor-pointer
           dark:bg-neutral-900 dark:border-neutral-700 dark:text-white"
  >
    <option value="">カテゴリを選択</option>
    <option value="tech">テクノロジー</option>
    <option value="design">デザイン</option>
    <option value="life">ライフスタイル</option>
  </select>
  <!-- Custom arrow icon -->
  <span class="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-neutral-600">
    ▼
  </span>
</div>
```

---

### 1.5 Checkbox

**用途:** 複数選択（フィルター・同意・複数項目）

#### 状態

```
Unchecked
├─ border: 2px solid --color-neutral-300
├─ background: white
└─ cursor: pointer

Checked
├─ background: --color-primary-500
├─ checkmark: white
└─ border: 2px solid --color-primary-500

Indeterminate
├─ background: --color-primary-500
├─ line: white
└─ border: 2px solid --color-primary-500

Focus
├─ ring: 2px --color-primary-100
└─ ring-offset: 2px

Disabled
├─ opacity: 0.5
├─ cursor: not-allowed
└─ background: --color-neutral-100
```

#### Tailwind実装例

```html
<div class="flex items-center gap-2">
  <input
    id="agree"
    type="checkbox"
    class="w-5 h-5 border-2 border-neutral-300 rounded accent-primary-500
           cursor-pointer focus:ring-2 focus:ring-primary-100
           disabled:opacity-50 disabled:cursor-not-allowed
           dark:border-neutral-600 dark:bg-neutral-800"
  />
  <label for="agree" class="text-body text-primary cursor-pointer">
    利用規約に同意します
  </label>
</div>
```

---

### 1.6 Radio

**用途:** 単一選択（オプション・モード選択）

#### 仕様

```
形式
├─ ラジオボタン（デフォルト）
└─ ラジオボタングループ（複数項目）

サイズ: 20px × 20px

状態
├─ unselected / selected / focus / disabled
└─ Checkbox と類似
```

#### Tailwind実装例

```html
<fieldset class="space-y-3">
  <legend class="text-label text-primary font-medium">
    表示モード
  </legend>
  
  <div class="flex items-center gap-2">
    <input
      id="mode-light"
      name="mode"
      type="radio"
      value="light"
      class="w-5 h-5 border-2 border-neutral-300 rounded-full accent-primary-500
             cursor-pointer focus:ring-2 focus:ring-primary-100"
      defaultChecked
    />
    <label for="mode-light" class="text-body cursor-pointer">
      ライトモード
    </label>
  </div>
  
  <div class="flex items-center gap-2">
    <input
      id="mode-dark"
      name="mode"
      type="radio"
      value="dark"
      class="w-5 h-5 border-2 border-neutral-300 rounded-full accent-primary-500"
    />
    <label for="mode-dark" class="text-body cursor-pointer">
      ダークモード
    </label>
  </div>
</fieldset>
```

---

### 1.7 Toggle

**用途:** ON/OFF切り替え（設定・フィーチャーフラグ）

#### 仕様

```
サイズ: 48px × 28px (標準)

状態
├─ off (左側・灰色)
├─ on (右側・プライマリ色)
├─ focus
└─ disabled

Animation
├─ 切り替え時: 200ms linear
└─ smooth transition
```

#### Tailwind実装例

```html
<label class="flex items-center cursor-pointer gap-3">
  <input
    type="checkbox"
    class="sr-only peer"
  />
  <div class="relative w-12 h-7 bg-neutral-300 rounded-full
              peer-checked:bg-primary-500
              transition-colors duration-200
              peer-focus:ring-2 peer-focus:ring-primary-100
              peer-disabled:opacity-50 peer-disabled:cursor-not-allowed">
    <div class="absolute top-1 left-1 w-5 h-5 bg-white rounded-full shadow
                peer-checked:translate-x-5
                transition-transform duration-200">
    </div>
  </div>
  <span class="text-body text-primary">
    通知を有効にする
  </span>
</label>
```

---

### 1.8 Badge & Tag

**用途:** ステータス表示・カテゴリラベル

#### Badge（ステータス）

```
バリエーション
├─ default (灰色)
├─ primary (青)
├─ success (緑)
├─ warning (黄)
├─ error (赤)
└─ info (情報)

サイズ
├─ sm: 16px高さ
├─ md: 24px高さ (標準)
└─ lg: 32px高さ

形状
├─ rounded (デフォルト: 12px)
└─ pill (border-radius: 9999px)
```

#### Tailwind実装例

```html
<!-- Default Badge -->
<span class="inline-flex items-center gap-1 px-2 py-1 bg-neutral-200 
             text-neutral-700 text-caption font-medium rounded-md">
  作成中
</span>

<!-- Success Badge -->
<span class="inline-flex items-center gap-1 px-3 py-1 bg-success-100 
             text-success-700 text-caption font-medium rounded-full">
  ✓ 公開済み
</span>

<!-- Error Badge -->
<span class="inline-flex items-center gap-1 px-2 py-1 bg-error-100 
             text-error-700 text-caption font-semibold rounded-md">
  ✕ エラー
</span>
```

#### Tag（カテゴリ）

```
仕様
├─ パディング: 4px 8px
├─ フォント: 12px / 400
├─ 背景: プライマリ100
├─ テキスト: プライマリ700
└─ アイコン: 削除ボタン (×) オプション

用途
├─ カテゴリ分類
├─ キーワード
└─ フィルタータグ
```

#### Tailwind実装例

```html
<!-- Tag with remove button -->
<div class="inline-flex items-center gap-2 px-2 py-1 bg-primary-100 
            text-primary-700 text-caption rounded-md">
  <span>テクノロジー</span>
  <button class="text-primary-600 hover:text-primary-900 font-bold">
    ×
  </button>
</div>
```

---

### 1.9 Avatar

**用途:** ユーザープロフィール画像

#### サイズ

| サイズ | 寸法 | 用途 |
|--------|------|------|
| xs | 24px | コンパクト |
| sm | 32px | リスト |
| md | 40px | 標準 |
| lg | 56px | プロフィール |
| xl | 80px | 大型表示 |

#### バリエーション

```
Image (写真)
├─ 正方形・円形両対応
└─ object-cover で中央配置

Initials (イニシャル)
├─ 背景色: プライマリ色
├─ テキスト: white
└─ ウェイト: bold

Icon (アイコン)
├─ SVG表示
└─ 背景色付き

Status (ステータスバッジ)
├─ 右下に小さいバッジ
└─ オンライン・オフライン表示
```

#### Tailwind実装例

```html
<!-- Image -->
<img
  src="avatar.jpg"
  alt="著者名"
  class="w-10 h-10 rounded-full object-cover border-2 border-neutral-200"
/>

<!-- Initials -->
<div class="w-10 h-10 rounded-full bg-primary-500 text-white
            flex items-center justify-center font-bold text-sm">
  ST
</div>

<!-- With Status -->
<div class="relative w-10 h-10">
  <img
    src="avatar.jpg"
    alt="ユーザー"
    class="w-full h-full rounded-full object-cover"
  />
  <span class="absolute bottom-0 right-0 w-3 h-3 bg-success-500 rounded-full
               border-2 border-white"></span>
</div>
```

---

### 1.10 Progress Bar

**用途:** 進捗表示（ファイルアップロード・フォーム進捗）

#### 仕様

```
高さ
├─ sm: 4px
├─ md: 8px (標準)
└─ lg: 12px

最大値: 100%

バリエーション
├─ default (プライマリ色)
├─ success (緑)
├─ warning (黄)
├─ error (赤)
└─ striped (ストライプアニメーション)

ラベル
├─ 非表示
├─ 数字表示 (12%)
└─ テキスト表示 (アップロード中...)
```

#### Tailwind実装例

```html
<!-- Simple Progress Bar -->
<div class="w-full bg-neutral-200 rounded-full h-2 overflow-hidden">
  <div class="bg-primary-500 h-full" style="width: 45%"></div>
</div>

<!-- With Label -->
<div class="space-y-2">
  <div class="flex justify-between text-caption">
    <span class="text-primary">アップロード中...</span>
    <span class="text-neutral-600">45%</span>
  </div>
  <div class="w-full bg-neutral-200 rounded-full h-2 overflow-hidden">
    <div
      class="bg-primary-500 h-full transition-all duration-300"
      style="width: 45%"
    ></div>
  </div>
</div>

<!-- Striped Animation -->
<div class="w-full bg-neutral-200 rounded-full h-3 overflow-hidden">
  <div
    class="h-full bg-gradient-to-r from-primary-500 to-primary-400 animate-pulse"
    style="width: 60%"
  ></div>
</div>
```

---

### 1.11 Spinner & Loader

**用途:** ローディング表示

#### バリエーション

```
Spinner (回転)
├─ リング型（ドーナツ型）
├─ サイズ: 16px, 24px, 32px, 48px
└─ 色: プライマリ色

Skeleton (プレースホルダー)
├─ テキスト用：高さ 16px
├─ 画像用：正方形
└─ カード用：複合型

Progress (進捗)
├─ インデターミネート (無限)
└─ デターミネート (0-100%)
```

#### Tailwind実装例

```html
<!-- Spinner -->
<div class="w-6 h-6 border-4 border-primary-200 border-t-primary-500 rounded-full animate-spin"></div>

<!-- Inline Loading -->
<button disabled class="flex items-center gap-2">
  <div class="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
  処理中...
</button>

<!-- Skeleton Loading -->
<div class="space-y-4">
  <!-- Text skeleton -->
  <div class="h-4 bg-neutral-200 rounded animate-pulse"></div>
  <div class="h-4 bg-neutral-200 rounded animate-pulse w-5/6"></div>
  
  <!-- Image skeleton -->
  <div class="w-24 h-24 bg-neutral-200 rounded animate-pulse"></div>
  
  <!-- Card skeleton -->
  <div class="p-4 bg-neutral-50 rounded-lg space-y-3">
    <div class="h-6 bg-neutral-200 rounded animate-pulse w-3/4"></div>
    <div class="h-4 bg-neutral-200 rounded animate-pulse"></div>
    <div class="h-4 bg-neutral-200 rounded animate-pulse w-5/6"></div>
  </div>
</div>
```

---

### 1.12 Alert & Toast

**用途:** ユーザーへのメッセージ通知

#### Alert（ページ内・永続）

```
バリエーション
├─ info (情報)
├─ success (成功)
├─ warning (警告)
└─ error (エラー)

要素
├─ アイコン
├─ タイトル（オプション）
├─ メッセージ
├─ 閉じるボタン（オプション）
└─ アクションボタン（オプション）

配置: ページ上部・フォーム直下・インライン
```

#### Tailwind実装例

```html
<!-- Success Alert -->
<div class="p-4 bg-success-50 border-l-4 border-success-500 rounded-r-md">
  <div class="flex gap-3">
    <span class="text-success-600 font-bold">✓</span>
    <div class="flex-1">
      <h4 class="font-semibold text-success-900">成功</h4>
      <p class="text-body-sm text-success-700 mt-1">
        記事を正常に公開しました
      </p>
    </div>
    <button class="text-success-600 hover:text-success-900">×</button>
  </div>
</div>

<!-- Error Alert -->
<div class="p-4 bg-error-50 border-l-4 border-error-500 rounded">
  <div class="flex gap-3">
    <span class="text-error-600 text-xl">⚠</span>
    <div class="flex-1">
      <h4 class="font-semibold text-error-900">エラー</h4>
      <p class="text-body-sm text-error-700 mt-1">
        ファイルアップロードに失敗しました。
        <button class="underline font-medium hover:no-underline">
          再試行
        </button>
      </p>
    </div>
  </div>
</div>

<!-- Info Alert -->
<div class="p-4 bg-info-50 border-l-4 border-info-500 rounded">
  <p class="text-body text-info-700">
    💡 このページは作成中です。今後更新される予定です。
  </p>
</div>
```

#### Toast（通知・一時表示）

```
特性
├─ 自動消去（3-5秒）
├─ スタック表示（複数通知）
├─ アニメーション（フェードイン・アウト）
└─ 位置: 右下・左下・上部（カスタマイズ可）

用途
├─ 操作完了通知
├─ 自動保存通知
├─ システムメッセージ
└─ バックグラウンド処理通知
```

#### Tailwind実装例

```html
<!-- Toast Stack (bottom-right) -->
<div class="fixed bottom-4 right-4 space-y-2 z-50">
  <!-- Success Toast -->
  <div class="flex items-center gap-3 p-4 bg-success-500 text-white rounded-lg shadow-lg
              animate-fade-in">
    <span>✓</span>
    <span class="text-sm font-medium">保存しました</span>
    <button class="ml-auto text-white hover:opacity-80">×</button>
  </div>
  
  <!-- Error Toast -->
  <div class="flex items-center gap-3 p-4 bg-error-500 text-white rounded-lg shadow-lg
              animate-fade-in animation-delay-100">
    <span>⚠</span>
    <span class="text-sm font-medium">エラーが発生しました</span>
  </div>
</div>
```

---

### 1.13 Tooltip

**用途:** ホバー時のヒント表示

#### 仕様

```
形式
├─ バブル（吹き出し）
├─ テキストのみ
└─ アイコン付き（オプション）

位置
├─ top / bottom / left / right
└─ auto（画面端で反転）

遅延
├─ マウスホバー: 200ms
├─ タッチ: long-press
└─ キーボード: focusで表示

最大幅: 240px
```

#### Tailwind実装例

```html
<!-- Tooltip Trigger -->
<div class="group relative inline-block">
  <button class="text-primary-600 hover:text-primary-700">
    ?
  </button>
  
  <!-- Tooltip Content -->
  <div class="invisible group-hover:visible absolute bottom-full left-1/2 -translate-x-1/2 mb-2 
              px-3 py-2 bg-neutral-900 text-white text-xs rounded whitespace-nowrap
              shadow-lg z-10
              opacity-0 group-hover:opacity-100
              transition-opacity duration-200">
    このボタンをクリックして詳細を表示
    <!-- Arrow -->
    <div class="absolute top-full left-1/2 -translate-x-1/2 w-2 h-2 bg-neutral-900 
                transform rotate-45"></div>
  </div>
</div>

<!-- Dark Mode Tooltip -->
<div class="dark">
  <div class="group relative">
    <button>ヘルプ</button>
    <div class="invisible group-hover:visible absolute bottom-full px-3 py-2
                bg-neutral-100 dark:bg-neutral-800 text-neutral-900 dark:text-white
                text-xs rounded shadow-lg">
      ヒント表示
    </div>
  </div>
</div>
```

---

## 2. 複合コンポーネント

### 2.1 Card

**用途:** コンテンツコンテナ（ポスト・統計情報・プレビュー）

#### バリエーション

```
レベル
├─ flat (背景色のみ)
├─ outlined (枠線のみ)
└─ elevated (shadow付き)

インタラクション
├─ static (非インタラクティブ)
├─ hover (ホバー時に上昇)
└─ clickable (クリック時の視覚フィードバック)
```

#### 構造

```
Card
├─ Header (タイトル・メタ情報)
│  ├─ Title
│  ├─ Subtitle
│  └─ Action Menu (オプション)
├─ Media (画像・ビデオ・アイコン) (オプション)
├─ Content (本文・説明)
└─ Footer (アクション・タグ) (オプション)
```

#### Tailwind実装例

```html
<!-- Elevated Card with Image -->
<div class="bg-white rounded-lg shadow-md overflow-hidden hover:shadow-lg
            transition-shadow duration-200 hover:translate-y-px cursor-pointer">
  <!-- Media -->
  <div class="w-full h-48 bg-neutral-200 overflow-hidden">
    <img src="post.jpg" alt="記事" class="w-full h-full object-cover" />
  </div>
  
  <!-- Content -->
  <div class="p-4 space-y-3">
    <!-- Header -->
    <div>
      <p class="text-overline text-primary-600">テクノロジー</p>
      <h3 class="text-h4 text-primary font-bold mt-1">
        React 19の新機能解説
      </h3>
      <p class="text-caption text-neutral-600 mt-2">
        2024年8月7日 by 著者名
      </p>
    </div>
    
    <!-- Body -->
    <p class="text-body text-neutral-700 line-clamp-2">
      React 19では多くの新機能が追加されました。
      この記事ではその全体像を解説します。
    </p>
    
    <!-- Footer -->
    <div class="flex gap-2 pt-2 border-t border-neutral-200">
      <button class="text-primary-600 hover:text-primary-700 font-medium text-sm">
        詳細を読む →
      </button>
    </div>
  </div>
</div>

<!-- Compact Card (List) -->
<div class="p-3 border border-neutral-200 rounded-md hover:bg-neutral-50">
  <h4 class="font-semibold text-body text-primary">タイトル</h4>
  <p class="text-body-sm text-neutral-600 mt-1">説明テキスト</p>
</div>
```

---

### 2.2 Modal / Dialog

**用途:** 重要な確認・フォーム入力・詳細表示

#### 仕様

```
オーバーレイ
├─ 背景: rgba(0,0,0,0.5)
├─ z-index: 40
└─ クリックで閉じる（オプション）

モーダル本体
├─ 最大幅: 512px (md)
├─ パディング: 24px
├─ Border Radius: 12px
├─ Shadow: elevation-high
└─ 縦方向センター・横方向センター

アニメーション
├─ オープン: 200ms scale-up + fade
├─ クローズ: 100ms scale-down + fade
└─ キーボード: Esc で閉じる
```

#### 構造

```
Modal
├─ Header
│  ├─ Title
│  └─ Close Button
├─ Body (scrollable if long)
│  └─ Content
└─ Footer (オプション)
   ├─ Secondary Action
   └─ Primary Action
```

#### Tailwind実装例

```html
<!-- Modal Overlay & Dialog -->
<div class="fixed inset-0 bg-black/50 flex items-center justify-center z-40
            animate-fade-in" id="modal-overlay">
  
  <!-- Modal Container -->
  <div class="bg-white rounded-lg shadow-2xl max-w-md w-full mx-4
              animate-scale-up dark:bg-neutral-900">
    
    <!-- Header -->
    <div class="flex items-center justify-between p-6 border-b border-neutral-200
                dark:border-neutral-700">
      <h2 class="text-h4 font-bold text-primary">確認</h2>
      <button class="text-neutral-600 hover:text-neutral-900 text-2xl">
        ×
      </button>
    </div>
    
    <!-- Body -->
    <div class="p-6 max-h-96 overflow-y-auto">
      <p class="text-body text-neutral-700">
        この記事を削除してもよろしいですか？
        削除後は復元できません。
      </p>
    </div>
    
    <!-- Footer -->
    <div class="flex gap-3 justify-end p-6 border-t border-neutral-200
                dark:border-neutral-700">
      <button class="px-4 py-2 text-body font-medium text-neutral-700
                     hover:bg-neutral-100 rounded-md">
        キャンセル
      </button>
      <button class="px-4 py-2 text-body font-medium text-white
                     bg-error-500 hover:bg-error-600 rounded-md">
        削除
      </button>
    </div>
  </div>
</div>
```

---

### 2.3 Dropdown / Menu

**用途:** コンテキストメニュー・ドロップダウンアクション

#### 仕様

```
トリガー
├─ ボタン / テキストリンク
└─ 状態: normal / active

メニュー
├─ 位置: auto-adjust (画面端チェック)
├─ 最小幅: 160px
├─ 最大高さ: 400px (scrollable)
└─ z-index: 30

アニメーション
├─ オープン: 100ms fade-in + slide-down
└─ クローズ: 100ms fade-out

キーボード操作
├─ ↓/↑: フォーカス移動
├─ Enter/Space: 選択
└─ Esc: 閉じる
```

#### 構造

```
Dropdown Menu
├─ Menu Item (clickable)
├─ Divider
├─ Menu Group (label付き)
│  ├─ Menu Item
│  ├─ Menu Item (with icon)
│  └─ Menu Item (disabled)
├─ Checkbox Menu Item
└─ Submenu (nested)
```

#### Tailwind実装例

```html
<!-- Dropdown Container -->
<div class="relative group">
  
  <!-- Trigger Button -->
  <button class="px-3 py-2 text-body font-medium text-primary-600
                 hover:bg-primary-50 rounded-md
                 flex items-center gap-2">
    メニュー
    <span class="group-open:rotate-180 transition-transform">▼</span>
  </button>
  
  <!-- Menu -->
  <div class="invisible group-open:visible absolute right-0 mt-1 w-48
              bg-white border border-neutral-200 rounded-md shadow-lg z-30
              opacity-0 group-open:opacity-100
              transform group-open:scale-100 scale-95
              origin-top-right transition-all duration-100
              dark:bg-neutral-900 dark:border-neutral-700">
    
    <!-- Menu Items -->
    <a href="#edit" class="block px-4 py-2 text-body text-primary
                          hover:bg-neutral-100
                          first:rounded-t-md">
      ✎ 編集
    </a>
    <a href="#copy" class="block px-4 py-2 text-body text-primary
                          hover:bg-neutral-100">
      📋 コピー
    </a>
    
    <!-- Divider -->
    <div class="my-1 border-t border-neutral-200"></div>
    
    <!-- Delete Item -->
    <button class="block w-full text-left px-4 py-2 text-body text-error-600
                   hover:bg-error-50">
      🗑 削除
    </button>
  </div>
</div>
```

---

### 2.4 Breadcrumb

**用途:** 階層的なナビゲーション・現在位置表示

#### 仕様

```
構造
├─ Home (アイコン)
├─ Divider (/)
├─ Page 1 (リンク)
├─ Divider (/)
├─ Current Page (テキスト・アクティブ)
└─ 最大: 5階層

折りたたみ
├─ モバイル: 最後の3階層のみ表示
└─ デスクトップ: 全階層表示

サイズ: 12px テキスト
```

#### Tailwind実装例

```html
<nav class="flex items-center gap-2 text-caption" aria-label="Breadcrumb">
  <a href="/" class="text-primary-600 hover:text-primary-700">
    🏠 ホーム
  </a>
  
  <span class="text-neutral-400">/</span>
  
  <a href="/sites" class="text-primary-600 hover:text-primary-700">
    サイト
  </a>
  
  <span class="text-neutral-400">/</span>
  
  <a href="/sites/1/posts" class="text-primary-600 hover:text-primary-700">
    投稿
  </a>
  
  <span class="text-neutral-400">/</span>
  
  <span class="text-neutral-900 font-medium" aria-current="page">
    React 19 解説
  </span>
</nav>
```

---

### 2.5 Pagination

**用途:** ページネーション・データテーブルナビ

#### 仕様

```
要素
├─ Previous Button
├─ Page Numbers (1,2,3...)
├─ Current Page Indicator (アクティブ)
├─ Next Button
└─ Page Info (オプション: "1-10 of 42")

最大表示ページ数: 7 (前後3 + 現在)

状態
├─ disabled (最初・最後のページ)
├─ active (現在)
└─ hover
```

#### Tailwind実装例

```html
<div class="flex items-center justify-between mt-6">
  
  <!-- Page Info -->
  <p class="text-caption text-neutral-600">
    1 〜 10 件を表示（全 42 件）
  </p>
  
  <!-- Pagination Controls -->
  <nav class="flex items-center gap-1">
    
    <!-- Previous -->
    <button class="px-3 py-2 text-primary-600 hover:bg-primary-50 rounded
                   disabled:text-neutral-400 disabled:hover:bg-transparent">
      ← 前へ
    </button>
    
    <!-- Page Numbers -->
    <button class="px-3 py-2 text-body text-neutral-700 hover:bg-neutral-100 rounded">
      1
    </button>
    <button class="px-3 py-2 text-body text-neutral-700 hover:bg-neutral-100 rounded">
      2
    </button>
    <button class="px-3 py-2 text-body text-white bg-primary-500 rounded font-medium">
      3
    </button>
    <button class="px-3 py-2 text-body text-neutral-700 hover:bg-neutral-100 rounded">
      4
    </button>
    
    <!-- Next -->
    <button class="px-3 py-2 text-primary-600 hover:bg-primary-50 rounded">
      次へ →
    </button>
  </nav>
</div>
```

---

### 2.6 Tabs

**用途:** 複数パネルの切り替え（フォーム・設定・詳細情報）

#### 仕様

```
配置
├─ 水平 (top / bottom) - 標準
└─ 垂直 (left / right)

バリエーション
├─ line (アンダーライン) - 推奨
├─ pill (背景色) - コンパクト
└─ card (カード型)

状態
├─ default / active / hover / disabled
└─ Indicator: underline / bg-color

キーボード操作
├─ ←/→: タブ移動
├─ Home/End: 最初/最後
└─ Enter/Space: 選択
```

#### Tailwind実装例

```html
<!-- Tab Container -->
<div>
  <!-- Tab List -->
  <div class="flex border-b border-neutral-200 dark:border-neutral-700">
    
    <!-- Tab 1 (Active) -->
    <button class="px-4 py-3 text-body font-medium text-primary-600
                   border-b-2 border-primary-500
                   hover:text-primary-700">
      一般情報
    </button>
    
    <!-- Tab 2 -->
    <button class="px-4 py-3 text-body font-medium text-neutral-600
                   border-b-2 border-transparent
                   hover:border-neutral-300
                   hover:text-neutral-900">
      詳細設定
    </button>
    
    <!-- Tab 3 -->
    <button class="px-4 py-3 text-body font-medium text-neutral-600
                   border-b-2 border-transparent
                   hover:border-neutral-300
                   disabled:text-neutral-300 disabled:cursor-not-allowed">
      高度な設定
    </button>
  </div>
  
  <!-- Tab Panels -->
  <div class="p-6">
    <!-- Panel 1 (Active) -->
    <div id="panel-1" class="space-y-4">
      <h3 class="text-h4 font-bold">一般情報</h3>
      <p class="text-body text-neutral-700">
        パネルの内容がここに表示されます
      </p>
    </div>
    
    <!-- Panel 2 (Hidden) -->
    <div id="panel-2" class="hidden">
      <h3 class="text-h4 font-bold">詳細設定</h3>
    </div>
  </div>
</div>
```

---

### 2.7 Accordion

**用途:** FAQ・設定項目・折りたたみ詳細情報

#### 仕様

```
バリアンス
├─ single (1つのみ展開可能)
└─ multiple (複数同時展開)

アニメーション
├─ 展開: 200ms height fade-in
└─ 折りたたみ: 100ms height fade-out

キーボード操作
├─ Enter/Space: 切り替え
└─ ↓/↑: 次/前のアイテムフォーカス
```

#### Tailwind実装例

```html
<!-- Accordion Container -->
<div class="space-y-2 border border-neutral-200 rounded-lg divide-y divide-neutral-200">
  
  <!-- Accordion Item 1 -->
  <div class="group">
    
    <!-- Trigger -->
    <button class="w-full px-4 py-3 flex items-center justify-between
                   hover:bg-neutral-50
                   group-open:bg-primary-50"
            aria-expanded="false"
            aria-controls="content-1">
      <span class="text-body font-medium text-primary">
        サービスについて
      </span>
      <span class="text-xl group-open:rotate-180 transition-transform">
        ▼
      </span>
    </button>
    
    <!-- Content -->
    <div id="content-1" class="hidden group-open:block px-4 py-3
                               border-t border-neutral-200">
      <p class="text-body text-neutral-700">
        Let's Blog Server は...の説明がここに入ります
      </p>
    </div>
  </div>
  
  <!-- Accordion Item 2 -->
  <div class="group">
    <button class="w-full px-4 py-3 flex items-center justify-between
                   hover:bg-neutral-50">
      <span class="text-body font-medium text-primary">
        料金について
      </span>
      <span class="text-xl group-open:rotate-180 transition-transform">
        ▼
      </span>
    </button>
    <div class="hidden group-open:block px-4 py-3 border-t">
      <p class="text-body text-neutral-700">
        料金体系の説明がここに入ります
      </p>
    </div>
  </div>
</div>
```

---

## 3. レイアウトコンポーネント

### 3.1 Header / Navbar

**用途:** ページ上部ナビゲーション

#### 構造

```
Header
├─ Logo / Branding
├─ Navigation Menu (デスクトップ)
├─ User Menu / Auth
└─ Hamburger Menu (モバイル)

高さ: 64px（標準）

固定配置
├─ sticky (スクロール時も表示)
└─ static (デフォルト)
```

#### Tailwind実装例

```html
<header class="sticky top-0 z-40 bg-white border-b border-neutral-200
               shadow-sm dark:bg-neutral-900 dark:border-neutral-700">
  <div class="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
    
    <!-- Logo -->
    <div class="flex items-center gap-2">
      <div class="w-8 h-8 bg-primary-500 rounded-lg flex items-center justify-center
                  text-white font-bold">
        L
      </div>
      <span class="text-h4 font-bold hidden sm:inline">
        Let's Blog
      </span>
    </div>
    
    <!-- Navigation (Desktop) -->
    <nav class="hidden md:flex items-center gap-6">
      <a href="/dashboard" class="text-body text-neutral-700 hover:text-primary-600">
        ダッシュボード
      </a>
      <a href="/sites" class="text-body text-neutral-700 hover:text-primary-600">
        サイト
      </a>
      <a href="/docs" class="text-body text-neutral-700 hover:text-primary-600">
        ドキュメント
      </a>
    </nav>
    
    <!-- User Menu -->
    <div class="flex items-center gap-3">
      <button class="w-10 h-10 rounded-full bg-primary-500 text-white
                     flex items-center justify-center">
        U
      </button>
      <button class="md:hidden text-neutral-700">
        ☰
      </button>
    </div>
  </div>
</header>
```

---

### 3.2 Sidebar

**用途:** サイドナビゲーション（ダッシュボード・管理画面）

#### 仕様

```
幅
├─ デスクトップ: 256px
├─ タブレット/モバイル: collapse or drawer
└─ 折りたたみ: 64px icon-only

コンテンツ
├─ ロゴ
├─ ナビゲーションメニュー
├─ アクティブインジケーター
└─ コラプス・展開機能

位置
├─ 固定（スクロール時も表示）
└─ ページ横並び
```

#### Tailwind実装例

```html
<!-- Sidebar -->
<aside class="w-64 bg-neutral-50 border-r border-neutral-200
              flex flex-col dark:bg-neutral-900 dark:border-neutral-700">
  
  <!-- Logo Section -->
  <div class="px-6 py-4 border-b border-neutral-200">
    <div class="flex items-center gap-2">
      <div class="w-8 h-8 bg-primary-500 rounded-lg flex items-center justify-center
                  text-white font-bold">
        L
      </div>
      <span class="text-h4 font-bold">Blog</span>
    </div>
  </div>
  
  <!-- Navigation Menu -->
  <nav class="flex-1 px-3 py-4 space-y-2">
    
    <!-- Menu Item (Active) -->
    <a href="/dashboard" class="block px-3 py-2 rounded-md bg-primary-100
                               text-primary-700 font-medium border-l-4 border-primary-500">
      📊 ダッシュボード
    </a>
    
    <!-- Menu Item (Inactive) -->
    <a href="/sites" class="block px-3 py-2 rounded-md text-neutral-700
                           hover:bg-neutral-100">
      🌐 サイト管理
    </a>
    
    <!-- Menu Item -->
    <a href="/posts" class="block px-3 py-2 rounded-md text-neutral-700
                           hover:bg-neutral-100">
      📝 投稿管理
    </a>
  </nav>
  
  <!-- Bottom Section -->
  <div class="px-3 py-4 border-t border-neutral-200 space-y-2">
    <button class="w-full px-3 py-2 text-left text-body text-neutral-700
                   hover:bg-neutral-100 rounded-md">
      ⚙️ 設定
    </button>
    <button class="w-full px-3 py-2 text-left text-body text-neutral-700
                   hover:bg-neutral-100 rounded-md">
      🚪 ログアウト
    </button>
  </div>
</aside>
```

---

### 3.3 Footer

**用途:** ページ下部（コピーライト・リンク・情報）

#### 構造

```
Footer
├─ Logo / Brand Info
├─ Links Grid
│  ├─ Company
│  ├─ Product
│  ├─ Resources
│  └─ Legal
├─ Newsletter Signup (オプション)
├─ Social Links
└─ Copyright
```

#### Tailwind実装例

```html
<footer class="bg-neutral-900 text-neutral-300 mt-16">
  <div class="max-w-7xl mx-auto px-4 py-12">
    
    <!-- Content Grid -->
    <div class="grid md:grid-cols-4 gap-8 mb-8">
      
      <!-- Brand -->
      <div>
        <div class="flex items-center gap-2 mb-4">
          <div class="w-8 h-8 bg-primary-500 rounded-lg"></div>
          <span class="font-bold text-white">Let's Blog</span>
        </div>
        <p class="text-caption text-neutral-400">
          シンプルで強力なブログプラットフォーム
        </p>
      </div>
      
      <!-- Links Group -->
      <div>
        <h4 class="font-semibold text-white mb-4">製品</h4>
        <ul class="space-y-2">
          <li><a href="#" class="text-caption hover:text-white">ダッシュボード</a></li>
          <li><a href="#" class="text-caption hover:text-white">デザイン</a></li>
          <li><a href="#" class="text-caption hover:text-white">AI アシスタント</a></li>
        </ul>
      </div>
      
      <!-- More Links -->
      <div>
        <h4 class="font-semibold text-white mb-4">リソース</h4>
        <ul class="space-y-2">
          <li><a href="#" class="text-caption hover:text-white">ドキュメント</a></li>
          <li><a href="#" class="text-caption hover:text-white">ブログ</a></li>
          <li><a href="#" class="text-caption hover:text-white">サポート</a></li>
        </ul>
      </div>
      
      <!-- Legal -->
      <div>
        <h4 class="font-semibold text-white mb-4">法務</h4>
        <ul class="space-y-2">
          <li><a href="#" class="text-caption hover:text-white">プライバシー</a></li>
          <li><a href="#" class="text-caption hover:text-white">利用規約</a></li>
          <li><a href="#" class="text-caption hover:text-white">お問い合わせ</a></li>
        </ul>
      </div>
    </div>
    
    <!-- Divider -->
    <div class="border-t border-neutral-800 pt-8">
      <div class="flex justify-between items-center">
        <p class="text-caption text-neutral-500">
          © 2024 Let's Blog Server. All rights reserved.
        </p>
        <div class="flex gap-4">
          <a href="#" class="text-neutral-400 hover:text-white">Twitter</a>
          <a href="#" class="text-neutral-400 hover:text-white">GitHub</a>
          <a href="#" class="text-neutral-400 hover:text-white">LinkedIn</a>
        </div>
      </div>
    </div>
  </div>
</footer>
```

---

## 4. Penpot 実装ガイド

### 4.1 コンポーネント作成手順

1. **基本シェイプ作成**
   - フレーム：コンポーネント本体
   - シェイプ：ボタン・入力フィールド等
   - テキスト：ラベル・説明文

2. **Main Component 定義**
   - フレーム → Assets パネル → "Make a component"
   - コンポーネント名：`Button / Primary / Medium`（命名規則）

3. **Variants 作成**
   - 右クリック → "Create component set"
   - Properties を追加：Size, Variant, State
   - 各状態のデザイン定義

4. **CSS/Tailwind マッピング**
   - コンポーネント名と Tailwind クラスを対応
   - Design Tokens JSON 生成

### 4.2 命名規則

```
[Category] / [Component] / [Property]

例:
- Input / Text / Default
- Button / Primary / Large / Hover
- Badge / Success / Pill
- Card / Elevated / With Image
```

### 4.3 Design Tokens エクスポート

各コンポーネント定義から以下を自動生成：

```json
{
  "components": {
    "Button": {
      "variants": {
        "primary-solid-md": {
          "tailwind": "px-4 py-2 bg-primary-500 text-white...",
          "css": {
            "backgroundColor": "var(--color-primary-500)",
            ...
          }
        }
      }
    }
  }
}
```

---

## 5. React 実装ガイド

### 5.1 コンポーネントファイル構造

```
apps/web/src/components/
├── ui/
│   ├── Button.tsx (基本コンポーネント)
│   ├── Input.tsx
│   ├── Card.tsx
│   └── ...
├── layout/
│   ├── Header.tsx
│   ├── Sidebar.tsx
│   ├── Footer.tsx
│   └── Container.tsx
├── composite/
│   ├── Modal.tsx
│   ├── Dropdown.tsx
│   ├── Tabs.tsx
│   └── ...
└── hooks/
    ├── useModal.ts
    ├── useDropdown.ts
    └── ...
```

### 5.2 Props インターフェース設計

```typescript
// 基本パターン
interface ComponentProps extends React.HTMLAttributes<HTMLElement> {
  // Variants
  variant?: 'primary' | 'secondary' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  
  // States
  disabled?: boolean;
  loading?: boolean;
  
  // Customization
  className?: string;
  children?: React.ReactNode;
}
```

### 5.3 スタイリング戦略

```typescript
// Tailwind クラスのマージ（clsx や classnames 使用）
import { clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

const cn = (...classes: unknown[]) => twMerge(clsx(classes));

// コンポーネント内で使用
const buttonClasses = cn(
  baseClasses,
  variantStyles[variant],
  sizeClasses[size],
  className // ユーザー指定クラスは最後に（上書き可能）
);
```

---

## 6. アクセシビリティ チェックリスト

### 6.1 全コンポーネント共通

- [ ] WCAG AA カラーコントラスト（最小 4.5:1）
- [ ] キーボード操作全対応（Tab・Enter・Arrow等）
- [ ] Focus outline 表示（`:focus-visible`）
- [ ] aria-label / aria-labelledby 設定
- [ ] role 属性適切設定
- [ ] スクリーンリーダー対応（セマンティック HTML）
- [ ] 最小タッチターゲット: 44px × 44px

### 6.2 フォームコンポーネント

- [ ] `<label>` 要素と関連付け
- [ ] エラーメッセージ `aria-describedby` 連携
- [ ] 必須フィールド `aria-required="true"`
- [ ] パスワード入力 `type="password"`

### 6.3 インタラクティブコンポーネント

- [ ] Dialog/Modal: focus トラップ
- [ ] Menu: keyboard nav (↓/↑/Home/End)
- [ ] Tab: `aria-selected` / `role="tab"`
- [ ] Button: `aria-pressed` (toggle時)

---

## 7. ダークモード実装

### 7.1 CSS 変数戦略

```css
/* Light Mode (Default) */
:root {
  --bg-primary: white;
  --text-primary: #212121;
  --border-color: #E0E0E0;
}

/* Dark Mode */
@media (prefers-color-scheme: dark) {
  :root {
    --bg-primary: #121212;
    --text-primary: white;
    --border-color: #424242;
  }
}

/* Manual Toggle */
.dark {
  --bg-primary: #121212;
  --text-primary: white;
  --border-color: #424242;
}
```

### 7.2 Tailwind Dark Mode

```html
<div class="bg-white dark:bg-neutral-900 text-neutral-900 dark:text-white">
  Light mode background with dark mode variant
</div>
```

---

## 8. コンポーネント完成チェックリスト

各コンポーネント作成時に確認：

- [ ] Penpot コンポーネント作成（Main + Variants）
- [ ] Tailwind CSS 実装例を文書化
- [ ] React コンポーネント型定義作成
- [ ] アクセシビリティ要件記載
- [ ] ダークモード対応確認
- [ ] スクリーンショット・デモ追加
- [ ] ドキュメント記載

---

## 9. 実装優先度

### Phase 1（基本・高優先度）
1. Button - ★★★ （全ページで使用）
2. Input / Textarea - ★★★ （フォーム必須）
3. Card - ★★★ （レイアウト基盤）
4. Header / Sidebar - ★★★ （ナビゲーション）

### Phase 2（重要）
5. Modal / Dropdown - ★★
6. Alert / Toast - ★★
7. Badge / Tag - ★★
8. Tabs / Accordion - ★★

### Phase 3（補助）
9. Avatar / Progress - ★
10. Tooltip / Spinner - ★
11. Breadcrumb / Pagination - ★

---

## 10. 参考資料・ツール

### デザインツール
- Penpot: https://penpot.app/
- Tailwind CSS: https://tailwindcss.com/
- Color Contrast Checker: https://webaim.org/resources/contrastchecker/

### アクセシビリティ
- WCAG 2.1: https://www.w3.org/WAI/WCAG21/quickref/
- A11y Checklist: https://www.a11yproject.com/checklist/
- ARIA Authoring Practices: https://www.w3.org/WAI/ARIA/apg/

### ドキュメント
- Headless UI: https://headlessui.com/
- Radix UI: https://www.radix-ui.com/
- shadcn/ui: https://ui.shadcn.com/

---

## 11. 今後の拡張

- [ ] Storybook 統合（コンポーネント カタログ）
- [ ] イアウント/アニメーション ガイドライン
- [ ] Internationalization（多言語対応）
- [ ] Design Tokens JSON 自動生成スクリプト
- [ ] Penpot → Figma マイグレーション

---

**完成日:** 2024-08-07  
**バージョン:** 1.0  
**次ステップ:** C-5 Page デザイン（ダッシュボード・サイト・投稿ページ等）
