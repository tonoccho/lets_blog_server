import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { getActor, requireAccessToken } from './config';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { DiagramGalleryInboundMessage, DiagramGalleryOutboundCommand } from './webviewMessages';

/**
 * 「Let's Blog: Diagram Gallery」用のWebviewパネル。
 *
 * サーバーに保存済みのダイアグラムを一覧し、選んだダイアグラムを記事のassets/へ保存して
 * Markdownへ挿入する。Diagram Editorで作ったダイアグラムを後から再利用するための入口。
 */
export class DiagramGalleryPanel extends WebviewPanelBase<
  DiagramGalleryInboundMessage,
  DiagramGalleryOutboundCommand
> {
  /**
   * Diagram Galleryパネルを開く。既に開いていれば前面に出す。
   * @param baseDir 取り込んだダイアグラムの保存先(この直下のassets/へ書き出す)
   */
  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    baseDir: string,
    projectId: number
  ): void {
    showSingletonPanel(
      'letsBlog.diagramGallery',
      () => new DiagramGalleryPanel(context, editor, baseDir, projectId)
    );
  }

  private constructor(
    context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _baseDir: string,
    private readonly _projectId: number
  ) {
    super(context, {
      viewType: 'letsBlog.diagramGallery',
      title: 'Diagram Gallery',
      assetName: 'diagramGallery',
    });
  }

  protected async handleMessage(message: DiagramGalleryInboundMessage): Promise<void> {
    switch (message.command) {
      case 'loadDiagrams':
        return this._handleLoadDiagrams();
      case 'loadThumbnails':
        return this._handleLoadThumbnails(message);
      case 'insertDiagram':
        return this._handleInsertDiagram(message);
      case 'deleteDiagram':
        return this._handleDeleteDiagram(message);
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

  private async _handleLoadDiagrams(): Promise<void> {
    const { apiKey, actor } = await this._requireCredentials();
    const diagrams = await api.listDiagrams(apiKey, actor, this._projectId);
    this.postMessage('diagramList', { diagrams });
  }

  private async _handleLoadThumbnails(
    message: Extract<DiagramGalleryInboundMessage, { command: 'loadThumbnails' }>
  ): Promise<void> {
    const { apiKey, actor } = await this._requireCredentials();

    const thumbnails = await this.runCancellable(async () => {
      const loaded: { id: number; dataUri: string }[] = [];
      for (const id of message.diagramIds) {
        const svg = await api.getDiagramSvg(apiKey, actor, id);
        loaded.push({ id, dataUri: `data:image/svg+xml;base64,${Buffer.from(svg, 'utf-8').toString('base64')}` });
      }
      return loaded;
    });

    this.postMessage('thumbnails', { thumbnails });
  }

  private async _handleInsertDiagram(
    message: Extract<DiagramGalleryInboundMessage, { command: 'insertDiagram' }>
  ): Promise<void> {
    const fileName = await this._saveToAssets(message.diagramId);

    const altText = message.name.trim() || 'ダイアグラム';
    const markdownImage = `![${altText}](assets/${fileName})`;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(this._editor.document.uri, this._editor.selection.active, markdownImage);
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();

    this.postMessage('diagramInserted', { fileName });
    vscode.window.showInformationMessage(`'assets/${fileName}' を記事へ挿入しました。`);
  }

  private async _handleDeleteDiagram(
    message: Extract<DiagramGalleryInboundMessage, { command: 'deleteDiagram' }>
  ): Promise<void> {
    const confirmation = await vscode.window.showWarningMessage(
      `サーバー上のダイアグラム(ID: ${message.diagramId})を削除します。取り消せません。よろしいですか?`,
      { modal: true },
      '削除する'
    );
    if (confirmation !== '削除する') {
      this.postMessage('deleteCancelled', { diagramId: message.diagramId });
      return;
    }

    const { apiKey, actor } = await this._requireCredentials();
    await api.deleteDiagram(apiKey, actor, message.diagramId, this._projectId);

    this.postMessage('diagramDeleted', { diagramId: message.diagramId });
    vscode.window.showInformationMessage(`ダイアグラム(ID: ${message.diagramId})を削除しました。`);
  }

  /** サーバーからSVGを取得し、{baseDir}/assets 配下へ保存してファイル名を返す。 */
  private async _saveToAssets(diagramId: number): Promise<string> {
    const { apiKey, actor } = await this._requireCredentials();
    const svg = await api.getDiagramSvg(apiKey, actor, diagramId);

    const assetsDir = path.join(this._baseDir, 'assets');
    fs.mkdirSync(assetsDir, { recursive: true });

    const fileName = `diagram-${diagramId}-${Date.now()}.svg`;
    fs.writeFileSync(path.join(assetsDir, fileName), svg, 'utf-8');
    return fileName;
  }
}
