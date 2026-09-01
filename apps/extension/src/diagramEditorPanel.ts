import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { getActor, getServerUrl, requireAccessToken } from './config';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { DiagramEditorInboundMessage, DiagramEditorOutboundCommand } from './webviewMessages';

/** ダイアグラムのMarkdown参照ファイル名。'Edit diagram'はカーソル行をこの形式で判定する。 */
export const DIAGRAM_REFERENCE_PATTERN = /assets\/(diagram-(\d+)-\d+\.svg)/;

export type DiagramEditorMode =
  | { kind: 'create' }
  | { kind: 'edit'; diagramId: number; name: string; xml: string; existingFileName: string };

/**
 * 「Add New Diagram」/「Edit Diagram」用のWebviewパネル。
 *
 * draw.ioの自己ホストサーバー(embed mode)をiframeで開き、postMessageで
 * xml(再編集用)とsvg(記事挿入・サーバー保存用)を受け取ってサーバーへ保存する。
 * 編集モードでは「上書き保存」と「新規保存として保存」を選べる(issue #476)。
 */
export class DiagramEditorPanel extends WebviewPanelBase<
  DiagramEditorInboundMessage,
  DiagramEditorOutboundCommand
> {
  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    baseDir: string,
    projectId: number,
    mode: DiagramEditorMode
  ): void {
    showSingletonPanel(
      'letsBlog.diagramEditor',
      () => new DiagramEditorPanel(context, editor, baseDir, projectId, mode)
    );
  }

  private constructor(
    context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _baseDir: string,
    private readonly _projectId: number,
    private readonly _mode: DiagramEditorMode
  ) {
    super(context, {
      viewType: 'letsBlog.diagramEditor',
      title: _mode.kind === 'edit' ? `Edit Diagram: ${_mode.name}` : 'New Diagram',
      assetName: 'diagramEditor',
      extraCspDirectives: [`frame-src ${getServerUrl()}`],
    });
  }

  protected async handleMessage(message: DiagramEditorInboundMessage): Promise<void> {
    switch (message.command) {
      case 'ready':
        return this._handleReady();
      case 'insertNew':
        return this._handleInsertNew(message);
      case 'saveOverwrite':
        return this._handleSaveOverwrite(message);
      case 'saveAsNew':
        return this._handleSaveAsNew(message);
      case 'cancel':
        this.close();
        return;
    }
  }

  private async _requireCredentials(): Promise<{ apiKey: string; actor: api.Actor | undefined }> {
    const apiKey = await requireAccessToken(this.context);
    const actor = await getActor(this.context);
    return { apiKey, actor };
  }

  private _handleReady(): void {
    this.postMessage('init', {
      mode: this._mode.kind,
      name: this._mode.kind === 'edit' ? this._mode.name : '無題のダイアグラム',
      xml: this._mode.kind === 'edit' ? this._mode.xml : '',
      drawioUrl: `${getServerUrl()}/drawio/?embed=1&ui=min&spin=1&proto=json&configure=1&noSaveBtn=1&noExitBtn=1`,
    });
  }

  private async _handleInsertNew(
    message: Extract<DiagramEditorInboundMessage, { command: 'insertNew' }>
  ): Promise<void> {
    const { apiKey, actor } = await this._requireCredentials();
    const detail = await api.createDiagram(apiKey, actor, {
      projectId: this._projectId,
      name: message.name,
      xml: message.xml,
      svg: message.svg,
    });

    const fileName = await this._writeSvgToAssets(detail.id, detail.svg);
    await this._insertMarkdown(fileName, detail.name);

    this.postMessage('inserted', { fileName });
    vscode.window.showInformationMessage(`'assets/${fileName}' を記事へ挿入しました。`);
    this.close();
  }

  private async _handleSaveOverwrite(
    message: Extract<DiagramEditorInboundMessage, { command: 'saveOverwrite' }>
  ): Promise<void> {
    if (this._mode.kind !== 'edit') {
      throw new Error('編集対象のダイアグラムがありません。');
    }

    const { apiKey, actor } = await this._requireCredentials();
    const detail = await api.updateDiagram(
      apiKey,
      actor,
      this._mode.diagramId,
      { name: this._mode.name, xml: message.xml, svg: message.svg },
      this._projectId
    );

    const assetsDir = path.join(this._baseDir, 'assets');
    fs.mkdirSync(assetsDir, { recursive: true });
    fs.writeFileSync(path.join(assetsDir, this._mode.existingFileName), detail.svg, 'utf-8');

    this.postMessage('saved', {});
    vscode.window.showInformationMessage(`ダイアグラム(ID: ${detail.id})を上書き保存しました。`);
    this.close();
  }

  private async _handleSaveAsNew(
    message: Extract<DiagramEditorInboundMessage, { command: 'saveAsNew' }>
  ): Promise<void> {
    const { apiKey, actor } = await this._requireCredentials();
    const detail = await api.createDiagram(apiKey, actor, {
      projectId: this._projectId,
      name: message.name,
      xml: message.xml,
      svg: message.svg,
    });

    const fileName = await this._writeSvgToAssets(detail.id, detail.svg);
    await this._insertMarkdown(fileName, detail.name);

    this.postMessage('saved', {});
    vscode.window.showInformationMessage(`新しいダイアグラム(ID: ${detail.id})として 'assets/${fileName}' に保存しました。`);
    this.close();
  }

  /** {baseDir}/assets 配下へSVGを書き出し、ファイル名を返す。 */
  private async _writeSvgToAssets(diagramId: number, svg: string): Promise<string> {
    const assetsDir = path.join(this._baseDir, 'assets');
    fs.mkdirSync(assetsDir, { recursive: true });

    const fileName = `diagram-${diagramId}-${Date.now()}.svg`;
    fs.writeFileSync(path.join(assetsDir, fileName), svg, 'utf-8');
    return fileName;
  }

  /** カーソル位置にMarkdown画像参照を挿入して保存する。 */
  private async _insertMarkdown(fileName: string, name: string): Promise<void> {
    const altText = name.trim() || 'ダイアグラム';
    const markdownImage = `![${altText}](assets/${fileName})`;
    const edit = new vscode.WorkspaceEdit();
    edit.insert(this._editor.document.uri, this._editor.selection.active, markdownImage);
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();
  }
}
