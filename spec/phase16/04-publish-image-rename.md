# 投稿時画像リネーム及び WordPress featured_media 連携仕様

## 概要

記事投稿(`POST /api/posts/publish`)時に、アップロード対象の画像ファイル名を `{slug}-{4桁連番}.{拡張子}` 形式にリネームし、`featured_image` フィールドがある場合はそれを WordPress の `featured_media` として自動設定する。

## ファイル名リネーム仕様

### 形式

```
{slug}-{4桁連番}.{拡張子}
例:
  my-article-0001.png
  my-article-0002.jpg
  best-practice-tips-0001.gif
```

### アルゴリズム

1. **Slug の決定**:
   - `PostPublishCommand.slug()` が指定されている場合: その値を使用
   - 未指定の場合: `title` から簡易スラッグ化
     - 英数字とハイフン以外の文字を除去
     - 連続する空白/特殊文字をハイフン1個に統一
     - 先頭・末尾のハイフンを除去
     - 結果が空文字列の場合: デフォルト値 `"post"` を使用

2. **連番生成**:
   - `PostPublishCommand.images()` のリスト順で 1～N を採番
   - ゼロパディングして 4 桁化: `String.format("%04d", index)`

3. **拡張子の保持**:
   - 元の `MultipartFile.getOriginalFilename()` から拡張子を抽出
   - 例: `eyecatch-123.png` → `.png`
   - 拡張子がない場合のデフォルト: `.bin`(ただし通常は画像なので `.png` を使用)

### 実装例

```java
// PostPublishService.java

private String generateSlugForFilename(String providedSlug, String title) {
    if (providedSlug != null && !providedSlug.isBlank()) {
        return providedSlug;
    }
    
    // title からスラッグ生成
    String slugified = title
        .replaceAll("[^\\w\\s-]", "")           // 英数字、ハイフン、ハイフン以外を除去
        .replaceAll("[\\s]+", "-")              // 空白をハイフンに
        .replaceAll("-+", "-")                  // 連続ハイフンを1個に
        .replaceAll("^-|-$", "");               // 先頭・末尾のハイフンを除去
    
    return slugified.isBlank() ? "post" : slugified.toLowerCase();
}

private String getFileExtension(String originalFilename) {
    if (originalFilename == null || originalFilename.isBlank()) {
        return ".bin";
    }
    int lastDot = originalFilename.lastIndexOf('.');
    return lastDot >= 0 ? originalFilename.substring(lastDot) : ".bin";
}

private String renameImageFile(String originalFilename, String slug, int index) {
    String extension = getFileExtension(originalFilename);
    String number = String.format("%04d", index);
    return slug + "-" + number + extension;
}
```

## featured_image の WordPress featured_media 連携

### フロー

1. `PostPublishCommand` に `featuredImageFilename` パラメータを追加
   - VSCode 拡張側から、アイキャッチ画像の Markdown 参照(e.g., `assets/eyecatch.png`)を渡す
   - 未指定の場合は null

2. `PostPublishService.publish()` で画像リネーム・アップロード時に、`featuredImageFilename` に一致する元ファイル名を特定

3. 該当する画像のメディア ID(`MediaUploadResult.id()`)を取得し、`PostContent` へ `featuredMediaId` として設定

4. `CmsAdapter.createOrUpdatePost()` で WordPress REST API に `featured_media` フィールドを含める

### DTOの変更

#### PostPublishCommand

```java
public record PostPublishCommand(
    String siteKey,
    String title,
    String slug,
    String status,
    List<String> categories,
    List<String> tags,
    String wpPostId,
    String markdown,
    List<MultipartFile> images,
    String featuredImageFilename  // ← NEW (nullable)
) {}
```

#### PostPublishService.replaceImageReferences()

現行:
```java
private String replaceImageReferences(CmsAdapter cmsAdapter, CmsCredentials credentials, 
                                       String markdown, List<MultipartFile> images)
```

新規(戻り値変更):
```java
private ImageReplacementResult replaceImageReferences(
    CmsAdapter cmsAdapter, 
    CmsCredentials credentials,
    String markdown,
    List<MultipartFile> images,
    String slug,
    String featuredImageFilename
) {
    String rewritten = markdown;
    Map<String, String> filenameToUrl = new LinkedHashMap<>();
    String featuredMediaId = null;
    
    if (images == null || images.isEmpty()) {
        return new ImageReplacementResult(markdown, null);
    }
    
    // 1. Slug 確定
    String finalSlug = generateSlugForFilename(slug, title);
    
    // 2. 画像のリネーム・アップロード
    for (int i = 0; i < images.size(); i++) {
        MultipartFile image = images.get(i);
        String originalFilename = image.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) continue;
        
        // リネーム
        String renamedFilename = renameImageFile(originalFilename, finalSlug, i + 1);
        
        try {
            MediaUploadResult uploaded = cmsAdapter.uploadMedia(
                credentials, 
                renamedFilename,            // ← リネーム後のファイル名を渡す
                image.getContentType(), 
                image.getBytes()
            );
            
            // Markdown 中の参照は元のファイル名で置換
            // (拡張側が元のファイル名で書いているため)
            filenameToUrl.put(originalFilename, uploaded.url());
            
            // featured_image に一致するかチェック
            if (featuredImageFilename != null && 
                featuredImageFilename.equals(originalFilename)) {
                featuredMediaId = uploaded.id();
            }
        } catch (IOException e) {
            throw new CmsApiException("画像 '" + renamedFilename + "' のアップロードに失敗しました", e);
        }
    }
    
    // 3. Markdown を置換
    for (Map.Entry<String, String> entry : filenameToUrl.entrySet()) {
        rewritten = rewritten.replace(entry.getKey(), entry.getValue());
    }
    
    return new ImageReplacementResult(rewritten, featuredMediaId);
}

// 戻り値レコード
private record ImageReplacementResult(
    String markdown,
    String featuredMediaId
) {}
```

#### PostContent

```java
public record PostContent(
    String title,
    String slug,
    String htmlContent,
    String status,
    List<String> categoryIds,
    List<String> tagIds,
    String featuredMediaId  // ← NEW (nullable)
) {}
```

### CmsAdapter 実装の変更

#### WordPressAdapter (REST パス)

```java
public PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId) {
    // ... 既存の処理
    
    if (credentials.transportType() == TransportType.REST) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("title", content.title());
        body.put("content", content.htmlContent());
        body.put("status", content.status());
        if (content.slug() != null) {
            body.put("slug", content.slug());
        }
        // ... categories, tags の処理
        
        // featured_media 設定
        if (content.featuredMediaId() != null) {
            body.put("featured_media", Long.parseLong(content.featuredMediaId()));
        }
        
        // ... REST API 呼び出し
    }
}
```

#### WordPressSshOperations

```java
public PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId) {
    List<String> args = new ArrayList<>();
    args.add("wp");
    args.add("post");
    args.add(existingPostId != null ? "update" : "create");
    
    if (existingPostId == null) {
        args.add("--post_type=post");
    } else {
        args.add(existingPostId);
    }
    
    args.add("--post_title=" + escapeShellArg(content.title()));
    args.add("--post_content=" + escapeShellArg(content.htmlContent()));
    args.add("--post_status=" + content.status());
    
    if (content.slug() != null) {
        args.add("--post_name=" + content.slug());
    }
    
    // featured_media 設定
    if (content.featuredMediaId() != null) {
        args.add("--post_thumbnail=" + content.featuredMediaId());
    }
    
    // ... 実行
}
```

#### WordPressAgentOperations

```java
// 同様に featuredMediaId 対応パラメータを追加
```

#### MicroCmsAdapter

```java
public PostResult createOrUpdatePost(CmsCredentials credentials, PostContent content, String existingPostId) {
    // featuredMediaId は無視(microCMS は統一された featured_media 概念がないため)
    // 既存の実装のまま
}
```

### PostController の変更

```java
@PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public PostPublishResponse publish(
    @RequestParam("site") String site,
    @RequestParam("title") String title,
    @RequestParam(value = "slug", required = false) String slug,
    @RequestParam(value = "status", defaultValue = "draft") String status,
    @RequestParam(value = "categories", required = false) List<String> categories,
    @RequestParam(value = "tags", required = false) List<String> tags,
    @RequestParam(value = "wpPostId", required = false) String wpPostId,
    @RequestParam("markdown") String markdown,
    @RequestParam(value = "images", required = false) List<MultipartFile> images,
    @RequestParam(value = "featuredImageFilename", required = false) String featuredImageFilename  // ← NEW
) {
    PostPublishCommand command = new PostPublishCommand(
        site, title, slug, status, categories, tags, wpPostId, markdown, images, featuredImageFilename
    );
    return postPublishService.publish(command);
}
```

## VSCode 拡張側の integration

### extension/src/apiClient.ts

```typescript
export async function publishPost(
    serverUrl: string,
    apiKey: string,
    actor: Actor,
    siteKey: string,
    slug: string | undefined,
    frontMatter: LetsBlogFrontMatter,
    markdown: string,
    images: LocalImageReference[],
    featuredImageFilename?: string  // ← NEW
): Promise<PublishResult> {
    const form = new FormData();
    form.append('site', siteKey);
    form.append('title', frontMatter.title || 'Untitled');
    form.append('slug', slug || frontMatter.slug || '');
    form.append('status', frontMatter.status || 'draft');
    
    // ... categories, tags, markdown の処理
    
    // 画像の追加
    for (const image of images) {
        form.append('images', fs.createReadStream(image.absolutePath), {
            filename: image.reference
        });
    }
    
    // featured_image の追加
    if (featuredImageFilename) {
        form.append('featuredImageFilename', featuredImageFilename);
    }
    
    // ... POST 実行
}
```

### extension/src/extension.ts の publishToSite()

```typescript
async function publishToSite(
    context: vscode.ExtensionContext,
    siteKey: string | undefined
): Promise<void> {
    // ... 既存処理
    
    const bodyImages = extractLocalImageReferences(article.content, baseDir);
    const featuredImage = resolveFeaturedImageReference(article.data, baseDir);
    
    const allImages = [...bodyImages];
    const featuredImageFilename = undefined;
    
    if (featuredImage) {
        if (!allImages.find(img => img.reference === featuredImage.reference)) {
            allImages.push(featuredImage);
        }
        featuredImageFilename = featuredImage.reference;
    }
    
    const result = await api.publishPost(
        getServerUrl(),
        apiKey,
        actor,
        siteKey,
        article.data.slug,
        article.data,
        article.content,
        allImages,
        featuredImageFilename  // ← パラメータ追加
    );
}
```

## 検証ポイント

### リネーム処理

- リネーム後のファイル名が `{slug}-XXXX.{ext}` 形式であること
- 複数画像投稿で連番が正しく採番される(0001, 0002, ...)
- slug 未設定時に title から正しくスラッグ化される
- title が空や特殊文字のみの場合に "post" がデフォルトになる
- 元のファイル名の拡張子が保持される

### WordPress featured_media 連携

- アイキャッチ画像が特定され、featured_media ID が正しく設定される
- WordPress 投稿の featured_media が設定される(投稿詳細で確認)
- アイキャッチなしの投稿で featured_media が null/未設定になる
- SSH/Agent トランスポートでも featured_media が設定される

### Markdown URL 置換

- 元のファイル名での Markdown 参照が、アップロード後の URL に正しく置換される
- featured_image とのファイル名一致判定が正しく動作する

### エッジケース

- 画像なし投稿で featured_image が指定されている場合のハンドリング(featured_media を設定しないか、エラーか)
- アイキャッチ画像ファイルが images リストに含まれていない場合のハンドリング
