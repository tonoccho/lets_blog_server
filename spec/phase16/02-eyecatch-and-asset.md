# アイキャッチ設定とアセット追加の仕様

## 概要

VSCode 拡張の「Let's Blog: Generate Image」コマンドを、プロンプト単独の簡易版から、複数パラメータを設定可能な WebviewPanel へ刷新する。生成後、ユーザーが「アイキャッチとして設定」または「アセットとして追加」を選択でき、それぞれ異なる動作をする。

## UIフロー

### 1. コマンド実行から WebviewPanel 起動まで

```
ユーザー: Let's Blog: Generate Image コマンド実行
         ↓
extension.ts: commandGenerateImage()
         ↓
- アクティブなエディタ(記事 Markdown ファイル)の baseDir を確認
- front matter から project_id を読み込む(なければ context から getProjectId())
- ImageGenPanel.createOrShow(context, editor, baseDir, projectId) を呼び出し
         ↓
ImageGenPanel が起動 → WebviewPanel の UI を表示
```

### 2. ImageGenPanel WebviewPanel の構成

新規ファイル: `extension/src/imageGenPanel.ts`

```typescript
export class ImageGenPanel {
  private static currentPanel: ImageGenPanel | undefined;
  
  private constructor(
    private readonly panel: vscode.WebviewPanel,
    private readonly context: vscode.ExtensionContext,
    private readonly editor: vscode.TextEditor,
    private readonly baseDir: string,
    private readonly projectId: number
  ) {}
  
  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    baseDir: string,
    projectId: number
  ): void {
    // WebviewPanel 作成・表示(planPanel.ts のパターンに準拠)
  }
  
  private onDidReceiveMessage(message: unknown): void {
    // メッセージディスパッチ
    // - 'loadOptions': パラメータ選択肢取得 → getImageGenerationOptions() 呼び出し
    // - 'generate': 画像生成 → generateImage() 呼び出し
    // - 'setAsEyecatch': アイキャッチ設定
    // - 'addAsAsset': アセット追加
  }
}
```

#### パネルの HTML コンテンツ構成

- **パラメータ入力セクション**:
  - prompt (textarea)
  - negative prompt (textarea)
  - steps (number input, min: 1, max: 150)
  - cfg scale (number input, min: 0, max: 30, step: 0.1)
  - sampler (select ドロップダウン、オプション一覧は起動時に API から取得)
  - scheduler (select ドロップダウン)
  - seed (text input, empty でランダム)
  - width/height (number input, min: 64, max: 2048, step: 8)
  - batch size (number input, min: 1, max: 4)
  - checkpoint (select ドロップダウン、選択値または globalDefault が既定)
  - LoRA (select ドロップダウン, 「なし」がデフォルト)
  - LoRA weight (number input, min: 0, max: 2, step: 0.1。LoRA 選択時のみ表示)
  
- **生成ボタン**: フォーム下部に「生成」ボタン
  
- **プレビューセクション**:
  - 生成画像の表示(Base64 データ URI)
  - 画像情報表示(生成時刻、パラメータサマリー)
  - 「アイキャッチとして設定」ボタン
  - 「アセットとして追加」ボタン
  - (オプション)生成結果履歴

### 3. アイキャッチとして設定

```typescript
private async onSetAsEyecatch(message: SetAsEyecatchMessage): Promise<void> {
  // message.imageData: Base64 エンコードされた画像バイト
  // message.fileName: ComfyUI 側で生成されたファイル名(e.g., "letsblog_00001.png")
  
  // 1. assets フォルダの確認/作成
  const assetsDir = path.join(this.baseDir, 'assets');
  if (!fs.existsSync(assetsDir)) {
    fs.mkdirSync(assetsDir, { recursive: true });
  }
  
  // 2. 画像ファイルをディスクへ保存
  const decodedImage = Buffer.from(message.imageData, 'base64');
  const extension = path.extname(message.fileName) || '.png';
  const generatedFileName = `eyecatch-${Date.now()}${extension}`;
  const filePath = path.join(assetsDir, generatedFileName);
  fs.writeFileSync(filePath, decodedImage);
  
  // 3. front matter の featured_image を更新
  const article = parseArticle(this.editor.document.getText());
  article.data.featured_image = `assets/${generatedFileName}`;  // 記事からの相対パス
  
  // 4. エディタを更新・保存
  const newContent = stringifyArticle(article);
  const edit = new vscode.WorkspaceEdit();
  const fullRange = new vscode.Range(
    this.editor.document.lineAt(0).range.start,
    this.editor.document.lineAt(this.editor.document.lineCount - 1).range.end
  );
  edit.replace(this.editor.document.uri, fullRange, newContent);
  await vscode.workspace.applyEdit(edit);
  await this.editor.document.save();
  
  // 5. 完了メッセージ表示
  vscode.window.showInformationMessage(`アイキャッチを 'assets/${generatedFileName}' に設定しました。`);
}
```

#### 動作仕様

- **出力先**: `{記事ディレクトリ}/assets/{generatedFileName}`
  - 例: `articles/my-post/assets/eyecatch-1723129200000.png`
  
- **front matter 更新**: `featured_image: assets/{generatedFileName}`(記事からの相対パス)
  - 既存値がある場合は置き換え
  
- **本文への挿入**: なし(frontmatter のみ更新)

- **ファイル名生成**: `eyecatch-{タイムスタンプ}.{拡張子}`
  - 複数回の設定で重複を避ける(毎回 timestamp で異なる)
  - 元ファイル名の `letsblog_00001.png` 等は無視

### 4. アセットとして追加

```typescript
private async onAddAsAsset(message: AddAsAssetMessage): Promise<void> {
  // message.imageData: Base64 画像
  // message.fileName: ComfyUI 生成ファイル名
  // message.prompt: 生成時のプロンプト(代替テキストとして使用)
  
  // 1. assets フォルダの確認/作成(上記と同じ)
  const assetsDir = path.join(this.baseDir, 'assets');
  if (!fs.existsSync(assetsDir)) {
    fs.mkdirSync(assetsDir, { recursive: true });
  }
  
  // 2. 画像をディスクへ保存
  const decodedImage = Buffer.from(message.imageData, 'base64');
  const extension = path.extname(message.fileName) || '.png';
  const assetFileName = `generated-asset-${Date.now()}${extension}`;
  const filePath = path.join(assetsDir, assetFileName);
  fs.writeFileSync(filePath, decodedImage);
  
  // 3. Markdown 画像記法をカーソル位置へ挿入
  // 前景パネルを非表示にして、エディタに戻ることで、ユーザーが
  // カーソル位置を確認しながら挿入できるようにする
  this.panel.dispose();
  
  // ImageGenPanel が dispose されたら extension.ts 側から
  // insertImageAtCursor() を呼ぶ(パネル側ではなく拡張側に委譲)
}
```

#### 代替実装(extension.ts 側で処理する場合)

`ImageGenPanel` からのコールバックではなく、直接 `extension.ts` 側の関数を呼ぶ:

```typescript
// extension.ts
async function commandGenerateImage(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;
  
  // ... 既存: ImageGenPanel.createOrShow()
  
  // ImageGenPanel から emit されるイベント(例: onSetAsEyecatch など)を
  // 拡張側で処理する
}

function insertImageAtCursor(
  editor: vscode.TextEditor,
  baseDir: string,
  imageData: string,
  fileName: string,
  prompt: string
): void {
  // 1. assets へ保存
  const assetsDir = path.join(baseDir, 'assets');
  fs.mkdirSync(assetsDir, { recursive: true });
  
  const decodedImage = Buffer.from(imageData, 'base64');
  const extension = path.extname(fileName) || '.png';
  const assetFileName = `generated-asset-${Date.now()}${extension}`;
  fs.writeFileSync(path.join(assetsDir, assetFileName), decodedImage);
  
  // 2. Markdown 画像記法を挿入(既存の replaceDocumentText() パターン)
  const markdownImage = `![${prompt}](assets/${assetFileName})`;
  editor.edit(editBuilder => {
    editBuilder.insert(editor.selection.active, markdownImage);
  });
  
  vscode.window.showInformationMessage(`アセットを 'assets/${assetFileName}' に追加しました。`);
}
```

#### 動作仕様

- **出力先**: `{記事ディレクトリ}/assets/{assetFileName}`
  - 例: `articles/my-post/assets/generated-asset-1723129200000.png`
  
- **本文への挿入**: カーソル位置に `![{prompt}](assets/{assetFileName})` を挿入
  - 既存の本文には影響を与えない(追記)
  
- **front matter への更新**: なし
  
- **ファイル名生成**: `generated-asset-{タイムスタンプ}.{拡張子}`

## APIClient 側の実装変更

### extension/src/apiClient.ts

```typescript
// 既存のシンプル版は廃止
// export async function generateImage(serverUrl, apiKey, prompt)

// 新規拡張版
export interface ImageGenerationParams {
  prompt: string;
  negativePrompt?: string;
  steps?: number;
  cfgScale?: number;
  samplerName?: string;
  scheduler?: string;
  seed?: number | null;
  width?: number;
  height?: number;
  batchSize?: number;
  checkpoint?: string;
  loraName?: string;
  loraWeight?: number;
}

export async function generateImage(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number | undefined,
  params: ImageGenerationParams
): Promise<{ id: number; fileName: string; dataBase64: string; mimeType: string }> {
  const res = await fetch(`${serverUrl}/api/ai/image`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify({
      projectId,
      ...params
    }),
    agent: buildAgent(serverUrl),
  });
  await assertOk(res);
  return (await res.json()) as any;
}

export async function getImageGenerationOptions(
  serverUrl: string,
  apiKey: string,
  projectId?: number
): Promise<{
  checkpoints: string[];
  selectedCheckpoint: string;
  samplers: string[];
  schedulers: string[];
  loras: string[];
}> {
  const params = projectId ? `?projectId=${projectId}` : '';
  const res = await fetch(`${serverUrl}/api/ai/image-options${params}`, {
    headers: buildHeaders(apiKey),
    agent: buildAgent(serverUrl),
  });
  await assertOk(res);
  return (await res.json()) as any;
}
```

## frontMatter.ts 拡張

```typescript
export interface LocalImageReference {
  reference: string;  // Markdown 中の参照文字列(e.g., "assets/eyecatch.png")
  absolutePath: string;
}

// 既存
export function extractLocalImageReferences(content: string, baseDir: string): LocalImageReference[]

// 新規: front matter の featured_image を LocalImageReference へ変換
export function resolveFeaturedImageReference(
  data: LetsBlogFrontMatter,
  baseDir: string
): LocalImageReference | undefined {
  if (!data.featured_image) return undefined;
  
  const reference = data.featured_image;
  // featured_image は相対パス想定(e.g., "assets/eyecatch.png")
  if (reference.startsWith('http://') || reference.startsWith('https://') || reference.startsWith('data:')) {
    return undefined;  // 外部 URL や data URI は対象外
  }
  
  return {
    reference,
    absolutePath: path.resolve(baseDir, reference),
  };
}
```

## 投稿時の統合 (publishToSite)

既存の `extension/src/extension.ts` の `publishToSite()` 関数を以下のように拡張:

```typescript
async function publishToSite(
  context: vscode.ExtensionContext,
  siteKey: string | undefined
): Promise<void> {
  // ... 既存処理
  
  // 本文の画像に加え、featured_image も images リストへ
  const bodyImages = extractLocalImageReferences(article.content, baseDir);
  const featuredImage = resolveFeaturedImageReference(article.data, baseDir);
  
  const allImages = [...bodyImages];
  if (featuredImage && !allImages.find(img => img.reference === featuredImage.reference)) {
    allImages.push(featuredImage);
  }
  
  // publishPost 呼び出しに featuredImageFilename を追加
  const featuredImageFilename = featuredImage?.reference;
  
  const result = await api.publishPost(
    getServerUrl(),
    apiKey,
    actor,
    siteKey,
    article.data.slug,
    article.data,
    article.content,
    allImages,
    featuredImageFilename  // 新規パラメータ
  );
}
```

## 検証ポイント

- VSCode 拡張側:
  - ImageGenPanel が正しく起動する
  - パラメータ選択肢が API から取得される
  - 「アイキャッチとして設定」後に front matter が更新される
  - 「アセットとして追加」後に本文にMarkdown 画像記法が挿入される
  - assets フォルダがない場合に自動作成される

- 投稿時:
  - featured_image の値が images リストに含まれる
  - ファイル名がリネームされる際、アイキャッチのファイルも正しくリネームされる
