# 04. VSCode拡張

## 目的

VSCode上でMarkdown記事を執筆し、仲介APIサーバー経由でWordPressへ投稿・更新できる拡張機能を実装する。

## 前提・決定事項

- 役割: 執筆 + 投稿トリガー(管理画面機能はWebフロントエンドに委譲)
- 同期方向: 一方向(Markdown → WordPress)
- 画像: 本文中ローカル画像を自動アップロード
- 複数サイト切り替えに対応
- APIキーは VSCode `SecretStorage` に保存

## Front matter スキーマ(ドラフト)

```yaml
---
title: "記事タイトル"
slug: "article-slug"
site: "site-key"          # 複数サイト切り替え用の識別子
status: "draft"           # draft | publish
categories: ["カテゴリ1"]
tags: ["タグ1", "タグ2"]
featured_image: "./images/eyecatch.png"   # ローカルパス or ComfyUI生成指示(要記法確定)
wp_post_id: null           # 初回投稿後にAPIから払い出され、この拡張が書き戻す
wp_post_url: null
---
```

## 投稿パイプライン(概略)

1. アクティブなMarkdownファイルを読み込み、front matterと本文を分離
2. 本文中のPlantUMLブロック・ComfyUI画像生成指示を抽出
3. APIサーバーへ投稿リクエスト送信(本文・front matterメタデータ・ローカル画像を同梱)
4. APIサーバー側でHTML変換・画像アップロード・カテゴリ/タグ解決・WordPress投稿を実施
5. レスポンスの `wp_post_id` / `wp_post_url` を front matter に書き戻す(2回目以降は更新呼び出しになる)

詳細な処理順序は [03-api-server](03-api-server.md) 側のAPI設計と合わせて確定する。

## コマンド(案)

| コマンド | 動作 |
|---|---|
| `Let's Blog: Publish` | 現在のMarkdownを投稿/更新 |
| `Let's Blog: Select Site` | front matterの`site`を選択UIで設定 |
| `Let's Blog: Set API Key` | SecretStorageへAPIキー登録 |
| `Let's Blog: Ask AI (Draft/Proofread/Summarize)` | Ollama呼び出し |
| `Let's Blog: Suggest Tags` | Ollamaによるタグ/カテゴリ提案 |
| `Let's Blog: Generate Image` | ComfyUIによる画像生成→ローカル挿入 |

## 実装状況(更新: 04-vscode-extension 完了時点)

`extension/` 配下にTypeScript製の拡張を実装済み(`tsc`でコンパイル、`out/`に出力)。

- `src/config.ts`: サーバーURL(設定 `letsBlog.serverUrl`)とAPIキー(`SecretStorage`)の取得
- `src/frontMatter.ts`: gray-matterによるfront matter解析/書き戻し、本文中のローカル画像参照抽出(http(s)/dataスキームは除外)
- `src/apiClient.ts`: `/api/posts/publish`(multipart, 画像同梱)、`/api/sites`、`/api/ai/draft`、`/api/ai/tags`、`/api/ai/image` を呼ぶクライアント(node-fetch + form-data)
- `src/extension.ts`: 6コマンドを実装

**実機検証**: 一時的なDocker WordPress(`wp-test2`)を用意し、コンパイル済み `apiClient`/`frontMatter` をNodeスクリプトから直接呼び出して、サイト一覧取得→ローカル画像抽出→投稿(画像同梱・カテゴリ/タグ自動作成)までエンドツーエンドで成功を確認(拡張本体のVSCode Extension Host上での起動確認は、GUI環境がないため未実施)。

## タスクチェックリスト

- [x] 拡張プロジェクト雛形作成(TypeScript + tsc、手動セットアップ)
- [x] Front matter パーサ導入(gray-matter)
- [x] `Publish` コマンド実装(APIサーバー呼び出し + front matter書き戻し)
- [x] サイト切り替えUI(QuickPick)実装
- [x] APIキー設定コマンド実装(SecretStorage)
- [x] AI支援コマンド実装(`Ask AI` / `Suggest Tags` / `Generate Image`)— ただし対応するサーバーAPI(`/api/ai/*`)は [06-ollama-integration](06-ollama-integration.md) / [07-comfyui-integration](07-comfyui-integration.md) 実装後に動作確認が必要
- [x] エラー表示(投稿失敗時の通知UI)実装(`showErrorMessage`)

## 未決事項

- `featured_image` にComfyUI生成を指示する記法(front matterにプロンプトを書くか、別コマンドで事前生成してパス差し替えにするか)— 現状は本文中の画像参照のみ対応、`featured_image`フィールド自体はまだ投稿処理に反映されない
- 投稿失敗時のリトライ/ロールバック方針
- 複数ファイル一括投稿の要否(Phase 1では単一ファイル操作のみを想定)
- VSCode Extension Host上での実機起動確認(GUI環境がある場所で`F5`実行 or `vsce package`でのインストール確認が必要)
