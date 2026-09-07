import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { getActor, getConfiguredAiProvider, requireAccessToken } from './config';
import { parseArticle, stringifyArticle } from './frontMatter';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { ImageGenInboundMessage, ImageGenOutboundCommand } from './webviewMessages';

/** サーバー応答由来の拡張子は、既知の画像拡張子だけを採用する。 */
const ALLOWED_IMAGE_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp'];

/**
 * 「Let's Blog: Generate Image」用のWebviewパネル。automatic1111相当のパラメータで
 * ComfyUI画像を生成し、生成結果を記事の「アイキャッチ」または「アセット」として組み込む。
 */
export class ImageGenPanel extends WebviewPanelBase<ImageGenInboundMessage, ImageGenOutboundCommand> {
  /**
   * 直近の生成結果(batch size × batch countで指定した枚数すべて。issue #1104 / #1105)。
   * base64データはここだけに保持し、Webviewへは表示に必要な1枚ずつしか渡さない
   * (保存時はindexだけを送らせ、実体を送り返させない)。
   */
  private _lastGenerated: api.AiImageResult[] = [];
  /** 直近の生成に使ったprompt(アセット挿入時のalt文言に使う)。 */
  private _lastPrompt: string | undefined;
  /**
   * Image Galleryの「この設定で画像生成」から開かれた際の反映待ちパラメータ(issue #294)。
   * Webview側の初回loadOptionsに便乗して送る(生成直後でWebviewのスクリプトが
   * まだメッセージ購読を終えていない可能性があるため、postMessageを直接叩かない)。
   */
  private _pendingPrefill: api.GeneratedImageDetail | undefined;

  /**
   * Generate Imageパネルを開く。既に開いていれば前面に出す。
   * @param baseDir 生成画像の保存先(この直下のassets/へ書き出す)
   * @param prefill 指定時、その設定をフォームへ反映する(issue #294: Image Galleryからの再生成)。
   */
  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    baseDir: string,
    projectId: number,
    prefill?: api.GeneratedImageDetail
  ): void {
    const panel = showSingletonPanel(
      'letsBlog.imageGen',
      () => new ImageGenPanel(context, editor, baseDir, projectId, prefill)
    );
    if (prefill) {
      panel.applyPrefill(prefill);
    }
  }

  private constructor(
    context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _baseDir: string,
    private readonly _projectId: number,
    prefill?: api.GeneratedImageDetail
  ) {
    super(context, { viewType: 'letsBlog.imageGen', title: 'Generate Image', assetName: 'imageGen' });
    this._pendingPrefill = prefill;
  }

  /**
   * 既に開いているパネルへ設定を反映する。パネルは読み込み済み(loadOptionsは受信済み)のため、
   * 直接postMessageしてよい。新規作成時は_handleLoadOptions側でpendingPrefillとして送る。
   */
  private applyPrefill(prefill: api.GeneratedImageDetail): void {
    this._pendingPrefill = undefined;
    this.postMessage('prefill', prefill);
  }

  /** Webviewからのコマンドを対応する処理へ振り分ける。 */
  protected async handleMessage(message: ImageGenInboundMessage): Promise<void> {
    switch (message.command) {
      case 'loadOptions':
        return this._handleLoadOptions();
      case 'cancel':
        this.cancelCurrentOperation();
        return;
      case 'generate':
        return this._handleGenerate(message);
      case 'requestImage':
        return this._handleRequestImage(message.index);
      case 'setAsEyecatch':
        return this._handleSetAsEyecatch(message.index);
      case 'addAsAsset':
        return this._handleAddAsAsset(message.index);
      case 'sendChat':
        return this._handleSendChat(message);
    }
  }

  private async _handleLoadOptions(): Promise<void> {
    const apiKey = await requireAccessToken(this.context);
    const options = await api.getImageGenerationOptions(apiKey, this._projectId);
    // letsBlog.aiProviderの現在値をWebview初期表示へ反映する(issue #530)。サーバー側の
    // ImageGenerationOptionsResponseには含まれない値のため、ここで拡張機能側の設定を合成して渡す。
    this.postMessage('options', { ...options, defaultAiProvider: getConfiguredAiProvider() });
    if (this._pendingPrefill) {
      this.postMessage('prefill', this._pendingPrefill);
      this._pendingPrefill = undefined;
    }
  }

  private async _handleGenerate(
    message: Extract<ImageGenInboundMessage, { command: 'generate' }>
  ): Promise<void> {
    const apiKey = await requireAccessToken(this.context);
    const actor = await getActor(this.context);
    const results = await this.runCancellable((signal) =>
      api.generateImage(apiKey, actor, this._projectId, message.params, signal)
    );
    this._lastGenerated = results;
    this._lastPrompt = message.params.prompt;
    // Webviewへ渡すのはファイル名の一覧だけにする(issue #1105)。batch size 16 ×
    // batch count 16 で最大256枚になり、1920×1080のPNGはbase64で1枚1MBを超えるため、
    // 全枚数を1つのメッセージで送ると数百MBがWebview境界を一度に越える。
    // 画像データはWebviewが表示に必要になった時点で1枚ずつ取りに来る(requestImage)。
    this.postMessage(
      'generated',
      results.map((generated) => ({ fileName: generated.fileName }))
    );
  }

  /**
   * Webviewが表示しようとしている1枚だけを渡す(issue #1105)。
   * 1メッセージあたりの大きさは常に画像1枚ぶんに収まる。
   */
  private async _handleRequestImage(index: number): Promise<void> {
    const generated = this._requireGenerated(index);
    this.postMessage('imageData', {
      index,
      fileName: generated.fileName,
      mimeType: generated.mimeType,
      dataBase64: generated.dataBase64,
    });
  }

  /** チャットメッセージ(と履歴)からOllamaで画像生成プロンプトを作成する。 */
  private async _handleSendChat(
    message: Extract<ImageGenInboundMessage, { command: 'sendChat' }>
  ): Promise<void> {
    const apiKey = await requireAccessToken(this.context);
    const actor = await getActor(this.context);
    const result = await this.runCancellable((signal) =>
      api.generateImagePrompt(
        apiKey,
        actor,
        this._projectId,
        message.history,
        message.message,
        signal,
        message.provider
      )
    );
    this.postMessage('promptGenerated', result);
  }

  private async _handleSetAsEyecatch(index: number): Promise<void> {
    const generated = this._requireGenerated(index);
    const fileName = this._saveToAssets(generated.dataBase64, generated.fileName, 'eyecatch');

    const article = parseArticle(this._editor.document.getText());
    article.data.featured_image = `assets/${fileName}`;
    await this._replaceEditorText(stringifyArticle(article));

    this.postMessage('eyecatchSet', { fileName });
    vscode.window.showInformationMessage(`アイキャッチを 'assets/${fileName}' に設定しました。`);
  }

  private async _handleAddAsAsset(index: number): Promise<void> {
    const generated = this._requireGenerated(index);
    const fileName = this._saveToAssets(generated.dataBase64, generated.fileName, 'generated-asset');

    const markdownImage = `![${this._lastPrompt ?? ''}](assets/${fileName})`;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(this._editor.document.uri, this._editor.selection.active, markdownImage);
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();

    this.postMessage('assetAdded', { fileName });
    vscode.window.showInformationMessage(`アセットを 'assets/${fileName}' に追加しました。`);
  }

  /**
   * 保存対象の生成結果を、Webviewで選択されている位置から取り出す(issue #1104)。
   * 生成前に保存操作が来た場合と、選択位置が生成枚数の範囲外の場合は明示的に失敗させる
   * (黙って先頭を保存すると、利用者が選んだのと違う画像が記事に入る)。
   */
  private _requireGenerated(index: number): api.AiImageResult {
    if (this._lastGenerated.length === 0) {
      throw new Error('保存できる生成画像がありません。先に画像を生成してください。');
    }
    const selected = this._lastGenerated[index];
    if (!selected) {
      throw new Error(
        `選択された画像が見つかりません(${this._lastGenerated.length}枚中${index + 1}枚目)。` +
          'プレビューから画像を選び直してください。'
      );
    }
    return selected;
  }

  /** Base64画像データを{baseDir}/assets配下へ保存し、生成したファイル名を返す。 */
  private _saveToAssets(imageDataBase64: string, sourceFileName: string, prefix: string): string {
    const assetsDir = path.join(this._baseDir, 'assets');
    fs.mkdirSync(assetsDir, { recursive: true });

    const decoded = Buffer.from(imageDataBase64, 'base64');
    // 拡張子はサーバー応答由来のため、既知の画像拡張子だけを採用する
    // (ファイル名自体は接頭辞とタイムスタンプから組み立てるため、パス要素は混入しない)。
    const candidate = path.extname(sourceFileName).toLowerCase();
    const extension = ALLOWED_IMAGE_EXTENSIONS.includes(candidate) ? candidate : '.png';
    const fileName = `${prefix}-${Date.now()}${extension}`;
    fs.writeFileSync(path.join(assetsDir, fileName), decoded);
    return fileName;
  }

  private async _replaceEditorText(newText: string): Promise<void> {
    const document = this._editor.document;
    const fullRange = new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length));
    const edit = new vscode.WorkspaceEdit();
    edit.replace(document.uri, fullRange, newText);
    await vscode.workspace.applyEdit(edit);
    await document.save();
  }
}
