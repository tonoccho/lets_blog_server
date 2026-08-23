import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { getActor, getServerUrl, requireAccessToken } from './config';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { ImageGalleryInboundMessage, ImageGalleryOutboundCommand } from './webviewMessages';

/** サムネイルを一度に読み込む件数。全件を同時にWebviewへ送ると重くなるため分割する。 */
const THUMBNAILS_PER_PAGE = 12;

/**
 * 「Let's Blog: Image Gallery」用のWebviewパネル。
 *
 * サーバーに保存済みの生成画像を一覧し、選んだ画像を記事のassets/へ保存して
 * Markdownへ挿入する。画像生成パネルで作った画像を後から再利用するための入口。
 */
export class ImageGalleryPanel extends WebviewPanelBase<
  ImageGalleryInboundMessage,
  ImageGalleryOutboundCommand
> {
  /**
   * Image Galleryパネルを開く。既に開いていれば前面に出す。
   * @param baseDir 取り込んだ画像の保存先(この直下のassets/へ書き出す)
   */
  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    baseDir: string,
    projectId: number
  ): void {
    showSingletonPanel(
      'letsBlog.imageGallery',
      () => new ImageGalleryPanel(context, editor, baseDir, projectId)
    );
  }

  private constructor(
    context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _baseDir: string,
    private readonly _projectId: number
  ) {
    super(context, {
      viewType: 'letsBlog.imageGallery',
      title: 'Image Gallery',
      assetName: 'imageGallery',
    });
  }

  /** Webviewからのコマンドを対応する処理へ振り分ける。 */
  protected async handleMessage(message: ImageGalleryInboundMessage): Promise<void> {
    switch (message.command) {
      case 'loadImages':
        return this._handleLoadImages();
      case 'loadThumbnails':
        return this._handleLoadThumbnails(message);
      case 'insertImage':
        return this._handleInsertImage(message);
      case 'setAsEyecatch':
        return this._handleSetAsEyecatch(message);
      case 'deleteImage':
        return this._handleDeleteImage(message);
      case 'regenerateWithSettings':
        return this._handleRegenerateWithSettings(message);
      case 'cancel':
        this.cancelCurrentOperation();
        return;
    }
  }

  private async _requireCredentials(): Promise<{ apiKey: string; actor: api.Actor | undefined }> {
    const apiKey = await requireAccessToken(this.context);
    const actor = await getActor(this.context);
    return { apiKey, actor };
  }

  private async _handleLoadImages(): Promise<void> {
    const { apiKey, actor } = await this._requireCredentials();
    const images = await api.listGeneratedImages(getServerUrl(), apiKey, actor, this._projectId);
    this.postMessage('imageList', { images, thumbnailsPerPage: THUMBNAILS_PER_PAGE });
  }

  /**
   * 指定されたIDのサムネイルだけをデータURIとして返す。
   * 一覧の全件を一度に転送すると、生成画像が増えるほどメモリと転送量が膨らむため、
   * 表示中のページ分だけを都度読み込む。
   */
  private async _handleLoadThumbnails(
    message: Extract<ImageGalleryInboundMessage, { command: 'loadThumbnails' }>
  ): Promise<void> {
    const { apiKey, actor } = await this._requireCredentials();
    const ids = message.imageIds.slice(0, THUMBNAILS_PER_PAGE);

    const thumbnails = await this.runCancellable(async () => {
      const loaded: { id: number; dataUri: string }[] = [];
      for (const id of ids) {
        const buffer = await api.downloadGeneratedImage(getServerUrl(), apiKey, actor, id);
        loaded.push({ id, dataUri: `data:image/png;base64,${buffer.toString('base64')}` });
      }
      return loaded;
    });

    this.postMessage('thumbnails', { thumbnails });
  }

  private async _handleInsertImage(
    message: Extract<ImageGalleryInboundMessage, { command: 'insertImage' }>
  ): Promise<void> {
    const fileName = await this._saveToAssets(message.imageId);

    const altText = (message.prompt ?? '').trim() || '生成画像';
    const markdownImage = `![${altText}](assets/${fileName})`;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(this._editor.document.uri, this._editor.selection.active, markdownImage);
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();

    this.postMessage('imageInserted', { fileName });
    vscode.window.showInformationMessage(`'assets/${fileName}' を記事へ挿入しました。`);
  }

  private async _handleSetAsEyecatch(
    message: Extract<ImageGalleryInboundMessage, { command: 'setAsEyecatch' }>
  ): Promise<void> {
    const fileName = await this._saveToAssets(message.imageId);

    // アイキャッチはfront matterへ書き込む。imageGenPanelと同じ規約に揃える。
    const { parseArticle, stringifyArticle } = await import('./frontMatter');
    const article = parseArticle(this._editor.document.getText());
    article.data.featured_image = `assets/${fileName}`;

    const document = this._editor.document;
    const fullRange = new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length));
    const edit = new vscode.WorkspaceEdit();
    edit.replace(document.uri, fullRange, stringifyArticle(article));
    await vscode.workspace.applyEdit(edit);
    await document.save();

    this.postMessage('eyecatchSet', { fileName });
    vscode.window.showInformationMessage(`アイキャッチを 'assets/${fileName}' に設定しました。`);
  }

  private async _handleDeleteImage(
    message: Extract<ImageGalleryInboundMessage, { command: 'deleteImage' }>
  ): Promise<void> {
    const confirmation = await vscode.window.showWarningMessage(
      `サーバー上の生成画像(ID: ${message.imageId})を削除します。取り消せません。よろしいですか?`,
      { modal: true },
      '削除する'
    );
    if (confirmation !== '削除する') {
      this.postMessage('deleteCancelled', { imageId: message.imageId });
      return;
    }

    const { apiKey, actor } = await this._requireCredentials();
    await api.deleteGeneratedImage(getServerUrl(), apiKey, actor, message.imageId);
    // 一覧のキャッシュに削除済みの画像が残らないようにする。
    api.invalidateProjectCache(this._projectId);

    this.postMessage('imageDeleted', { imageId: message.imageId });
    vscode.window.showInformationMessage(`生成画像(ID: ${message.imageId})を削除しました。`);
  }

  /**
   * 右クリックメニューの「この設定で画像生成」(issue #294)。選択画像の生成パラメータを
   * 取得し、それを反映した状態でGenerate Imageパネルを開く(既に開いていれば前面に出して反映)。
   */
  private async _handleRegenerateWithSettings(
    message: Extract<ImageGalleryInboundMessage, { command: 'regenerateWithSettings' }>
  ): Promise<void> {
    const { apiKey, actor } = await this._requireCredentials();
    const detail = await api.getGeneratedImageDetail(getServerUrl(), apiKey, actor, message.imageId);

    const { ImageGenPanel } = await import('./imageGenPanel');
    ImageGenPanel.createOrShow(this.context, this._editor, this._baseDir, this._projectId, detail);
  }

  /** サーバーから画像を取得し、{baseDir}/assets 配下へ保存してファイル名を返す。 */
  private async _saveToAssets(imageId: number): Promise<string> {
    const { apiKey, actor } = await this._requireCredentials();
    const buffer = await api.downloadGeneratedImage(getServerUrl(), apiKey, actor, imageId);

    const assetsDir = path.join(this._baseDir, 'assets');
    fs.mkdirSync(assetsDir, { recursive: true });

    // サーバーの保存形式はPNG。ファイル名はIDとタイムスタンプから組み立て、
    // サーバー応答由来の文字列をパスに混ぜない。
    const fileName = `gallery-${imageId}-${Date.now()}.png`;
    fs.writeFileSync(path.join(assetsDir, fileName), buffer);
    return fileName;
  }
}
