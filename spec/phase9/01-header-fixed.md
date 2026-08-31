# 01. ヘッダーメニューのスクロール固定表示

## 目的

管理画面のヘッダー(ロゴ・ナビゲーション・ユーザー情報)を、ページを上下スクロールする際に常に画面上部に貼り付けて表示することで、ナビゲーションがスクロール時に見えなくなるというUX問題を解決する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 実装方式 | `position: sticky`(fixed ではなく、レイアウト自動調整で将来ズレにくい) |
| z-index | `z-40`(将来の modal/dropdown が出ても最前面) |
| 対象ファイル | `web/src/app/layout.tsx`(root layout Server Component) |
| スコープ内容 | ヘッダー行(`<header>`)の Tailwind className 修正のみ |
| スコープ外 | header 内部の nav `overflow-x-auto` は既存のまま(横スクロール、別軸の仕組み) |

## アーキテクチャ・実装詳細

### 現状の HTML 構造

`web/src/app/layout.tsx` より(24〜72行目、Server Component):

```html
<html>
  <body className="min-h-full flex flex-col bg-neutral-50 text-neutral-900">
    <header className="border-b border-neutral-200 bg-white">
      <!-- sticky/fixed の Tailwind class なし。通常フロー内の兄弟要素 -->
      <div className="mx-auto flex max-w-7xl items-center gap-6 px-4 py-3">
        <span className="shrink-0 font-semibold">Let's Blog Server</span>
        <nav className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto text-sm">
          <!-- NAV_ITEMS/ADMIN_NAV_ITEMS、lucide-react icons -->
        </nav>
        <div className="ml-auto flex shrink-0 items-center gap-4 text-sm">
          <!-- user email + LogoutButton -->
        </div>
      </div>
    </header>
    <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-8">
      {children}
    </main>
  </body>
</html>
```

### 現在の挙動

- `<body>` は `flex flex-col` で通常の flex レイアウト(スクロール可能なdocument flow)
- `<header>` は何も positioning が指定されていないため、in-flow な兄弟要素として`<main>`と同じくスクロール対象になる
- 結果: ページを上にスクロールするとヘッダーが上端で隠れる

### 修正内容

`<header>` の `className` を以下のように変更:

```tsx
// Before
<header className="border-b border-neutral-200 bg-white">

// After
<header className="sticky top-0 z-40 border-b border-neutral-200 bg-white">
```

### `position: sticky` vs `position: fixed` の選択理由

| 方式 | 利点 | 欠点 |
|---|---|---|
| **sticky** (採用) | ① flex レイアウト内で normal flow を保持 / ② `<main>` へのpadding-top 手動調整不要 / ③ 将来ヘッダー高さが変わっても自動に追従 | なし |
| fixed | ① 常に viewport 固定、scroll 性能優位 | ① `<main>` の `margin-top` を header height と同じだけ確保する手動調整が必須 / ② header 高さが 48px→64px に変わるたびにズレる / ③ レイアウト破壊のリスク |

→ `sticky top-0` で十分であり、将来メンテナンスが楽。

### z-index: z-40 の選択理由

- 現状のコード内に z-index 指定がほぼ無い → 将来 modal(`z-50` 程度)が出ることを想定して `z-40` を指定しておく余裕
- `z-9999` 等の極端な値は避ける(Tailwind の scale に収まる `z-40` 推奨)

## スコープ・実装項目

実装対象:

- [x] `web/src/app/layout.tsx` line 39 の `<header>` className に `sticky top-0 z-40` 追加

対象外・スコープ外:

- ヘッダー高さの自動計算・動的調整
- ヘッダー内の `<nav>` 横スクロール(既存の `overflow-x-auto` はそのまま)
- header shadow/border styling の強化(既存の `border-b` で十分)

## 実装順序

1. `web/src/app/layout.tsx` の該当行を修正(1行の変更)
2. `docker compose up` 等で開発環境再起動
3. 実機検証(以下)
4. git commit

## テスト整備

**Unit test**: CSS の挙動なので単体テストは書きようがない。layout.tsx 自体のJSX 構造は既存テストで十分。

**E2E/実機検証**: ブラウザで確認(ユニットテスト向きではない挙動なため)

## 実機検証

1. `docker compose up -d --build` でコンテナ再起動
2. 管理画面にログイン
3. ページ内容が長い場合(例: ユーザー一覧が多数行等)、**ページ内容を上下にスクロール**してヘッダーが常に画面上部に貼り付いて見える(scroll out しない)ことを確認
4. header 内のナビゲーションリンク(ダッシュボード/ユーザー等)が normal に動作すること確認
5. 横長コンテンツ(テーブル等)のときに、nav 内の `overflow-x-auto` が独立して横スクロール(ページ本体の上下スクロールと独立)できることを確認

