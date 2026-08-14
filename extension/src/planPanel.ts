import * as vscode from 'vscode';
import * as api from './apiClient';
import { Actor, getActor, getProjectId, getServerUrl, requireApiKey } from './config';
import { buildArticleFrontMatter } from './frontMatter';
import { createArticleScaffold, openArticle, requireWorkspaceRoot } from './articleScaffold';
import { messageOf } from './errorHandler';
import { extractIssueOutline, formatOutlineAsMarkdown } from './issueParser';
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

  /** Article Planパネルを開く。既に開いていれば前面に出す。 */
  public static createOrShow(context: vscode.ExtensionContext): void {
    showSingletonPanel('letsBlog.articlePlan', () => new PlanPanel(context));
  }

  private constructor(context: vscode.ExtensionContext) {
    super(context, { viewType: 'letsBlog.articlePlan', title: 'Article Plan', assetName: 'plan' });
  }

  /** Webviewからのコマンドを対応する処理へ振り分ける。 */
  protected async handleMessage(message: PlanInboundMessage): Promise<void> {
    switch (message.command) {
      case 'loadIssues':
        return this._handleLoadIssues();
      case 'cancel':
        this.cancelCurrentOperation();
        return;
      case 'loadCategories':
        return this._handleLoadCategories();
      case 'loadIssueOutline':
        return this._handleLoadIssueOutline(message);
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

  /**
   * 選択されたIssueの本文から見出し構造を抽出し、記事構成の初期案としてWebviewへ返す。
   * Issueに構造が書かれていない場合は空を返し、従来どおりAIによる提案へ委ねる。
   */
  private async _handleLoadIssueOutline(
    message: Extract<PlanInboundMessage, { command: 'loadIssueOutline' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const description = await api.getIssueDescription(
      getServerUrl(),
      apiKey,
      actor,
      projectId,
      message.issueNumber
    );
    const outline = extractIssueOutline(description);
    this.postMessage('issueOutline', {
      issueNumber: message.issueNumber,
      structure: formatOutlineAsMarkdown(outline),
      headingCount: outline.length,
    });
  }

  private async _handleSendChat(message: Extract<PlanInboundMessage, { command: 'sendChat' }>): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const response = await this.runCancellable((signal) =>
      api.postPlanChat(
        getServerUrl(),
        apiKey,
        actor,
        projectId,
        {
          history: message.history,
          message: message.message,
          sessionId: message.sessionId,
          githubIssueNumber: message.issueNumber,
        },
        signal
      )
    );
    this.postMessage('chatResponse', response);
  }

  private async _handleSuggestStructure(
    message: Extract<PlanInboundMessage, { command: 'suggestStructure' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const suggestion = await this.runCancellable((signal) =>
      api.suggestArticleStructure(getServerUrl(), apiKey, actor, projectId, message.history, signal)
    );
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
    const categories = await api.listExistingCategoriesWithParents(getServerUrl(), apiKey, actor, projectId);
    this.postMessage('categoryList', { categories });
  }

  private async _handleSuggestMetadata(
    message: Extract<PlanInboundMessage, { command: 'suggestMetadata' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const suggestion = await this.runCancellable((signal) =>
      api.suggestMetadata(getServerUrl(), apiKey, actor, projectId, message.history, signal)
    );
    this.postMessage('metadataSuggestion', suggestion);
  }

  private async _handleApproveAndScaffold(
    message: Extract<PlanInboundMessage, { command: 'approveAndScaffold' }>
  ): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const { issue, metadata } = message;

    const description = await api.getIssueDescription(getServerUrl(), apiKey, actor, projectId, issue.number);
    const githubRepositoryMatch = issue.htmlUrl.match(GITHUB_ISSUE_URL_PATTERN);

    const scaffold = await createArticleScaffold({
      workspaceRoot: requireWorkspaceRoot(),
      slug: metadata.slug,
      frontMatter: buildArticleFrontMatter({
        title: metadata.title,
        slug: metadata.slug,
        projectId,
        categories: metadata.categories,
        tags: metadata.tags,
        githubIssueNumber: issue.number,
        githubRepository: githubRepositoryMatch?.[1],
      }),
      content: description,
    });
    if (!scaffold) {
      this.postMessage('error', { error: 'キャンセルしました。' });
      return;
    }
    this._lastArticlePath = scaffold.articlePath;

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
    await openArticle(this._lastArticlePath);
  }
}
