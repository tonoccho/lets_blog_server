import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { getActor, getServerUrl, requireApiKey } from './config';
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
   * 直近の生成結果。base64データはここに1つだけ保持し、Webviewへは
   * プレビュー表示用に一度送るだけにする(保存時に送り返させない)。
   */
  private _lastGenerated: api.AiImageResult | undefined;
  /** 直近の生成に使ったprompt(アセット挿入時のalt文言に使う)。 */
  private _lastPrompt: string | undefined;


  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    baseDir: string,
    projectId: number
  ): void {
    showSingletonPanel('letsBlog.imageGen', () => new ImageGenPanel(context, editor, baseDir, projectId));
  }

  private constructor(
    context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _baseDir: string,
    private readonly _projectId: number
  ) {
    super(context, { viewType: 'letsBlog.imageGen', title: 'Generate Image', assetName: 'imageGen' });
  }

  protected async handleMessage(message: ImageGenInboundMessage): Promise<void> {
    switch (message.command) {
      case 'loadOptions':
        return this._handleLoadOptions();
      case 'generate':
        return this._handleGenerate(message);
      case 'setAsEyecatch':
        return this._handleSetAsEyecatch();
      case 'addAsAsset':
        return this._handleAddAsAsset();
    }
  }

  private async _handleLoadOptions(): Promise<void> {
    const apiKey = await requireApiKey(this.context);
    const options = await api.getImageGenerationOptions(getServerUrl(), apiKey, this._projectId);
    this.postMessage('options', options);
  }

  private async _handleGenerate(
    message: Extract<ImageGenInboundMessage, { command: 'generate' }>
  ): Promise<void> {
    const apiKey = await requireApiKey(this.context);
    const actor = await getActor(this.context);
    const result = await api.generateImage(getServerUrl(), apiKey, actor, this._projectId, message.params);
    this._lastGenerated = result;
    this._lastPrompt = message.params.prompt;
    this.postMessage('generated', result);
  }

  private async _handleSetAsEyecatch(): Promise<void> {
    const generated = this._requireGenerated();
    const fileName = this._saveToAssets(generated.dataBase64, generated.fileName, 'eyecatch');

    const article = parseArticle(this._editor.document.getText());
    article.data.featured_image = `assets/${fileName}`;
    await this._replaceEditorText(stringifyArticle(article));

    this.postMessage('eyecatchSet', { fileName });
    vscode.window.showInformationMessage(`アイキャッチを 'assets/${fileName}' に設定しました。`);
  }

  private async _handleAddAsAsset(): Promise<void> {
    const generated = this._requireGenerated();
    const fileName = this._saveToAssets(generated.dataBase64, generated.fileName, 'generated-asset');

    const markdownImage = `![${this._lastPrompt ?? ''}](assets/${fileName})`;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(this._editor.document.uri, this._editor.selection.active, markdownImage);
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();

    this.postMessage('assetAdded', { fileName });
    vscode.window.showInformationMessage(`アセットを 'assets/${fileName}' に追加しました。`);
  }

  /** 保存対象の生成結果を取り出す。生成前に保存操作が来た場合は明示的に失敗させる。 */
  private _requireGenerated(): api.AiImageResult {
    if (!this._lastGenerated) {
      throw new Error('保存できる生成画像がありません。先に画像を生成してください。');
    }
    return this._lastGenerated;
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
