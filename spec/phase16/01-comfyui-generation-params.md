# ComfyUI 生成パラメータと LoRA 実装仕様

## 概要

`ComfyUiClient.java` を拡張し、automatic1111 相当のパラメータで Stable Diffusion 画像生成ワークフローを組み立てられるようにする。既存の固定値ハードコード方式を廃止し、パラメータレコード `ComfyUiGenerationParams` を入力として受け取る設計に変更。

## ComfyUiGenerationParams レコード

```java
public record ComfyUiGenerationParams(
    String prompt,                      // プロンプト(必須)
    String negativePrompt,              // ネガティブプロンプト(既定: "low quality, blurry, watermark, text")
    Integer steps,                      // サンプリングステップ(既定: 20, 範囲: 1-150)
    Double cfgScale,                    // CFG スケール(既定: 7.0, 範囲: 0.0-30.0)
    String samplerName,                 // サンプラー名(既定: "euler")
    String scheduler,                   // スケジューラー名(既定: "normal")
    Long seed,                          // シード値(既定: null → 自動生成)
    Integer width,                      // 幅(既定: 512, 範囲: 64-2048, 8の倍数)
    Integer height,                     // 高さ(既定: 512, 範囲: 64-2048, 8の倍数)
    Integer batchSize,                  // バッチサイズ(既定: 1, 範囲: 1-4)
    String checkpoint,                  // チェックポイント名(既定: 設定値)
    String loraName,                    // LoRA モデル名(任意)
    Double loraWeight                   // LoRA 適用強度(既定: 1.0, 範囲: 0.0-2.0)
) {
    public static ComfyUiGenerationParams withDefaults(String prompt) {
        return new ComfyUiGenerationParams(
            prompt, 
            "low quality, blurry, watermark, text", 
            20, 7.0, "euler", "normal", null, 512, 512, 1, 
            null, // checkpoint は呼び出し側で指定
            null, null
        );
    }
}
```

## ComfyUiClient メソッド変更

### 既存メソッドの置き換え

```java
// 削除
public ComfyUiImage generateImage(String prompt)
public ComfyUiImage generateImage(String prompt, String checkpoint)

// 新規統一メソッド
public ComfyUiImage generateImage(ComfyUiGenerationParams params)
```

実装:
- `params.seed()` が null または -1 の場合は `System.nanoTime() & 0xFFFFFFFFL` で自動生成
- `params.checkpoint()` が null の場合はコンストラクタで受け取った `checkpointName` を使用
- ワークフロー JSON を `buildWorkflow(params)` で構築し、ComfyUI `/prompt` API に投入

### 新規リスト取得メソッド

```java
// 既存
public List<String> listCheckpoints()

// 新規
public List<String> listSamplers()
public List<String> listSchedulers()
public List<String> listLoras()
```

#### listSamplers()

```java
public List<String> listSamplers() {
    try {
        JsonNode response = client.get().uri("/object_info/KSampler").retrieve().body(JsonNode.class);
        List<String> samplers = new ArrayList<>();
        if (response == null) return samplers;
        
        JsonNode samplerNames = response.path("KSampler")
                .path("input").path("required").path("sampler_name").path(0);
        for (JsonNode name : samplerNames) {
            samplers.add(name.asText());
        }
        return samplers;
    } catch (RestClientResponseException e) {
        throw new AiServiceException("ComfyUI サンプラー一覧の取得に失敗しました", e);
    }
}
```

#### listSchedulers()

```java
public List<String> listSchedulers() {
    try {
        JsonNode response = client.get().uri("/object_info/KSampler").retrieve().body(JsonNode.class);
        List<String> schedulers = new ArrayList<>();
        if (response == null) return schedulers;
        
        JsonNode schedulerNames = response.path("KSampler")
                .path("input").path("required").path("scheduler").path(0);
        for (JsonNode name : schedulerNames) {
            schedulers.add(name.asText());
        }
        return schedulers;
    } catch (RestClientResponseException e) {
        throw new AiServiceException("ComfyUI スケジューラー一覧の取得に失敗しました", e);
    }
}
```

#### listLoras()

```java
public List<String> listLoras() {
    try {
        JsonNode response = client.get().uri("/object_info/LoraLoader").retrieve().body(JsonNode.class);
        List<String> loras = new ArrayList<>();
        if (response == null) return loras; // LoRA ノード未実装
        
        JsonNode loraNames = response.path("LoraLoader")
                .path("input").path("required").path("lora_name").path(0);
        for (JsonNode name : loraNames) {
            loras.add(name.asText());
        }
        return loras;
    } catch (RestClientResponseException e) {
        // LoRA ノード未実装の場合は空リストを返す(例外を投げない)
        return new ArrayList<>();
    }
}
```

## ワークフロー生成ロジック: buildWorkflow(ComfyUiGenerationParams)

### ベース構造(LoRA なし)

```
CheckpointLoaderSimple (node 4)
    ↓ (model, clip)
CLIPTextEncode 正プロンプト (node 6)
CLIPTextEncode 負プロンプト (node 7)
    ↓ (conditioning)
KSampler (node 3) ← EmptyLatentImage (node 5)
    ↓ (latent samples)
VAEDecode (node 8) ← VAE from Checkpoint
    ↓
SaveImage (node 9)
```

### LoRA 有効時の構造

```
CheckpointLoaderSimple (node 4)
    ↓ (model, clip)
LoraLoader (node 10) ← LORA_NAME (params.loraName())
    ↓ (model_out, clip_out → strength_model/strength_clip = params.loraWeight())
CLIPTextEncode 正プロンプト (node 6)
CLIPTextEncode 負プロンプト (node 7)
    ↓
KSampler (node 3)
    ↓
VAEDecode (node 8)
    ↓
SaveImage (node 9)
```

### buildWorkflow() 実装の骨組み

```java
private ObjectNode buildWorkflow(ComfyUiGenerationParams params) {
    var factory = JsonNodeFactory.instance;
    ObjectNode graph = factory.objectNode();
    
    // Node 4: CheckpointLoaderSimple
    ObjectNode checkpointLoader = factory.objectNode();
    checkpointLoader.put("class_type", "CheckpointLoaderSimple");
    checkpointLoader.putObject("inputs").put("ckpt_name", params.checkpoint());
    graph.set("4", checkpointLoader);
    
    // Node 5: EmptyLatentImage
    ObjectNode latentImage = factory.objectNode();
    latentImage.put("class_type", "EmptyLatentImage");
    ObjectNode latentInputs = latentImage.putObject("inputs");
    latentInputs.put("width", params.width());
    latentInputs.put("height", params.height());
    latentInputs.put("batch_size", params.batchSize());
    graph.set("5", latentImage);
    
    // Node 10: LoraLoader (params.loraName() が指定されている場合のみ)
    String clipRef = "4";  // デフォルトは CheckpointLoaderSimple から直結
    String modelRef = "4";
    if (params.loraName() != null && !params.loraName().isBlank()) {
        ObjectNode loraLoader = factory.objectNode();
        loraLoader.put("class_type", "LoraLoader");
        ObjectNode loraInputs = loraLoader.putObject("inputs");
        loraInputs.put("lora_name", params.loraName());
        loraInputs.put("strength_model", params.loraWeight() != null ? params.loraWeight() : 1.0);
        loraInputs.put("strength_clip", params.loraWeight() != null ? params.loraWeight() : 1.0);
        loraInputs.putArray("model").add("4").add(0);        // CheckpointLoaderSimple の model_out
        loraInputs.putArray("clip").add("4").add(1);         // CheckpointLoaderSimple の clip_out
        graph.set("10", loraLoader);
        
        modelRef = "10";  // LoRA 出力へ参照を変更
        clipRef = "10";
    }
    
    // Node 6: CLIPTextEncode 正プロンプト
    ObjectNode positive = factory.objectNode();
    positive.put("class_type", "CLIPTextEncode");
    ObjectNode positiveInputs = positive.putObject("inputs");
    positiveInputs.put("text", params.prompt());
    positiveInputs.putArray("clip").add(clipRef).add(1);  // LoRA or Checkpoint の clip_out
    graph.set("6", positive);
    
    // Node 7: CLIPTextEncode 負プロンプト
    ObjectNode negative = factory.objectNode();
    negative.put("class_type", "CLIPTextEncode");
    ObjectNode negativeInputs = negative.putObject("inputs");
    negativeInputs.put("text", params.negativePrompt());
    negativeInputs.putArray("clip").add(clipRef).add(1);
    graph.set("7", negative);
    
    // Node 3: KSampler
    ObjectNode sampler = factory.objectNode();
    sampler.put("class_type", "KSampler");
    ObjectNode samplerInputs = sampler.putObject("inputs");
    samplerInputs.put("seed", params.seed() != null && params.seed() >= 0 
        ? params.seed() : (System.nanoTime() & 0xFFFFFFFFL));
    samplerInputs.put("steps", params.steps());
    samplerInputs.put("cfg", params.cfgScale());
    samplerInputs.put("sampler_name", params.samplerName());
    samplerInputs.put("scheduler", params.scheduler());
    samplerInputs.put("denoise", 1.0);
    samplerInputs.putArray("model").add(modelRef).add(0);     // LoRA or Checkpoint の model_out
    samplerInputs.putArray("positive").add("6").add(0);
    samplerInputs.putArray("negative").add("7").add(0);
    samplerInputs.putArray("latent_image").add("5").add(0);
    graph.set("3", sampler);
    
    // Node 8: VAEDecode
    ObjectNode vaeDecode = factory.objectNode();
    vaeDecode.put("class_type", "VAEDecode");
    ObjectNode vaeInputs = vaeDecode.putObject("inputs");
    vaeInputs.putArray("samples").add("3").add(0);
    vaeInputs.putArray("vae").add("4").add(2);  // CheckpointLoaderSimple の vae_out
    graph.set("8", vaeDecode);
    
    // Node 9: SaveImage
    ObjectNode saveImage = factory.objectNode();
    saveImage.put("class_type", "SaveImage");
    ObjectNode saveInputs = saveImage.putObject("inputs");
    saveInputs.put("filename_prefix", "letsblog");
    saveInputs.putArray("images").add("8").add(0);
    graph.set("9", saveImage);
    
    return graph;
}
```

## エラーハンドリング

- `params.seed()` が負数の場合は自動生成(「シード -1 でランダム」という a1111 の慣習に対応)
- `params.checkpoint()` が null の場合、コンストラクタで受け取った `checkpointName` をデフォルトに
- `params.loraName()` が blank の場合は LoRA ノードを省略(デフォルト分岐)
- ComfyUI が LoRA をサポートしていない場合(`listLoras()` が例外)は空リストを返し、生成時に LoRA を指定されたら例外を投げる

## 検証ポイント

- 単体テスト: `ComfyUiClientTest` で以下を確認
  - デフォルトパラメータでのワークフロー生成
  - 各パラメータ変更がワークフロー JSON に反映される
  - LoRA あり/なしで正しくノード構成が分岐する
  - seed null/負数で自動生成が行われる
  - `listSamplers()` / `listSchedulers()` / `listLoras()` が ComfyUI API から正しく抽出される

- 統合テスト: docker-compose 環境で
  - パラメータが異なる複数回の画像生成が成功し、DB に記録される
  - LoRA モデルが指定された画像の生成結果を ComfyUI ログで確認(LoRA ノードが実行された証跡)
