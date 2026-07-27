# 06. Ollama 連携

## 目的

ローカルLLM(Ollama)を用いて、記事の下書き・校正・要約支援およびタグ/カテゴリの自動提案を行う。

## 前提・決定事項

- 実行基盤: Docker Compose上の `ollama` サービス([01-docker-compose](01-docker-compose.md))
- 呼び出し元: VSCode拡張のコマンド([04-vscode-extension](04-vscode-extension.md)) → APIサーバー([03-api-server](03-api-server.md)) → Ollama
- 用途: (1) 下書き/校正/要約支援、(2) タグ/カテゴリ自動提案

## 実装状況(更新: 06-ollama-integration 完了時点)

- 利用モデル: **`qwen2.5:7b-instruct`**(Q4_K_M量子化、7.6Bパラメータ、context length 32768)。日本語の指示追従・文章品質が良好で、7B級でVRAM 16GBのRTX 5070 Tiに十分収まるサイズのため採用。`.env` の `OLLAMA_MODEL` で変更可能。
- モデルpull手順: `docker exec lbs-ollama ollama pull qwen2.5:7b-instruct`(初回のみ、約4.7GB)。
- `api/src/main/java/com/letsblog/api/ai/OllamaClient.java`: Ollamaの `/api/generate` を呼ぶ薄いHTTPクライアント(`stream: false`で一括レスポンス取得)。
- `AiAssistService` + `AiController`:
  - `POST /api/ai/draft` — `{ mode: "draft"|"proofread"|"summarize", text }` → `{ result }`。モードごとに専用プロンプトテンプレートを用意。
  - `POST /api/ai/tags` — `{ text }` → `{ categories: string[], tags: string[] }`。LLMには厳密なJSON形式のみを出力するよう指示し、`{}`部分を抽出してパース(失敗時は空配列を返すフォールバック付き)。
  - 呼び出しごとに `generation_jobs` テーブルへ `running`→`done`/`failed` のステータスで記録(Web管理画面の「AIジョブ」一覧に反映される)。
- **実機検証**: 実際にモデルをpullし、draft/proofread/summarize/tagsの4パターンをAPI経由・[04-vscode-extension](04-vscode-extension.md)のコンパイル済みクライアントコード経由の両方で実行し、期待通りの日本語出力(校正の誤字修正、要約、カテゴリ/タグのJSON抽出)を確認。`generation_jobs`への記録も確認済み。

## タスクチェックリスト

- [x] 利用モデルの選定 → `qwen2.5:7b-instruct`
- [x] `docker exec` での初回モデルpull手順の確立
- [x] APIサーバー側 `/api/ai/draft`(下書き/校正/要約)実装
- [x] APIサーバー側 `/api/ai/tags`(タグ/カテゴリ提案)実装
- [x] プロンプトテンプレート設計
- [x] VSCode拡張側のAI支援コマンドUI実装(結果のプレビュー・反映フロー)— [04-vscode-extension](04-vscode-extension.md)で実装済み、本ステップで実際のAPIに対して動作確認

## 未決事項

- 生成結果を「提案」として差分表示するか、直接本文/front matterを書き換えるか — 現状はVSCode拡張側で「下書き/校正/要約は別タブでプレビュー」「タグ提案はQuickPickで選択して反映」という形に決定済み(要再確認事項ではなくなった)
- 長文記事に対するコンテキスト長の制約への対処(現モデルはcontext length 32768と大きいため当面問題になりにくいが、非常に長い記事では要約してから投げる等の対処を検討)
- タグ提案のJSON出力が不正な形式になった場合のリトライ(現状は空配列を返すのみ)
