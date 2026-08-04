# phase16: ComfyUI 高度な画像生成 + ギャラリー + 投稿時自動リネーム

## 概要

phase16 では VSCode 拡張から automatic1111/stable-diffusion-webui 相当のパラメータで ComfyUI 画像を生成でき、生成画像を「アイキャッチ」または「アセット」として記事に組み込めるようにする。生成画像とパラメータをサーバーに永続化し、Web管理画面でギャラリーとして表示する。投稿時には画像ファイル名を `slug-4桁連番` 形式にリネームし、アイキャッチは WordPress の featured_media としても設定する。

## 要件

### 要件 1. VSCode 拡張からの高度な画像生成

- VSCode 拡張「Let's Blog: Generate Image」コマンドはプロンプト入力のみではなく、UI フォームで以下のパラメータを設定できる:
  - **prompt**: プロンプト文字列(必須)
  - **negative prompt**: ネガティブプロンプト
  - **steps**: サンプリングステップ数(既定: 20)
  - **cfg scale**: 分類器自由度スケール(既定: 7.0)
  - **sampler**: サンプラー名(既定: euler。リスト表示)
  - **scheduler**: スケジューラー名(既定: normal。リスト表示)
  - **seed**: シード値(空欄でランダム生成)
  - **width/height**: 生成画像サイズ(既定: 512x512。8の倍数)
  - **batch size**: バッチサイズ(既定: 1)
  - **checkpoint**: Stable Diffusion チェックポイント(既定: プロジェクト選択値またはグローバルデフォルト。リスト表示)
  - **LoRA モデル**: 適用する LoRA(任意。リスト表示)
  - **LoRA 適用強度**: LoRA weight(既定: 1.0)

### 要件 2. automatic1111 相当のパラメータ仕様

- サポート対象のパラメータ範囲:
  - `steps`: 1～150
  - `cfg scale`: 0.0～30.0(浮動小数点)
  - `width/height`: 64～2048、かつ 8 の倍数
  - `batch size`: 1～4
  - `seed`: 0～2^32-1(空欄でランダム)
  - `sampler/scheduler`: ComfyUI API から取得可能なもののみ
  - `checkpoint/LoRA`: ComfyUI コンテナ内のファイル一覧から取得
- **対象外**: Hires fix、ControlNet、複数 LoRA、カスタムノード等の高度な拡張機能

### 要件 3. 生成画像の「アイキャッチとして設定」または「アセットとして追加」選択

- VSCode 拡張での生成フロー:
  1. UI フォームでパラメータ入力 → 「生成」ボタン
  2. サーバーで画像生成、Base64 エンコード結果と画像パラメータを返却
  3. 拡張側でプレビュー表示
  4. ユーザーが「アイキャッチとして設定」または「アセットとして追加」を選択
  
- **アイキャッチとして設定**: 
  - 生成画像を `{記事ディレクトリ}/assets/{生成ファイル名}` へ保存
  - `front matter` の `featured_image` フィールドに `assets/{ファイル名}` を設定(本文には挿入しない)
  - 既存値があれば置き換え
  
- **アセットとして追加**:
  - 生成画像を `{記事ディレクトリ}/assets/{生成ファイル名}` へ保存
  - カーソル位置に Markdown 画像記法 `![{prompt}](assets/{ファイル名})` を挿入(本文の既存箇所へのインライン挿入)

### 要件 4. 生成画像のアイキャッチと WordPress featured_media の連携

- VSCode 拡張で「アイキャッチとして設定」した画像は、記事投稿時に WordPress の `featured_media`(アイキャッチ)としても自動設定される
- `front matter` の `featured_image` がある場合、その画像ファイルを投稿の featured_media に設定(投稿時に同梱される画像リストで特定して、メディア ID を解決)

### 要件 5. サーバーサイドの生成画像ギャラリー

- Web 管理画面に新規ページ `/image-gallery` を追加
- 生成した画像とそのパラメータをサーバーに永続化し、ギャラリーで表示:
  - サムネイルグリッド表示(最新順)
  - 画像クリックで詳細パラメータ表示(prompt、negative prompt、各生成パラメータ、作成日時)
  - プロジェクト絞り込み(任意)
- 画像バイナリ取得は `/api/generated-images/{id}/file` エンドポイント経由

### 要件 6. 投稿時の画像ファイル名リネーム

- 記事投稿(`POST /api/posts/publish`)の際、アップロード対象の画像ファイル名を以下の形式にリネーム:
  - `{slug}-{4桁連番}.{拡張子}`
  - 例: `my-article-0001.png`、`my-article-0002.jpg`
  - 記事に複数の画像がある場合、front matter の `featured_image` に該当する画像が先頭のいずれかと一致すれば、その image ID をアイキャッチ(featured_media)として設定
  - `slug` が未設定の場合は `title` から簡易スラッグ化(英数字以外を除去/ハイフン化。結果が空なら `"post"`)してリネーム

## スコープ外(将来拡張ポイント)

- Hires fix、ControlNet、img2img、inpaint、複数 LoRA の同時適用
- GPU メモリ制限やキューイング管理
- LoRA / Checkpoint のアップロード/ダウンロード管理(現行の ComfyUiCheckpointStorageService レベルでの実装)
- microCMS への featured_media 連携(microCMS の仕様上、アイキャッチ相当の概念が統一されていないため)
- 画像生成の非同期化(現行のポーリング方式を保持)

## DB スキーマ変更

### V27__add_generated_images.sql

新規テーブル `generated_images`:
- `id` (BIGINT PRIMARY KEY AUTO_INCREMENT)
- `project_id` (BIGINT, NULL, FK → `projects(id)` ON DELETE SET NULL)
- `prompt` (TEXT NOT NULL): 生成プロンプト
- `negative_prompt` (TEXT): ネガティブプロンプト
- `steps` (INT): サンプリングステップ数
- `cfg_scale` (DECIMAL(5,2)): CFG スケール値
- `sampler_name` (VARCHAR(100)): サンプラー名
- `scheduler` (VARCHAR(100)): スケジューラー名
- `seed` (BIGINT): シード値
- `width` (INT): 生成画像幅
- `height` (INT): 生成画像高さ
- `batch_size` (INT): バッチサイズ
- `checkpoint` (VARCHAR(255)): チェックポイント名
- `lora_name` (VARCHAR(255) NULL): LoRA モデル名
- `lora_weight` (DECIMAL(5,2) NULL): LoRA 適用強度
- `file_path` (VARCHAR(500) NOT NULL): ディスク上の保存パス(相対パス、`{projectIdOrGlobal}/{sha256}.png` 形式)
- `mime_type` (VARCHAR(100) NOT NULL, デフォルト: `image/png`)
- `created_at` (DATETIME NOT NULL, DEFAULT CURRENT_TIMESTAMP)

インデックス:
- `idx_generated_images_project_id`
- `idx_generated_images_created_at`

## API エンドポイント変更一覧

### 新規

- `POST /api/ai/image`: 詳細パラメータ対応(リクエスト DTO 拡張)
  - リクエスト: `AiImageRequest`(prompt、negativePrompt、steps、cfgScale 等、projectId)
  - レスポンス: `AiImageResponse`(fileName、dataBase64、mimeType、**id** ← ギャラリー ID)
  
- `GET /api/ai/image-options?projectId={id}`: 生成パラメータのオプション一覧
  - レスポンス: `{checkpoints: [...], selectedCheckpoint: "...", samplers: [...], schedulers: [...], loras: [...]}`
  
- `GET /api/generated-images`: 生成画像一覧
  - クエリ: `projectId`(任意)
  - レスポンス: List<`GeneratedImageSummaryResponse`>
  
- `GET /api/generated-images/{id}`: 生成画像詳細
  - レスポンス: `GeneratedImageDetailResponse`
  
- `GET /api/generated-images/{id}/file`: 画像バイナリ
  - レスポンス: Content-Type: `image/png` で画像バイナリ

### 変更

- `POST /api/posts/publish`: multipart form に `featuredImageFilename` パラメータ追加
  - `PostPublishCommand` に `featuredImageFilename`(nullable String) を追加
  - リネーム処理を追加(slug-4 桁連番)

## フロントエンド/拡張側の変更

### VSCode 拡張

- 新規: `extension/src/imageGenPanel.ts`: 画像生成パラメータ入力 WebviewPanel
- 新規: `extension/src/apiClient.ts` の関数拡張:
  - `generateImage(serverUrl, apiKey, actor, projectId, params)`: 詳細パラメータ版
  - `getImageGenerationOptions(serverUrl, apiKey, projectId)`: オプション取得
- 変更: `extension/src/extension.ts` の `commandGenerateImage`: ImageGenPanel 経由に切り替え
- 変更: `extension/src/frontMatter.ts`: `resolveFeaturedImageReference()` 追加
- 変更: `extension/src/extension.ts` の `publishToSite()`: featured_image を images リストに追加
- 変更: `extension/src/apiClient.ts` の `publishPost()`: featuredImageFilename パラメータ追加

### Web 管理画面

- 新規ページ: `web/src/app/image-gallery/page.tsx`
- 新規プロキシ route: `web/src/app/image-gallery/[id]/file/route.ts`
- 変更: `web/src/lib/apiClient.ts` にギャラリー関連関数追加

## 実装の依存順序

1. **DB・ドメイン・DTO**: V27 マイグレーション、GeneratedImage ドメイン、関連 DTO
2. **ComfyUI クライアント拡張**: ComfyUiClient、ComfyUiGenerationParams
3. **バックエンド画像生成・永続化**: AiAssistService、GeneratedImageStorageService、リポジトリ
4. **バックエンド API**: GeneratedImageController、AiController 拡張
5. **VSCode 拡張の画像生成 UI**: ImageGenPanel、apiClient 拡張
6. **VSCode 拡張のアイキャッチ/アセット**: frontMatter、extension.ts の handling
7. **投稿パイプラインの統合**: PostPublishService リネーム処理、featured_media 設定
8. **Web 管理画面ギャラリー**: image-gallery ページ・route

## テスト方針

- API: 単体テスト(ComfyUiClient のパラメータ組み立て、PostPublishService のリネーム・featured_media 解決)
- 統合: docker-compose 環境でエンドツーエンド確認
  - ComfyUI サーバーで画像生成 → パラメータが DB に保存される
  - VSCode 拡張で「アイキャッチとして設定」→ front matter が更新される
  - 投稿→ WordPress でファイル名が `slug-XXXX.ext` 形式に、アイキャッチが設定される
  - Web ギャラリーで画像とパラメータが表示される
