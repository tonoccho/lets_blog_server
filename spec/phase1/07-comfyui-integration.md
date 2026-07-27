# 07. ComfyUI 連携

## 目的

ComfyUIの画像生成ワークフローを利用し、アイキャッチ画像や本文挿入画像を自動生成、WordPressメディアライブラリへ自動アップロードする。

## 前提・決定事項

- 実行基盤: Docker Compose上の `comfyui` サービス([01-docker-compose](01-docker-compose.md))。配布イメージは未確定(コミュニティイメージ or 自前ビルド)。
- 用途: アイキャッチ・本文挿入画像の自動生成
- 生成後の流れ: VSCode拡張が `/api/ai/image` でComfyUI生成画像(Base64)を取得 → ローカルファイルとして保存 → Markdown本文に画像参照を挿入 → 通常の `/api/posts/publish` フロー([03-api-server](03-api-server.md))でWordPress Media APIへアップロードされ、URLが差し替わる。ComfyUI→WordPress間の直接連携ではなく、既存の画像アップロードパイプラインに合流させる設計とした。

## 実装状況(更新: 07-comfyui-integration 完了時点)

- チェックポイント: **`v1-5-pruned-emaonly.safetensors`**(Stable Diffusion v1.5, 約4.27GB)を `docker exec lbs-comfyui aria2c ...` で `models/checkpoints/` に配置。`.env` の `COMFYUI_CHECKPOINT` で変更可能。
- `api/src/main/java/com/letsblog/api/ai/ComfyUiClient.java`: ComfyUIのAPI形式ワークフローJSON(CheckpointLoaderSimple→CLIPTextEncode(正/負)→EmptyLatentImage→KSampler→VAEDecode→SaveImage、512x512, 20 steps, euler/normal)を構築し `POST /prompt` で投入、`GET /history/{prompt_id}` を1秒間隔・最大120回ポーリングして完了を待ち、`GET /view` で画像バイト列を取得する。
- `AiAssistService.generateImage()` / `POST /api/ai/image` — `{ prompt }` → `{ fileName, dataBase64, mimeType }`。[04-vscode-extension](04-vscode-extension.md)の `generateImage` コマンドの契約と完全一致。呼び出しごとに `generation_jobs`(type=`comfyui_image`)へ記録。
- **実機検証**: 実際にプロンプトから画像を生成し(所要時間 約3秒、RTX 5070 Ti)、有効なPNG(512x512)であることを確認。さらにVSCode拡張のコンパイル済みクライアントコードから「画像生成→ローカル保存→Markdown参照→投稿」の一連の流れを一時WordPressに対して実行し、生成画像がWordPressメディアライブラリへアップロードされ記事に正しく埋め込まれることを確認済み。

## タスクチェックリスト

- [x] ComfyUIの配布形態確定 → `yanwk/comfyui-boot:cu130-slim`([01-docker-compose](01-docker-compose.md))
- [x] 使用するチェックポイントモデルの選定・配置 → `v1-5-pruned-emaonly.safetensors`
- [x] 生成ワークフローJSON(プロンプト→画像)の作成
- [x] APIサーバー側 `/api/ai/image` エンドポイント実装(ComfyUI APIへのプロキシ、ジョブポーリング)
- [x] 生成画像をWordPressへアップロードする処理 → 既存の `/api/posts/publish` 画像アップロード経路に統合する形で実現
- [x] VSCode拡張側での画像生成指示の記法確定 → プロンプトはコマンド実行時にInputBoxで都度入力(front matterには保持しない、[04-vscode-extension](04-vscode-extension.md)参照)

## 未決事項

- 生成画像のバリエーション選択UI(現状は1枚自動採用。複数候補から選ばせる場合は `/api/ai/image` を複数回呼ぶか、バッチ生成に対応する拡張が必要)
- アイキャッチ画像(WordPressの `featured_media`)への自動設定は未実装(現状は本文中画像としての挿入のみ)
- チェックポイント/LoRA等の追加・切り替えをWeb管理画面から行えるようにするか(現状は`.env`の環境変数のみ)
