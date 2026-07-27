# 07. ComfyUI 連携

## 目的

ComfyUIの画像生成ワークフローを利用し、アイキャッチ画像や本文挿入画像を自動生成、WordPressメディアライブラリへ自動アップロードする。

## 前提・決定事項

- 実行基盤: Docker Compose上の `comfyui` サービス([01-docker-compose](01-docker-compose.md))。配布イメージは未確定(コミュニティイメージ or 自前ビルド)。
- 用途: アイキャッチ・本文挿入画像の自動生成
- 生成後の流れ: ComfyUI生成 → APIサーバーが受け取り → WordPress Media APIへアップロード → 記事内のURL差し替え

## タスクチェックリスト

- [ ] ComfyUIの配布形態確定([01-docker-compose](01-docker-compose.md)の未決事項と連動)
- [ ] 使用するチェックポイントモデルの選定・配置
- [ ] 生成ワークフローJSON(プロンプト→画像)の作成
- [ ] APIサーバー側 `/api/ai/image` エンドポイント実装(ComfyUI APIへのプロキシ、ジョブポーリング)
- [ ] 生成画像をWordPress Media APIへアップロードする処理の実装
- [ ] VSCode拡張側での画像生成指示の記法確定([04-vscode-extension](04-vscode-extension.md)と連動)

## 未決事項

- Markdown/Front matter内でのプロンプト指定記法(例: `featured_image_prompt: "..."` のようなfront matterフィールドか、本文中の特殊記法か)
- 生成の同期/非同期方式(ComfyUIの生成は数秒〜数十秒かかるため、VSCode拡張側でのポーリングUIが必要)
- 生成画像のバリエーション選択UI(1枚自動採用か、複数候補から選ばせるか)
