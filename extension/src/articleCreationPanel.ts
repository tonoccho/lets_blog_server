import * as vscode from 'vscode';
import * as api from './apiClient';
import { Actor, getActor, getProjectId, getServerUrl, requireApiKey, setProjectId } from './config';
import { buildArticleFrontMatter } from './frontMatter';
import { createArticleScaffold, openArticle, requireWorkspaceRoot } from './articleScaffold';
import { showSingletonPanel, WebviewPanelBase } from './webviewPanelBase';
import { ArticleCreationInboundMessage, ArticleCreationOutboundCommand } from './webviewMessages';

/**
 * 「Let's Blog: Create Article」用のWebviewパネル。
 *
 * Article Plan がGitHub Issueを起点にするのに対し、こちらはIssueを持たない記事を
 * コマンドから直接起こすための入口。生成される記事の配置とfront matterの項目は
 * Article Plan と同じ(articleScaffold.ts / buildArticleFrontMatter に集約)。
 */
export class ArticleCreationPanel extends WebviewPanelBase<
  ArticleCreationInboundMessage,
  ArticleCreationOutboundCommand
> {
  /** Create Articleパネルを開く。既に開いていれば前面に出す。 */
  public static createOrShow(context: vscode.ExtensionContext): void {
    showSingletonPanel('letsBlog.createArticle', () => new ArticleCreationPanel(context));
  }

  private constructor(context: vscode.ExtensionContext) {
    super(context, {
      viewType: 'letsBlog.createArticle',
      title: 'Create Article',
      assetName: 'articleCreation',
    });
  }

  /** Webviewからのコマンドを対応する処理へ振り分ける。 */
  protected async handleMessage(message: ArticleCreationInboundMessage): Promise<void> {
    switch (message.command) {
      case 'loadProjects':
        return this._handleLoadProjects();
      case 'close':
        // 入力途中で中断したい場合の退避口(Escape)。
        this.close();
        return;
      case 'cancel':
        this.cancelCurrentOperation();
        return;
      case 'loadCategories':
        return this._handleLoadCategories(message);
      case 'sendChat':
        return this._handleSendChat(message);
      case 'suggestMetadata':
        return this._handleSuggestMetadata(message);
      case 'suggestStructure':
        return this._handleSuggestStructure(message);
      case 'createArticle':
        return this._handleCreateArticle(message);
    }
  }

  private async _handleLoadProjects(): Promise<void> {
    const apiKey = await requireApiKey(this.context);
    const actor = await getActor(this.context);
    const projects = await api.listProjects(getServerUrl(), apiKey, actor);
    this.postMessage('projectList', {
      projects,
      // 直前に選択していたプロジェクトを初期選択にする。
      selectedProjectId: getProjectId(this.context) ?? null,
    });
  }

  /**
   * 選択中プロジェクトの既存カテゴリを返す。サイト未紐付け等で取得できない場合も
   * 記事作成自体は続けられるよう、失敗を通知して空一覧として扱う。
   */
  private async _handleLoadCategories(
    message: Extract<ArticleCreationInboundMessage, { command: 'loadCategories' }>
  ): Promise<void> {
    const apiKey = await requireApiKey(this.context);
    const actor = await getActor(this.context);
    if (!actor) {
      throw new Error('ログインしていません。「Let\'s Blog: Login」を先に実行してください。');
    }
    try {
      const categories = await api.listExistingCategoriesWithParents(
        getServerUrl(),
        apiKey,
        actor,
        message.projectId
      );
      this.postMessage('categoryList', { categories });
    } catch {
      this.postMessage('categoryList', { categories: [] });
    }
  }

  /** チャットメッセージ(と履歴)からAIブレインストーミングの応答を返す。 */
  private async _handleSendChat(
    message: Extract<ArticleCreationInboundMessage, { command: 'sendChat' }>
  ): Promise<void> {
    const apiKey = await requireApiKey(this.context);
    const actor = await this._requireActor();
    const response = await this.runCancellable((signal) =>
      api.postPlanChat(
        getServerUrl(),
        apiKey,
        actor,
        message.projectId,
        { history: message.history, message: message.message, sessionId: message.sessionId },
        signal
      )
    );
    this.postMessage('chatResponse', response);
  }

  /** 壁打ちの会話履歴から、タイトル・スラッグ(各5件)・カテゴリ・タグを提案する。 */
  private async _handleSuggestMetadata(
    message: Extract<ArticleCreationInboundMessage, { command: 'suggestMetadata' }>
  ): Promise<void> {
    const apiKey = await requireApiKey(this.context);
    const actor = await this._requireActor();
    const suggestion = await this.runCancellable((signal) =>
      api.suggestMetadata(getServerUrl(), apiKey, actor, message.projectId, message.history, signal)
    );
    this.postMessage('metadataSuggestion', suggestion);
  }

  /** 壁打ちの会話履歴から、記事の構成案(見出し構造)を提案する。 */
  private async _handleSuggestStructure(
    message: Extract<ArticleCreationInboundMessage, { command: 'suggestStructure' }>
  ): Promise<void> {
    const apiKey = await requireApiKey(this.context);
    const actor = await this._requireActor();
    const suggestion = await this.runCancellable((signal) =>
      api.suggestArticleStructure(getServerUrl(), apiKey, actor, message.projectId, message.history, signal)
    );
    this.postMessage('structureSuggestion', suggestion);
  }

  private async _requireActor(): Promise<Actor> {
    const actor = await getActor(this.context);
    if (!actor) {
      throw new Error('ログインしていません。「Let\'s Blog: Login」を先に実行してください。');
    }
    return actor;
  }

  private async _handleCreateArticle(
    message: Extract<ArticleCreationInboundMessage, { command: 'createArticle' }>
  ): Promise<void> {
    const { title, slug, categories, tags, status, projectId } = message.metadata;
    const workspaceRoot = requireWorkspaceRoot();

    const result = await createArticleScaffold({
      workspaceRoot,
      slug,
      frontMatter: buildArticleFrontMatter({ title, slug, projectId, categories, tags, status }),
      content: message.content ?? '',
    });
    if (!result) {
      this.postMessage('error', { error: 'キャンセルしました。' });
      return;
    }

    // 作成したプロジェクトを以降の既定にしておく(投稿や画像生成で再選択せずに済む)。
    await setProjectId(this.context, projectId);

    this.postMessage('articleCreated', { slug });
    await openArticle(result.articlePath);
    vscode.window.showInformationMessage(`articles/${slug}/article.md を作成しました。`);
  }
}
