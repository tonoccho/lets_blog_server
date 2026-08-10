import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { Actor, getActor, getProjectId, getServerUrl, requireApiKey } from './config';
import { LetsBlogFrontMatter, stringifyArticle } from './frontMatter';
import { messageOf } from './errorHandler';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { PlanInboundMessage, PlanOutboundCommand } from './webviewMessages';

const GITHUB_ISSUE_URL_PATTERN = /^(https:\/\/github\.com\/[^/]+\/[^/]+)\/issues\/\d+$/;

/**
 * 「Let's Blog: Plan Article」用のWebviewパネル。
 * GitHub Issueを起点に、壁打ちチャット → 記事構成の提案とIssueへの反映 →
 * メタデータ提案 → 記事スキャフォールド生成までを担う。
 */
export class PlanPanel extends WebviewPanelBase<PlanInboundMessage, PlanOutboundCommand> {
  private _lastArticlePath: string | undefined;

  public static createOrShow(context: vscode.ExtensionContext): void {
    showSingletonPanel('letsBlog.articlePlan', () => new PlanPanel(context));
  }

  private constructor(context: vscode.ExtensionContext) {
    super(context, { viewType: 'letsBlog.articlePlan', title: 'Article Plan', assetName: 'plan' });
  }

  protected async handleMessage(message: PlanInboundMessage): Promise<void> {
    switch (message.command) {
      case 'loadIssues':
        return this._handleLoadIssues();
      case 'loadCategories':
        return this._handleLoadCategories();
      case 'sendChat':
        return this._handleSendChat(message);
      case 'suggestStructure':
        return this._handleSuggestStructure(message);
      case 'acceptStructure':
        return this._handleAcceptStructure(message);
      case 'suggestMetadata':
        return this._handleSuggestMetadata(message);
      case 'approveAndScaffold':
        return this._handleApproveAndScaffold(message);
      case 'openArticle':
        return this._handleOpenArticle();
    }
  }

  /** APIキー・ログインユーザー・選択中プロジェクトが揃っていることを確認して取り出す。 */
  private async _requireContext(): Promise<{ apiKey: string; actor: Actor; projectId: number }> {
    const actor = await getActor(this.context);
    const projectId = getProjectId(this.context);
    if (!actor || !projectId) {
      throw new Error(
        'ユーザーまたはプロジェクトが未選択です。「Let\'s Blog: Login」「Let\'s Blog: Select Project」を先に実行してください。'
      );
    }
    const apiKey = await requireApiKey(this.context);
    return { apiKey, actor, projectId };
  }

  private async _handleLoadIssues(): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const issues = await api.listUnassignedIssues(getServerUrl(), apiKey, actor, projectId);
    this.postMessage('issueList', { issues });
  }

  private async _handleSendChat(message: Extract<PlanInboundMessage, { command: 'sendChat' }>): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const response = await api.postPlanChat(getServerUrl(), apiKey, actor, projectId, {
      history: message.history,
      message: message.message,
      sessionId: message.sessionId,
      githubIssueNumber: message.issueNumber,
    });
    this.postMessage('chatResponse', response);
  }

  private async _handleSuggestStructure(
    message: Extract<PlanInboundMessage, { command: 'suggestStructure' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const suggestion = await api.suggestArticleStructure(getServerUrl(), apiKey, actor, projectId, message.history);
    this.postMessage('structureSuggestion', suggestion);
  }

  private async _handleAcceptStructure(
    message: Extract<PlanInboundMessage, { command: 'acceptStructure' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const result = await api.acceptArticleStructure(
      getServerUrl(),
      apiKey,
      actor,
      projectId,
      message.issueNumber,
      message.structure
    );
    this.postMessage('structureAccepted', result);
  }

  private async _handleLoadCategories(): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const categories = await api.listExistingCategories(getServerUrl(), apiKey, actor, projectId);
    this.postMessage('categoryList', { categories });
  }

  private async _handleSuggestMetadata(
    message: Extract<PlanInboundMessage, { command: 'suggestMetadata' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const suggestion = await api.suggestMetadata(getServerUrl(), apiKey, actor, projectId, message.history);
    this.postMessage('metadataSuggestion', suggestion);
  }

  private async _handleApproveAndScaffold(
    message: Extract<PlanInboundMessage, { command: 'approveAndScaffold' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const { issue, metadata } = message;

    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      throw new Error('ワークスペースフォルダが開かれていません。');
    }

    const articlesPath = path.join(workspaceFolder.uri.fsPath, 'articles', metadata.slug);
    if (fs.existsSync(articlesPath)) {
      const overwrite = await vscode.window.showWarningMessage(
        `articles/${metadata.slug} は既に存在します。上書きしますか?`,
        'Yes',
        'No'
      );
      if (overwrite !== 'Yes') {
        this.postMessage('error', { error: 'キャンセルしました。' });
        return;
      }
    }

    fs.mkdirSync(articlesPath, { recursive: true });
    fs.mkdirSync(path.join(articlesPath, 'assets'), { recursive: true });
    fs.writeFileSync(path.join(articlesPath, 'assets', '.gitkeep'), '');

    const description = await api.getIssueDescription(getServerUrl(), apiKey, actor, projectId, issue.number);

    const githubRepositoryMatch = issue.htmlUrl.match(GITHUB_ISSUE_URL_PATTERN);
    const frontMatter: LetsBlogFrontMatter = {
      title: metadata.title,
      slug: metadata.slug,
      categories: metadata.categories,
      tags: metadata.tags,
      status: 'draft',
      github_issue_number: issue.number,
      github_repository: githubRepositoryMatch?.[1],
      project_id: projectId,
    };
    const content = description.trim().length > 0 ? description : '記事本文をここに記入してください。';
    const articlePath = path.join(articlesPath, 'article.md');
    fs.writeFileSync(articlePath, stringifyArticle({ data: frontMatter, content }), 'utf-8');
    this._lastArticlePath = articlePath;

    try {
      await api.assignIssue(getServerUrl(), apiKey, actor, projectId, issue.number);
    } catch (error) {
      this.postMessage('error', {
        error: `記事ファイルは生成されましたが、issueの割り当てに失敗しました: ${messageOf(error)}`,
      });
      await this._handleOpenArticle();
      return;
    }

    this.postMessage('scaffoldCreated', {});
  }

  private async _handleOpenArticle(): Promise<void> {
    if (!this._lastArticlePath) return;
    const doc = await vscode.workspace.openTextDocument(this._lastArticlePath);
    await vscode.window.showTextDocument(doc);
  }
}
