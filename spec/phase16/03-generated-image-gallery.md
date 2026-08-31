# 生成画像ギャラリーの仕様

## 概要

サーバーサイドでComfyUI生成画像とそのパラメータを永続化し、Web管理画面で検索・表示・詳細確認できるギャラリー機能を追加する。

## DB スキーマ (V27__add_generated_images.sql)

### テーブル: generated_images

```sql
CREATE TABLE generated_images (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NULL,
    prompt TEXT NOT NULL COMMENT 'プロンプト',
    negative_prompt TEXT COMMENT 'ネガティブプロンプト',
    steps INT COMMENT 'サンプリングステップ数',
    cfg_scale DECIMAL(5, 2) COMMENT 'CFG スケール',
    sampler_name VARCHAR(100) COMMENT 'サンプラー名',
    scheduler VARCHAR(100) COMMENT 'スケジューラー名',
    seed BIGINT COMMENT 'シード値',
    width INT COMMENT '画像幅',
    height INT COMMENT '画像高さ',
    batch_size INT COMMENT 'バッチサイズ',
    checkpoint VARCHAR(255) COMMENT 'チェックポイント名',
    lora_name VARCHAR(255) NULL COMMENT 'LoRA モデル名',
    lora_weight DECIMAL(5, 2) NULL COMMENT 'LoRA 適用強度',
    file_path VARCHAR(500) NOT NULL COMMENT 'ディスク保存パス',
    mime_type VARCHAR(100) NOT NULL DEFAULT 'image/png',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_generated_images_project FOREIGN KEY (project_id) 
        REFERENCES projects(id) ON DELETE SET NULL,
    INDEX idx_generated_images_project_id (project_id),
    INDEX idx_generated_images_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## バックエンド API

### 1. GET /api/ai/image-options

**説明**: 画像生成パラメータの選択肢を取得(VSCode拡張のUI用)

**クエリパラメータ**:
- `projectId` (Long, 任意): プロジェクトID指定時、選択中のcheckpointはそのプロジェクトの値、未指定時はグローバルデフォルト

**レスポンス**:
```json
{
  "checkpoints": ["sd15.safetensors", "sdxl.safetensors"],
  "selectedCheckpoint": "sd15.safetensors",
  "samplers": ["euler", "euler_ancestral", "heun", "dpm_2", "..."],
  "schedulers": ["normal", "karras", "exponential"],
  "loras": ["lora_model_1.safetensors", "lora_model_2.safetensors"]
}
```

**実装**:
- `AiController` に新規 `getImageOptions()` メソッド追加
- `ComfyUiClient` の `listCheckpoints()`, `listSamplers()`, `listSchedulers()`, `listLoras()` を呼び出し
- `projectId` 指定時は `ComfyUiModelService.getSelectedCheckpoint(projectId)` で selectedCheckpoint を取得

### 2. POST /api/ai/image

**説明**: 拡張パラメータ対応の画像生成(既存エンドポイントの拡張)

**リクエスト** (`AiImageRequest`):
```java
public record AiImageRequest(
    @NotBlank String prompt,
    String negativePrompt,
    @Min(1) @Max(150) Integer steps,
    @DecimalMin("0.0") @DecimalMax("30.0") Double cfgScale,
    String samplerName,
    String scheduler,
    Long seed,
    @Min(64) @Max(2048) Integer width,
    @Min(64) @Max(2048) Integer height,
    @Min(1) @Max(4) Integer batchSize,
    String checkpoint,
    String loraName,
    @DecimalMin("0.0") @DecimalMax("2.0") Double loraWeight,
    Long projectId
) {
    // デフォルト値設定ファクトリ
    public static AiImageRequest withDefaults(String prompt) { ... }
}
```

注: width/height の検証は`@Pattern`を使って8の倍数をチェック(またはカスタムバリデーション)

**レスポンス** (`AiImageResponse`):
```java
public record AiImageResponse(
    Long id,                // ← NEW: ギャラリーID
    String fileName,
    String dataBase64,
    String mimeType
) {}
```

**実装フロー**:
1. `AiController.image()` が `AiImageRequest` を受け取る
2. `AiAssistService.generateImage()` へパラメータ全体を渡す
3. `ComfyUiClient.generateImage(ComfyUiGenerationParams)` で画像生成
4. `GeneratedImageStorageService.store()` で画像をディスクへ保存
5. `GeneratedImageRepository.save()` でDB記録
6. エンティティID を `AiImageResponse.id` として返却

### 3. GET /api/generated-images

**説明**: 生成画像一覧取得

**クエリパラメータ**:
- `projectId` (Long, 任意): フィルター対象プロジェクト
- `page` (Integer, 任意, デフォルト: 0)
- `size` (Integer, 任意, デフォルト: 20)

**レスポンス**:
```json
[
  {
    "id": 1,
    "projectId": 5,
    "prompt": "a beautiful landscape",
    "checkpoint": "sd15.safetensors",
    "createdAt": "2024-08-04T12:34:56"
  },
  ...
]
```

**DTO** (`GeneratedImageSummaryResponse`):
```java
public record GeneratedImageSummaryResponse(
    Long id,
    Long projectId,        // nullable
    String prompt,
    String checkpoint,
    LocalDateTime createdAt
) {}
```

### 4. GET /api/generated-images/{id}

**説明**: 生成画像詳細取得(パラメータ全体)

**レスポンス** (`GeneratedImageDetailResponse`):
```java
public record GeneratedImageDetailResponse(
    Long id,
    Long projectId,
    String prompt,
    String negativePrompt,
    Integer steps,
    Double cfgScale,
    String samplerName,
    String scheduler,
    Long seed,
    Integer width,
    Integer height,
    Integer batchSize,
    String checkpoint,
    String loraName,
    Double loraWeight,
    LocalDateTime createdAt
) {}
```

### 5. GET /api/generated-images/{id}/file

**説明**: 画像バイナリ取得(Web BFF のプロキシ route から叩く)

**レスポンス**:
- Content-Type: `image/png`(固定)
- Body: 画像バイナリ

**実装**:
```java
@GetMapping("/api/generated-images/{id}/file")
public ResponseEntity<byte[]> getImageFile(@PathVariable Long id) {
    GeneratedImage image = generatedImageRepository.findById(id)
        .orElseThrow(() -> new GeneratedImageNotFoundException("id: " + id));
    
    byte[] data = generatedImageStorageService.load(image.getFilePath());
    
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + id + ".png")
        .body(data);
}
```

## Web 管理画面: Image Gallery ページ

### ファイル構成

- `web/src/app/image-gallery/page.tsx`: メインページ(Server Component)
- `web/src/app/image-gallery/[id]/file/route.ts`: プロキシ route

### page.tsx 実装

```typescript
// app/image-gallery/page.tsx
export default async function ImageGalleryPage() {
  // 1. API から画像一覧を取得(デフォルト: 最新順、20件)
  const [images, timezone] = await Promise.all([
    listGeneratedImages().catch(() => []),
    getViewerTimeZone()
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">生成画像ギャラリー</h1>
      
      {/* 検索・フィルター(任意) */}
      <div className="flex gap-4">
        <input 
          type="text" 
          placeholder="プロンプトで検索…" 
          className="flex-1 px-3 py-2 border rounded"
        />
        {/* プロジェクトフィルター等を追加可能 */}
      </div>

      {/* サムネイルグリッド */}
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
        {images.length === 0 ? (
          <p className="text-neutral-500 col-span-full">生成画像がありません</p>
        ) : (
          images.map(image => (
            <ImageCard key={image.id} image={image} timezone={timezone} />
          ))
        )}
      </div>
    </div>
  );
}

// サムネイルカードコンポーネント
function ImageCard({ image, timezone }: { image: GeneratedImageSummary; timezone: string }) {
  return (
    <details className="border rounded overflow-hidden cursor-pointer">
      <summary className="block">
        <img 
          src={`/image-gallery/${image.id}/file`}
          alt={image.prompt}
          className="w-full aspect-square object-cover hover:opacity-80"
        />
      </summary>
      
      {/* 詳細表示(details の <summary> 後) */}
      <div className="p-3 bg-neutral-50 text-sm space-y-2">
        <div>
          <span className="font-semibold">プロンプト:</span>
          <p className="text-neutral-600 line-clamp-2">{image.prompt}</p>
        </div>
        <div>
          <span className="font-semibold">モデル:</span>
          <p className="text-neutral-600">{image.checkpoint}</p>
        </div>
        <div>
          <span className="font-semibold">作成日時:</span>
          <p className="text-neutral-600">{formatDateTime(image.createdAt, timezone)}</p>
        </div>
        <button 
          onClick={() => showDetailModal(image.id)}
          className="text-blue-600 hover:underline"
        >
          詳細を見る
        </button>
      </div>
    </details>
  );
}
```

### プロキシ route: [id]/file/route.ts

```typescript
// app/image-gallery/[id]/file/route.ts
export async function GET(
  request: Request,
  { params }: { params: { id: string } }
) {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "ログインが必要です" }, { status: 401 });
  }

  try {
    const { body, mimeType } = await downloadGeneratedImageFile(Number(params.id));
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": mimeType || "image/png",
        "Content-Disposition": "inline",
        "Cache-Control": "public, max-age=3600",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
```

**理由**: `/api/` 配下だと nginx のリバースプロキシルーティングが `/api/` をバックエンド API に直結させるため(VSCode拡張ダウンロード同様)、Web BFF 側の中継 route として `/image-gallery/[id]/file` を経由

### Web apiClient 関数群

```typescript
// web/src/lib/apiClient.ts

export interface GeneratedImageSummary {
  id: number;
  projectId: number | null;
  prompt: string;
  checkpoint: string;
  createdAt: string;
}

export interface GeneratedImageDetail extends GeneratedImageSummary {
  negativePrompt: string;
  steps: number;
  cfgScale: number;
  samplerName: string;
  scheduler: string;
  seed: number;
  width: number;
  height: number;
  batchSize: number;
  loraName: string | null;
  loraWeight: number | null;
}

export async function listGeneratedImages(
  projectId?: number
): Promise<GeneratedImageSummary[]> {
  const params = projectId ? `?projectId=${projectId}` : "";
  return apiFetch<GeneratedImageSummary[]>(`/api/generated-images${params}`);
}

export async function getGeneratedImage(id: number): Promise<GeneratedImageDetail> {
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/${id}`);
}

export async function downloadGeneratedImageFile(id: number): Promise<{ body: ArrayBuffer; mimeType: string }> {
  const res = await fetch(`/image-gallery/${id}/file`, {
    cache: "no-store",
  });
  if (!res.ok) {
    throw new Error(`画像の取得に失敗しました: ${res.status}`);
  }
  return {
    body: await res.arrayBuffer(),
    mimeType: res.headers.get("Content-Type") || "image/png",
  };
}
```

## インフラ設定

### application.yml

```yaml
app:
  generated-images-storage-path: ${GENERATED_IMAGES_STORAGE_PATH:/app/data/generated-images}
```

### docker-compose.yml

```yaml
services:
  api:
    volumes:
      # 既存のボリューム
      - comfyui_models:/app/data/comfyui-models
      - bulk_uploads:/app/data/bulk-uploads
      # 新規
      - generated_images:/app/data/generated-images

volumes:
  generated_images:
```

## 検証ポイント

### バックエンド

- `AiController.getImageOptions()` が正しくオプション一覧を返す
- `/api/ai/image` エンドポイントで生成画像の ID が返される
- `GeneratedImageRepository.findAllByOrderByCreatedAtDesc()` で最新順取得が動作
- `GeneratedImageStorageService.store()` と `load()` でファイル I/O が正常
- `GET /api/generated-images/{id}/file` で画像バイナリが返される

### Web 管理画面

- `/image-gallery` ページが読み込まれ、生成画像のサムネイルが表示される
- サムネイルクリックで詳細パラメータが表示される
- `/image-gallery/[id]/file` プロキシで画像が正しくロードされる(ブラウザの Image タブで確認)
- プロジェクトフィルター(実装時)で絞り込みが動作する
