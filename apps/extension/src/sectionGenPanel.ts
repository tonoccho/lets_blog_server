import * as vscode from 'vscode';
import * as api from './apiClient';
import { getActor, getConfiguredAiProvider, requireAccessToken } from './config';
import { SectionContext } from './headingContext';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import {
  SectionGenInboundMessage,
  SectionGenInitPayload,
  SectionGenOutboundCommand,
} from './webviewMessages';

/**
 * 「Let's Blog: Generate Section」用のWebviewパネル。カーソル位置の見出し階層から
 * 自動判定したモード(本文/リード文/サブセクション考慮リード文)で生成し、追加の指示による
 * 壁打ち(再生成)を経て、選択範囲があれば置換・なければカーソル位置へ挿入する。
 */
export class SectionGenPanel extends WebviewPanelBase<SectionGenInboundMessage, SectionGenOutboundCommand> {

  /**
   * Generate Sectionパネルを開く。既に開いていれば前面に出す。
   * @param sectionContext カーソル位置から判定した生成モードと文脈
   */
  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    articleTitle: string | undefined,
    sectionContext: SectionContext
  ): void {
    showSingletonPanel('letsBlog.sectionGen', () => new SectionGenPanel(context, editor, articleTitle, sectionContext));
  }

  private constructor(
    context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _articleTitle: string | undefined,
    private readonly _sectionContext: SectionContext
  ) {
    super(context, {
      viewType: 'letsBlog.sectionGen',
      title: 'Generate Section',
      assetName: 'sectionGen',
    });
  }

  /** Webviewからのコマンドを対応する処理へ振り分ける。 */
  protected async handleMessage(message: SectionGenInboundMessage): Promise<void> {
    switch (message.command) {
      case 'init':
        return this._handleInit();
      case 'cancel':
        this.cancelCurrentOperation();
        return;
      case 'generate':
        return this._handleGenerate(message);
      case 'insert':
        return this._handleInsert(message);
    }
  }

  private async _handleInit(): Promise<void> {
    const payload: SectionGenInitPayload = {
      articleTitle: this._articleTitle ?? '',
      selectedText: this._editor.document.getText(this._editor.selection),
      sectionContext: this._sectionContext,
      defaultAiProvider: getConfiguredAiProvider(),
    };
    this.postMessage('init', payload);
  }

  private async _handleGenerate(
    message: Extract<SectionGenInboundMessage, { command: 'generate' }>
  ): Promise<void> {
    const apiKey = await requireAccessToken(this.context);
    const actor = await getActor(this.context);
    const result = await this.runCancellable((signal) =>
      api.generateSection(apiKey, actor, message.params, signal)
    );
    this.postMessage('generated', result);
  }

  private async _handleInsert(
    message: Extract<SectionGenInboundMessage, { command: 'insert' }>
  ): Promise<void> {
    const edit = new vscode.WorkspaceEdit();
    if (!this._editor.selection.isEmpty) {
      edit.replace(this._editor.document.uri, this._editor.selection, message.text);
    } else {
      edit.insert(this._editor.document.uri, this._editor.selection.active, message.text);
    }
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();
    this.postMessage('inserted', {});
    vscode.window.showInformationMessage('記事に挿入しました。');
  }
}
