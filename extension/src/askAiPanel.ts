import * as vscode from 'vscode';
import * as api from './apiClient';
import { getActor, getConfiguredAiProvider, getServerUrl, requireAccessToken } from './config';
import { buildSourcesSection } from './markdownSources';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { AskAiInboundMessage, AskAiInitPayload, AskAiOutboundCommand } from './webviewMessages';

/**
 * エディタ右クリックメニュー「Ask AI」用のWebviewパネル(issue #526)。
 * 質問を入力するとBrave Search + LLMで調査結果を要約し、右クリック時点の選択範囲
 * (無ければカーソル位置)へ、要約文+出典リンクをMarkdown形式で挿入する。
 */
export class AskAiPanel extends WebviewPanelBase<AskAiInboundMessage, AskAiOutboundCommand> {

  /** Ask AIパネルを開く。既に開いていれば前面に出す。 */
  static createOrShow(context: vscode.ExtensionContext, editor: vscode.TextEditor): void {
    showSingletonPanel('letsBlog.askAiSearch', () => new AskAiPanel(context, editor));
  }

  private _lastResult: api.AiAskResult | undefined;

  private constructor(
    context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor
  ) {
    super(context, {
      viewType: 'letsBlog.askAiSearch',
      title: 'Ask AI',
      assetName: 'askAi',
    });
  }

  /** Webviewからのコマンドを対応する処理へ振り分ける。 */
  protected async handleMessage(message: AskAiInboundMessage): Promise<void> {
    switch (message.command) {
      case 'init':
        return this._handleInit();
      case 'cancel':
        this.cancelCurrentOperation();
        return;
      case 'ask':
        return this._handleAsk(message);
      case 'insert':
        return this._handleInsert(message);
    }
  }

  private async _handleInit(): Promise<void> {
    const payload: AskAiInitPayload = {
      defaultAiProvider: getConfiguredAiProvider(),
    };
    this.postMessage('init', payload);
  }

  private async _handleAsk(
    message: Extract<AskAiInboundMessage, { command: 'ask' }>
  ): Promise<void> {
    const apiKey = await requireAccessToken(this.context);
    const actor = await getActor(this.context);
    const result = await this.runCancellable((signal) =>
      api.askAiSearch(getServerUrl(), apiKey, actor, message.question, message.provider, signal)
    );
    this._lastResult = result;
    this.postMessage('answered', result);
  }

  /**
   * 回答(要約+出典リンク)を、パネルを開いた時点の選択範囲(無ければカーソル位置)へ挿入する。
   * 出典はSourceReferenceのtitle/urlのみを使ってMarkdownリンクへ整形するため、
   * AIやWeb検索結果由来の文字列がHTMLとして解釈される余地はない。
   */
  private async _handleInsert(
    message: Extract<AskAiInboundMessage, { command: 'insert' }>
  ): Promise<void> {
    const text = this._lastResult
      ? message.text + buildSourcesSection(this._lastResult.sources, this._lastResult.searchNote)
      : message.text;

    const edit = new vscode.WorkspaceEdit();
    if (!this._editor.selection.isEmpty) {
      edit.replace(this._editor.document.uri, this._editor.selection, text);
    } else {
      edit.insert(this._editor.document.uri, this._editor.selection.active, text);
    }
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();
    this.postMessage('inserted', {});
    vscode.window.showInformationMessage('記事に挿入しました。');
  }
}
