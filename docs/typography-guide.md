# Let's Blog タイポグラフィガイド

## 1. フォント選定

### 1.1 英語フォント

**見出し・UI:** Inter
- 特徴: モダン・ニュートラル・非常に読みやすい
- ウェイト: 400(Regular), 600(SemiBold), 700(Bold)
- 用途: Heading, Button, Label

**本体・UI:** Inter
- 特徴: 優れた可読性・Web最適化
- ウェイト: 400(Regular)
- 用途: Body Text, Form inputs

**コード・等幅:** Fira Code または Monaco
- 特徴: 開発者向け・シンボル表示・Ligature対応
- ウェイト: 400(Regular), 500(Medium)
- 用途: Code blocks, monospace content

**フォールバック:**
```
見出し: "Inter", "Segoe UI", "Roboto", sans-serif
本文: "Inter", "Segoe UI", "-apple-system", sans-serif
コード: "Fira Code", "Monaco", "Courier New", monospace
```

### 1.2 日本語フォント

**優先度別:**

| 優先度 | フォント | 形式 | 用途 |
|--------|---------|------|------|
| 1 | Noto Sans JP | Variable | 見出し・本文・UI |
| 2 | Hiragino Sans | System | システムフォント（macOS） |
| 3 | Segoe UI | System | System font (Windows) |
| 4 | sans-serif | Fallback | 汎用フォント |

**フォント読み込み:**
```css
@import url('https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;500;600;700&display=swap');
```

**フォント定義:**
```css
.noto-sans-jp {
  font-family: 'Noto Sans JP', 'Hiragino Sans', 'Segoe UI', sans-serif;
}
```

### 1.3 フォント読み込み戦略

**Strategy: font-display: swap**
- 即座に代替フォント表示
- Web フォント読み込み後に置き換え
- ユーザーが即座にコンテンツを読める

**読み込み順序:**
1. 英語フォント（Inter）→ 30KB
2. 日本語フォント（Noto Sans JP）→ 200KB（variable）
3. 合計: 230KB（gzip 圧縮後 40-50KB）

---

## 2. テキストスタイル体系

### 2.1 Display（大見出し・ランディング）

#### Display 1
```
Font Size: 48px
Font Weight: 700
Line Height: 1.1 (52.8px)
Letter Spacing: -1px
```

**用途:**
- ページのメインタイトル
- キャンペーンスローガン
- ランディングページ

**例:**
```html
<h1 class="text-5xl font-bold leading-tight">
  Let's Blog Server
</h1>
```

#### Display 2
```
Font Size: 40px
Font Weight: 700
Line Height: 1.1 (44px)
Letter Spacing: -0.5px
```

**用途:**
- セクションタイトル
- ページ区切り

### 2.2 Heading（見出し）

#### Heading 1
```
Font Size: 32px
Font Weight: 700
Line Height: 1.2 (38.4px)
Letter Spacing: 0px
```

**用途:** ページセクション見出し

```html
<h1 class="text-4xl font-bold">セクション見出し</h1>
```

#### Heading 2
```
Font Size: 24px
Font Weight: 700
Line Height: 1.2 (28.8px)
Letter Spacing: 0px
```

**用途:** サブセクション見出し

#### Heading 3
```
Font Size: 20px
Font Weight: 700
Line Height: 1.3 (26px)
Letter Spacing: 0px
```

**用途:** 小見出し・フォームセクション

#### Heading 4
```
Font Size: 18px
Font Weight: 600
Line Height: 1.3 (23.4px)
Letter Spacing: 0px
```

**用途:** カード見出し・ダイアログタイトル

### 2.3 Body（本文）

#### Body Large
```
Font Size: 16px
Font Weight: 400
Line Height: 1.6 (25.6px)
Letter Spacing: 0px
```

**用途:** リード文・重要な説明テキスト

#### Body (Default)
```
Font Size: 14px
Font Weight: 400
Line Height: 1.6 (22.4px)
Letter Spacing: 0px
```

**用途:** 通常の本文テキスト・説明

**最も利用頻度が高い** → 最も読みやすくする

#### Body Small
```
Font Size: 12px
Font Weight: 400
Line Height: 1.6 (19.2px)
Letter Spacing: 0px
```

**用途:** メタ情報・補足説明・フォームヘルプテキスト

### 2.4 UI テキスト

#### Button
```
Font Size: 14px
Font Weight: 600
Line Height: 1.0
Letter Spacing: 0px
Text Transform: none
```

**特徴:**
- Bold で目立つ
- 単一行（白スペースなし）
- 操作を強調

```html
<button class="text-sm font-semibold">
  投稿する
</button>
```

#### Label
```
Font Size: 12px
Font Weight: 500
Line Height: 1.0
Letter Spacing: 0.5px
Text Transform: uppercase (optional)
```

**用途:** フォームラベル・テーブルヘッダ・タグ

#### Caption
```
Font Size: 11px
Font Weight: 400
Line Height: 1.5
Letter Spacing: 0px
Color: neutral-600
```

**用途:** 画像キャプション・著者・日付・メタ情報

```html
<p class="text-xs text-neutral-600">
  2024年8月7日 by 著者名
</p>
```

#### Overline
```
Font Size: 12px
Font Weight: 600
Line Height: 1.4
Letter Spacing: 1px
Text Transform: uppercase
Color: neutral-700
```

**用途:** セクション区分・カテゴリラベル

---

## 3. ライン高さ・文字間隔

### 3.1 Line Height（行高）

**原則:**
- 見出し: 1.1 - 1.3（タイトなスペーシング）
- 本文: 1.5 - 1.6（読みやすさ重視）
- UI: 1.0 - 1.2（コンパクト）

**公式:**
```
Line Height（px） = Font Size（px） × Line Height（倍数）

例: 16px × 1.6 = 25.6px
```

### 3.2 Letter Spacing（文字間隔）

| シーン | Letter Spacing | 備考 |
|--------|---------------|----|
| 見出し | -0.5px～0px | やや狭い（活動的） |
| 本文 | 0px | 標準 |
| ラベル | 0.5px～1px | やや広い（強調） |
| Overline | 1px | 広い（視覚的分断） |

---

## 4. Tailwind CSS 実装

### 4.1 Tailwind Config 拡張

**ファイル:** `apps/web/tailwind.config.ts`

```typescript
export default {
  theme: {
    extend: {
      fontFamily: {
        sans: [
          'Inter',
          'Noto Sans JP',
          'Segoe UI',
          '-apple-system',
          'sans-serif',
        ],
        mono: ['Fira Code', 'Monaco', 'Courier New', 'monospace'],
      },
      fontSize: {
        // Display
        'display-1': ['48px', { lineHeight: '52.8px', letterSpacing: '-1px' }],
        'display-2': ['40px', { lineHeight: '44px', letterSpacing: '-0.5px' }],
        
        // Heading
        'h1': ['32px', { lineHeight: '38.4px', fontWeight: '700' }],
        'h2': ['24px', { lineHeight: '28.8px', fontWeight: '700' }],
        'h3': ['20px', { lineHeight: '26px', fontWeight: '700' }],
        'h4': ['18px', { lineHeight: '23.4px', fontWeight: '600' }],
        
        // Body
        'body-lg': ['16px', { lineHeight: '25.6px' }],
        'body': ['14px', { lineHeight: '22.4px' }],
        'body-sm': ['12px', { lineHeight: '19.2px' }],
        
        // UI
        'btn': ['14px', { lineHeight: '1', fontWeight: '600' }],
        'label': ['12px', { lineHeight: '1', fontWeight: '500', letterSpacing: '0.5px' }],
        'caption': ['11px', { lineHeight: '1.5' }],
        'overline': ['12px', { lineHeight: '1.4', fontWeight: '600', letterSpacing: '1px' }],
      },
      lineHeight: {
        'tight': '1.1',
        'snug': '1.2',
        'normal': '1.3',
        'relaxed': '1.6',
      },
      letterSpacing: {
        'tighter': '-1px',
        'tight': '-0.5px',
        'normal': '0px',
        'wide': '0.5px',
        'wider': '1px',
      },
    },
  },
};
```

### 4.2 CSS クラス例

```html
<!-- Display -->
<h1 class="text-display-1 font-bold">ページタイトル</h1>

<!-- Heading -->
<h2 class="text-h2">セクション見出し</h2>
<h3 class="text-h3">サブセクション</h3>

<!-- Body -->
<p class="text-body">通常の本文テキスト</p>
<p class="text-body-sm text-neutral-600">メタ情報</p>

<!-- UI -->
<button class="text-btn">操作ボタン</button>
<label class="text-label">フォームラベル</label>
```

---

## 5. 日本語フォント実装

### 5.1 字体（フォント）

**ウェイト選択:**

| 内容 | ウェイト | 用途 |
|------|---------|------|
| 見出し | 700 (Bold) | h1, h2, 強調 |
| UI テキスト | 600 (SemiBold) | ボタン、ラベル |
| 本文 | 400 (Regular) | 本文、説明 |

### 5.2 ルビ（Ruby）処理

大きなテキスト（見出し）には不要。
小さなテキスト（本文）でも通常は不要（日本語フォントが最適化）。

### 5.3 字間（character spacing）

日本語は自動調整が適切。明示的な `letter-spacing` はまれ。

---

## 6. アクセシビリティ

### 6.1 最小フォントサイズ

**WCAG AAA 要件:**
- 最小サイズ: 12px
- 本文: 14px 以上推奨

✓ 当ガイド: すべて 11px 以上

### 6.2 コントラスト比

| テキスト | 背景 | 比率 | WCAG |
|---------|------|------|------|
| neutral-900 | white | 17.3:1 | AAA |
| neutral-800 | white | 9.5:1 | AAA |
| neutral-700 | white | 6.2:1 | AA |
| neutral-600 | white | 4.5:1 | AA |
| primary-500 | white | 5.2:1 | AA |

### 6.3 行高

最小 1.5 倍 → フォーカス文字で読みやすさ確保

---

## 7. レスポンシブ対応

### 7.1 デバイス別フォントサイズ

| デバイス | 本文 | 見出し |
|---------|------|--------|
| デスクトップ | 14px | 32px |
| タブレット | 14px | 28px |
| モバイル | 14px | 24px |

**Tailwind 実装:**
```html
<h2 class="text-2xl md:text-3xl lg:text-4xl">
  見出し
</h2>
```

### 7.2 行高・文字間隔

デバイス変更時は保持（相対単位使用）

---

## 8. テキスト配色パターン

### 8.1 テキストカラークラス

```css
/* Primary text */
.text-primary {
  color: var(--color-neutral-900);
}

.dark .text-primary {
  color: #FFFFFF;
}

/* Secondary text */
.text-secondary {
  color: var(--color-neutral-700);
}

.dark .text-secondary {
  color: #E0E0E0;
}

/* Muted text */
.text-muted {
  color: var(--color-neutral-500);
}

.dark .text-muted {
  color: #BDBDBD;
}
```

---

## 9. コンポーネント実装例

### 見出し + 説明文
```html
<div>
  <h2 class="text-h2 text-primary">記事タイトル</h2>
  <p class="text-body text-secondary mt-2">
    記事の説明テキストがここに入ります
  </p>
  <p class="text-caption text-muted mt-1">
    2024年8月7日 by 著者名
  </p>
</div>
```

### フォーム
```html
<div class="space-y-4">
  <div>
    <label class="text-label text-primary block mb-2">
      記事タイトル
    </label>
    <input
      type="text"
      class="text-body w-full px-3 py-2 border border-neutral-300
             focus:outline-none focus:ring-2 focus:ring-primary-500"
      placeholder="タイトルを入力"
    />
  </div>
</div>
```

### データテーブル
```html
<table>
  <thead>
    <tr>
      <th class="text-label text-primary">項目</th>
      <th class="text-label text-primary">値</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td class="text-body text-primary">記事数</td>
      <td class="text-body-sm text-secondary">42</td>
    </tr>
  </tbody>
</table>
```

---

## 10. チェックリスト

- [ ] フォント読み込み速度確認（Lighthouse）
- [ ] 日本語テキストの字体確認（macOS/Windows/Linux）
- [ ] ダークモード時の読みやすさ確認
- [ ] モバイルデバイスでの行高確認
- [ ] スクリーンリーダー対応確認
- [ ] 印刷時の見た目確認

---

## 11. 参考資料

- Google Fonts: https://fonts.google.com/
- Inter Font: https://rsms.me/inter/
- Noto Sans JP: https://fonts.google.com/noto/specimen/Noto+Sans+JP
- Tailwind Typography: https://tailwindcss.com/docs/font-size
- Web 安全フォント: https://www.w3schools.com/cssref/css_websafe_fonts.php
