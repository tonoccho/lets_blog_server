# 拡張の受け入れテスト(Layer 1: APIレベル)

VSCode拡張が実際に呼ぶサーバー契約を、**拡張自身の実装を通して**検証する(issue #942 / AT-16)。

- 記法・タグの意味・原則は [docs/ACCEPTANCE_TESTING.md](../../../docs/ACCEPTANCE_TESTING.md) と同じ。
- 受け入れ基準との対応は [docs/ACCEPTANCE_CRITERIA.md](../../../docs/ACCEPTANCE_CRITERIA.md) §2.14。
- UI操作(コマンドパレット・Webview・キーバインド)は自動化しない。
  [../MANUAL_ACCEPTANCE_CHECKLIST.md](../MANUAL_ACCEPTANCE_CHECKLIST.md) を参照。

## なぜ playwright-bdd ではないのか

`apps/web` の受け入れテストはブラウザを操作するため Playwright が要る。拡張の Layer 1 は
**ブラウザを一切使わず**、`apps/extension/src/apiClient.ts` を Node から直接呼ぶ。
Playwright を持ち込む理由が無いので、拡張が既に使っている jest(ts-jest)の上で
`.feature` を動かす最小のランナー(`support/gherkin.ts`)を置いた。
`vscode` モジュールは単体テストと同じく `src/__mocks__/vscode.ts` へ差し替える。

## 置き場所

| パス | 内容 |
| --- | --- |
| `features/<domain>/*.feature` | 受け入れ基準。日本語 Gherkin |
| `steps/<domain>.steps.ts` | ステップ定義。検証対象の呼び出しは拡張自身の実装を通す |
| `support/gherkin.ts` | `.feature` → jest の describe/test 変換とタグ絞り込み |
| `support/env.ts` | 拡張設定(`letsBlog.*`)・SecretStorage 相当・デバイス認可ログイン |
| `support/browser.ts` | デバイス認可の**ブラウザ承認**だけを肩代わりするヘルパー |
| `support/api.ts` | 前提条件の作り込み(プロジェクト・マネージドWordPress・環境紐付け) |
| `support/stubs.ts` | 外部依存スタブの死活確認とエラー注入 |
| `acceptance.test.ts` | 入口。全 `.feature` を1ファイルへ束ね、直列に実行する |

`support/browser.ts` だけは拡張のコードを通らない。デバイス認可を承認するのは
利用者のブラウザであって拡張ではないため、そこはテスト側で再現している。

## 実行

```bash
# 前提: 開発スタックとスタブが起動している
docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml up -d
source ~/.config/lets-blog-e2e.env      # E2E_ADMIN_PASSWORD / E2E_TEST_PASSWORD

cd apps/extension
npm run test:at            # 全件
npm run test:at:fast       # @slow / @destructive を除く

AT_TAGS=@ai npm run test:at            # タグで絞る
npx jest -c e2e/jest.config.js -t "ログイン"   # シナリオ名で絞る
```

- 資格情報が未設定なら**スキップではなく失敗**する(環境の不備であってテスト対象の問題ではない)。
- `@stub` のシナリオはスタブへ到達できなければ失敗する(docs/ACCEPTANCE_TESTING.md §9 と同じ方針)。
- `@slow` はマネージドWordPressの構築と実公開を伴う。サイト `at16probe` とプロジェクト
  `at16-extension` が無ければ作り、あれば再利用する(冪等)。
- gateway のレート制限は**クライアントIPあたり 100 req/分**
  (`services/gateway/src/main/resources/application.yml` の `rate-limit.api-global`)。
  全件実行1回で数十リクエストを使うため、**続けて何度も回すと 429 になる**。
  背景ステップが解決するログインユーザー・プロジェクト・サイトは実行内で使い回しているが、
  それでも連続実行するときは1分空けること(429 は環境の状態であってシナリオの失敗ではない)。

## 単体テスト(Layer 2)との使い分け

サーバー越しに観測できることは Layer 1、リクエストの中身やローカル処理は Layer 2。
たとえば「校正チェックが本文だけを送る」ことはサーバーからは見えないので、
`src/__tests__/apiClientRequests.test.ts` が担当する。

```bash
npm run test            # 単体
npm run test:coverage   # 単体 + カバレッジ
```
