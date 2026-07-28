# 03. UIコントラスト改善

## 目的

管理画面(Web フロントエンド)の文字が薄く判読しづらいというフィードバックを受け、低コントラスト箇所を洗い出して修正する。

## 現状調査結果

- ログインページ: [`web/src/app/login/page.tsx`](../../web/src/app/login/page.tsx)。ラベルは `text-neutral-600`、枠線は `border-neutral-300`、送信ボタンは `bg-neutral-900 text-white`。
- 無効化状態のボタン(`disabled:opacity-50`)が [`login/page.tsx`](../../web/src/app/login/page.tsx)、[`web/src/app/sites/SiteForm.tsx`](../../web/src/app/sites/SiteForm.tsx)、[`web/src/app/users/UserForm.tsx`](../../web/src/app/users/UserForm.tsx)、[`web/src/app/users/DeleteUserButton.tsx`](../../web/src/app/users/DeleteUserButton.tsx) で共通パターンとして使われており、白文字+50%不透明度でコントラスト比が不足している可能性が高い。
- グローバルテーマ: [`web/src/app/globals.css`](../../web/src/app/globals.css) に `--foreground: #171717`(ライト)/`#ededed`(`prefers-color-scheme: dark`)を定義。一方でログインページ等のフォームは `bg-white` 固定でこのテーマ変数を参照していない。OS側がダークモードの場合、ページ全体の文字色はテーマ変数(`#ededed`)を使う部分と `bg-white` 固定のフォーム内(`neutral-*`)とで不整合が生じる。

## 前提・決定事項(要確認)

- ダークモード対応まで含めるか、まずライトモード固定で統一するかは未決(下記「未決事項」参照)。本ドキュメントでは暫定的に「ライトモード固定で統一し、フォームは常に `bg-white` + 十分なコントラストの文字色を使う」方針を前提に記載する。
- コントラスト比は WCAG 2.1 AA 基準(通常文字 4.5:1 以上、大きい文字 3:1 以上)を満たすことを目標とする。

## 対応方針

1. `disabled:opacity-50` パターンを見直し、無効化状態でも文字とボタン背景のコントラスト比が基準を満たすよう調整する(例: 背景色を薄くする代わりに文字色はそのまま保つ、または `disabled:text-neutral-300 disabled:bg-neutral-100` のような明示的な配色に変更)。
2. フォームラベル・補助テキストで `text-neutral-400` 以下の薄い色を使っている箇所があれば `text-neutral-600` 以上に引き上げる。
3. `globals.css` のテーマ変数とフォームコンポーネントの配色方針を統一する(ダークモード対応を含めるかは未決事項の結論待ち)。

## タスクチェックリスト

- [ ] `web/src/app/login/page.tsx`、`web/src/app/sites/SiteForm.tsx`、`web/src/app/users/UserForm.tsx`、`web/src/app/users/DeleteUserButton.tsx` の低コントラスト箇所を洗い出す(ブラウザの開発者ツールやコントラストチェッカーで実測)
- [ ] 無効化状態ボタンの配色を WCAG AA 準拠に修正
- [ ] `globals.css` のテーマ変数とフォームの配色方針を統一(ライトモード固定 or ダークモード対応、未決事項の結論に従う)
- [ ] 修正後、実際にブラウザで表示確認(ライト/ダーク両方、決定した対応範囲に応じて)

## 未決事項

- ダークモード対応まで含めるか、まずはライトモード固定で修正するか
- コントラストチェックを自動化するか(Lighthouse等のCI導入)、目視確認のみで済ませるか
