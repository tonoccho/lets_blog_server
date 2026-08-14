# recharts-renderer

`[recharts]` 組み込みタグ(Issue #340)が使う、React + Recharts をサーバーサイドで1回だけ実行して
静的なHTML/SVGへ変換するためのビルド元。

## 仕組み

- `RechartsTagRenderService`(Java)が `[recharts]...[/recharts]` ブロックをパース・検証し、
  チャート設定(型・データ・配色等)を組み立てる。
- `RechartsRenderer`(Java)が、共有のPlaywright `Browser` で新しいページを開き、
  `renderer.bundle.js`(このディレクトリのビルド成果物)を読み込んで `window.renderChart(config)` を呼び出す。
- Recharts(内部的にReact/ReactDOM)がSVG+HTML(凡例等)をブラウザ内でレンダリングし、
  その結果のDOM(`.recharts-wrapper` 要素)の `outerHTML` を静的な文字列として取り出す。
- 取り出した静的HTML/SVGをそのまま記事のHTMLへ埋め込む。**React/Recharts自体は公開記事にも
  プレビューのWebviewにも一切配信されない**(サーバー内部のレンダリングにのみ使う使い捨てのブラウザ)。

この方式により、記事プレビュー(VSCode拡張のWebview)と公開後のWordPress記事の両方で、
追加のJSランタイムなしに同一の静的マークアップが表示される。

## ビルド方法

rechartsのバージョンアップやハーネスのロジック変更が必要になった場合のみ、手動で再ビルドする
(通常のAPIビルド・デプロイでは実行不要。`renderer.bundle.js` は生成物としてリポジトリにコミット済み)。

```bash
cd api/tools/recharts-renderer
npm install
npm run build
```

`../../src/main/resources/recharts/renderer.bundle.js` が更新される。差分を確認の上コミットすること。

## ライセンス

React / ReactDOM / Recharts はいずれもMIT Licenseで配布されている。
