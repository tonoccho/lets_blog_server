# 06. Ollama 連携

## 目的

ローカルLLM(Ollama)を用いて、記事の下書き・校正・要約支援およびタグ/カテゴリの自動提案を行う。

## 前提・決定事項

- 実行基盤: Docker Compose上の `ollama` サービス([01-docker-compose](01-docker-compose.md))
- 呼び出し元: VSCode拡張のコマンド([04-vscode-extension](04-vscode-extension.md)) → APIサーバー([03-api-server](03-api-server.md)) → Ollama
- 用途: (1) 下書き/校正/要約支援、(2) タグ/カテゴリ自動提案

## タスクチェックリスト

- [ ] 利用モデルの選定(日本語性能・モデルサイズ・ライセンスを比較)
- [ ] `docker exec` 等での初回モデルpull手順の確立(`ollama pull <model>`)
- [ ] APIサーバー側 `/api/ai/draft`(下書き/校正/要約)実装
- [ ] APIサーバー側 `/api/ai/tags`(タグ/カテゴリ提案)実装
- [ ] プロンプトテンプレート設計(記事本文 → 校正結果/要約/タグ候補)
- [ ] VSCode拡張側のAI支援コマンドUI実装(結果のプレビュー・反映フロー)

## 未決事項

- モデル選定(候補: Llama系、Qwen系、日本語特化モデルなど)
- 生成結果を「提案」として差分表示するか、直接本文/front matterを書き換えるか
- 長文記事に対するコンテキスト長の制約への対処(要約してから投げる等)
