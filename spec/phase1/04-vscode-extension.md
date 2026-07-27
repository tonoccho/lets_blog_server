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

## タスクチェックリスト

- [ ] 拡張プロジェクト雛形作成(`yo code` 等)
- [ ] Front matter パーサ導入(gray-matter相当)
- [ ] `Publish` コマンド実装(APIサーバー呼び出し + front matter書き戻し)
- [ ] サイト切り替えUI(QuickPick)実装
- [ ] APIキー設定コマンド実装(SecretStorage)
- [ ] AI支援コマンド実装(Ollama/ComfyUI呼び出し)
- [ ] エラー表示(投稿失敗時の通知UI)実装

## 未決事項

- `featured_image` にComfyUI生成を指示する記法(front matterにプロンプトを書くか、別コマンドで事前生成してパス差し替えにするか)
- 投稿失敗時のリトライ/ロールバック方針
- 複数ファイル一括投稿の要否(Phase 1では単一ファイル操作のみを想定)
