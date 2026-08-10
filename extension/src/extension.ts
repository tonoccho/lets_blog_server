import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { getServerUrl, requireApiKey, setApiKey, getActor, setActor, getProjectId, setProjectId } from './config';
import {
  parseArticle,
  stringifyArticle,
  extractLocalImageReferences,
  resolveFeaturedImageReference,
  resolveExistingPostId,
  guessImageMimeType,
} from './frontMatter';
import * as api from './apiClient';
import { PlanPanel } from './planPanel';
import { PreviewPanel } from './previewPanel';
import { ImageGenPanel } from './imageGenPanel';
import { SectionGenPanel } from './sectionGenPanel';
import { resolveSectionContext } from './headingContext';
import { logger } from './logger';
import { reportError } from './errorHandler';

export function activate(context: vscode.ExtensionContext): void {
  logger.refreshFromConfiguration();
  logger.info("Let's Blog 拡張を有効化しました。");

  context.subscriptions.push(
    { dispose: () => logger.dispose() },
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration('letsBlog.debugMode')) {
        logger.refreshFromConfiguration();
      }
    })
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('letsBlog.login', () => commandLogin(context)),
    vscode.commands.registerCommand('letsBlog.setApiKey', () => commandSetApiKey(context)),
    vscode.commands.registerCommand('letsBlog.selectSite', () => commandSelectSite(context)),
    vscode.commands.registerCommand('letsBlog.publish', () => commandPublish(context)),
    vscode.commands.registerCommand('letsBlog.deletePost', () => commandDeletePost(context)),
    vscode.commands.registerCommand('letsBlog.askAi', () => commandAskAi(context)),
    vscode.commands.registerCommand('letsBlog.suggestTags', () => commandSuggestTags(context)),
    vscode.commands.registerCommand('letsBlog.generateImage', () => commandGenerateImage(context)),
    vscode.commands.registerCommand('letsBlog.generateSection', () => commandGenerateSection(context)),
    vscode.commands.registerCommand('letsBlog.selectProject', () => commandSelectProject(context)),
    vscode.commands.registerCommand('letsBlog.planArticle', () => commandPlanArticle(context)),
    vscode.commands.registerCommand('letsBlog.previewArticle', () => commandPreviewArticle(context))
  );
}

export function deactivate(): void {
  // no-op
}

function getActiveMarkdownEditor(): vscode.TextEditor | undefined {
  const editor = vscode.window.activeTextEditor;
  if (!editor) {
    vscode.window.showErrorMessage("Markdownファイルを開いた状態で実行してください。");
    return undefined;
  }
  return editor;
}

/**
 * AI生成結果末尾に付加する出典セクション。出典があるかのように装わないよう、
 * 検索失敗/未設定/0件時はsearchNoteでその旨を明示する。
 */
function buildSourcesSection(sources: api.SourceReference[], searchNote: string | null): string {
  if (sources.length === 0) {
    return searchNote ? `\n\n---\n*${searchNote}*\n` : '';
  }
  const list = sources.map((s) => `- [${s.title}](${s.url})`).join('\n');
  return `\n\n---\n**出典:**\n${list}\n`;
}

async function replaceDocumentText(editor: vscode.TextEditor, newText: string): Promise<void> {
  const fullRange = new vscode.Range(
    editor.document.positionAt(0),
    editor.document.positionAt(editor.document.getText().length)
  );
  await editor.edit((builder) => builder.replace(fullRange, newText));
  await editor.document.save();
}

/**
 * メールアドレス/パスワード(必要なら2FAコード)でLet's Blogにログインし、
 * 発行されたAPIキーをSecretStorageに保存する。ログインしたユーザーがそのままActorになる
 * (以前の「Select User」QuickPickによるActor選択は廃止し、ログインに一本化した)。
 */
async function commandLogin(context: vscode.ExtensionContext): Promise<void> {
  const email = await vscode.window.showInputBox({ prompt: 'メールアドレス', ignoreFocusOut: true });
  if (!email) return;

  const password = await vscode.window.showInputBox({
    prompt: 'パスワード',
    password: true,
    ignoreFocusOut: true,
  });
  if (!password) return;

  try {
    let result = await api.login(getServerUrl(), email, password);

    if (result.twoFactorRequired) {
      const code = await vscode.window.showInputBox({
        prompt: '2段階認証コードを入力してください',
        ignoreFocusOut: true,
      });
      if (!code) return;
      result = await api.verifyTotpLogin(getServerUrl(), result.user.id, code);
    }

    if (!result.apiKey) {
      throw new Error('APIキーの取得に失敗しました。');
    }

    await setApiKey(context, result.apiKey);
    await setActor(context, result.user);
    vscode.window.showInformationMessage(`'${result.user.email}' としてログインしました。`);
  } catch (err) {
    reportError('ログインに失敗しました', err);
  }
}

async function commandSetApiKey(context: vscode.ExtensionContext): Promise<void> {
  const value = await vscode.window.showInputBox({
    prompt: "仲介APIサーバーのAPIキーを入力してください",
    password: true,
    ignoreFocusOut: true,
  });
  if (!value) {
    return;
  }
  await setApiKey(context, value);
  vscode.window.showInformationMessage('APIキーを保存しました。');
}

async function commandSelectSite(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const apiKey = await requireApiKey(context);
    const sites = await api.listSites(getServerUrl(), apiKey);
    if (sites.length === 0) {
      vscode.window.showWarningMessage('登録済みのサイトがありません。先にWeb管理画面またはAPIでサイトを登録してください。');
      return;
    }

    const picked = await vscode.window.showQuickPick(
      sites.map((s) => ({ label: s.name, description: s.siteKey, siteKey: s.siteKey })),
      { placeHolder: '投稿先サイトを選択' }
    );
    if (!picked) return;

    const article = parseArticle(editor.document.getText());
    article.data.site = picked.siteKey;
    await replaceDocumentText(editor, stringifyArticle(article));
    vscode.window.showInformationMessage(`サイトを '${picked.siteKey}' に設定しました。`);
  } catch (err) {
    reportError('サイトの選択に失敗しました', err);
  }
}

/**
 * 指定サイトへ現在のエディタの記事を投稿する共通処理。
 * front matterのtitleチェック・画像収集・publishPost呼び出し・front matter書き戻し・完了通知を行う。
 */
async function publishToSite(
  context: vscode.ExtensionContext,
  editor: vscode.TextEditor,
  siteKey: string,
  forceStatus?: string
): Promise<void> {
  const apiKey = await requireApiKey(context);
  const serverUrl = getServerUrl();
  const article = parseArticle(editor.document.getText());

  if (!article.data.title) {
    vscode.window.showErrorMessage("front matterに 'title' がありません。");
    return;
  }

  const baseDir = path.dirname(editor.document.uri.fsPath);
  const allBodyImages = extractLocalImageReferences(article.content, baseDir);
  const bodyImages = allBodyImages.filter((img) => fs.existsSync(img.absolutePath));
  const featuredImage = resolveFeaturedImageReference(article.data, baseDir);

  const missingReferences = allBodyImages
    .filter((img) => !fs.existsSync(img.absolutePath))
    .map((img) => img.reference);
  const featuredImageMissing = featuredImage != null && !fs.existsSync(featuredImage.absolutePath);
  if (featuredImageMissing) {
    missingReferences.push(featuredImage.reference);
  }
  if (missingReferences.length > 0) {
    vscode.window.showWarningMessage(
      `以下の画像ファイルが見つからないため、投稿に含まれません: ${missingReferences.join(', ')}`
    );
  }

  const images = [...bodyImages];
  if (featuredImage && !featuredImageMissing) {
    if (!images.find((img) => img.reference === featuredImage.reference)) {
      images.push(featuredImage);
    }
  }

  const existingPostId = resolveExistingPostId(article.data, siteKey);
  const actor = await getActor(context);

  const result = await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: 'WordPressへ投稿しています…' },
    () =>
      api.publishPost(
        serverUrl,
        apiKey,
        {
          site: siteKey,
          title: article.data.title as string,
          slug: article.data.slug,
          status: forceStatus ?? article.data.status ?? 'draft',
          categories: article.data.categories ?? [],
          tags: article.data.tags ?? [],
          wpPostId: existingPostId,
          markdown: article.content,
          images,
          featuredImageFilename: featuredImage?.reference,
        },
        actor
      )
  );

  article.data.site = siteKey;
  article.data.wp_post_id = result.wpPostId;
  article.data.wp_post_url = result.wpPostUrl;
  article.data.status = result.status;
  article.data.wp_post_ids = { ...(article.data.wp_post_ids ?? {}), [siteKey]: result.wpPostId };
  await replaceDocumentText(editor, stringifyArticle(article));

  const selection = await vscode.window.showInformationMessage(
    `投稿しました(status: ${result.status})`,
    '開く'
  );
  if (selection === '開く') {
    vscode.env.openExternal(vscode.Uri.parse(result.wpPostUrl));
  }
}

interface EnvironmentOption extends vscode.QuickPickItem {
  siteKey: string;
  /** ローカル/テストは動作確認用途のため即公開(publish)する。本番はfront matterのstatus(既定draft)を尊重する。 */
  forceStatus?: string;
}

/**
 * プロジェクトに紐づくローカル/テスト/本番の各サイトをQuickPickの選択肢へ変換する。
 * 未設定の環境(サイト未紐づけ)は選択肢から除外する。
 */
function buildEnvironmentOptions(project: api.ProjectDetail): EnvironmentOption[] {
  const options: EnvironmentOption[] = [];
  if (project.localSite) {
    options.push({
      label: 'ローカル',
      description: project.localSite.name,
      siteKey: project.localSite.siteKey,
      forceStatus: 'publish',
    });
  }
  if (project.testSite) {
    options.push({
      label: 'テスト',
      description: project.testSite.name,
      siteKey: project.testSite.siteKey,
      forceStatus: 'publish',
    });
  }
  if (project.productionSite) {
    options.push({ label: '本番', description: project.productionSite.name, siteKey: project.productionSite.siteKey });
  }
  return options;
}

async function commandPublish(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);
    if (!projectId) {
      vscode.window.showErrorMessage(
        'プロジェクトが未選択です。front matterのproject_id、または「Let\'s Blog: Select Project」で設定してください。'
      );
      return;
    }

    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    const project = await api.getProject(getServerUrl(), apiKey, actor, projectId);

    const options = buildEnvironmentOptions(project);
    if (options.length === 0) {
      vscode.window.showErrorMessage(`プロジェクト '${project.name}' には投稿先サイトが紐づいていません。`);
      return;
    }

    const picked = await vscode.window.showQuickPick(options, { placeHolder: '投稿先の環境を選択' });
    if (!picked) return;

    await publishToSite(context, editor, picked.siteKey, picked.forceStatus);
  } catch (err) {
    reportError('投稿に失敗しました', err);
  }
}

/**
 * 現在の記事を、front matterのwp_post_idsに記録されているサイトから選んで削除する
 * (WordPressの場合、既定でゴミ箱へ移動する。完全削除は行わない)。
 */
async function commandDeletePost(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());
    const wpPostIds = article.data.wp_post_ids ?? {};
    const siteKeys = Object.keys(wpPostIds);

    if (siteKeys.length === 0) {
      vscode.window.showErrorMessage('この記事はまだどのサイトにも投稿されていません。');
      return;
    }

    let siteKey: string;
    if (siteKeys.length === 1) {
      siteKey = siteKeys[0];
    } else {
      const picked = await vscode.window.showQuickPick(
        siteKeys.map((key) => ({ label: key, description: wpPostIds[key] })),
        { placeHolder: '削除対象のサイトを選択' }
      );
      if (!picked) return;
      siteKey = picked.label;
    }

    const wpPostId = wpPostIds[siteKey];
    const confirmation = await vscode.window.showWarningMessage(
      `サイト '${siteKey}' の投稿(ID: ${wpPostId})を削除します(WordPressの場合はゴミ箱へ移動します)。よろしいですか?`,
      { modal: true },
      '削除する'
    );
    if (confirmation !== '削除する') return;

    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '投稿を削除しています…' },
      () => api.deletePost(getServerUrl(), apiKey, actor, siteKey, wpPostId)
    );

    const remainingWpPostIds = { ...wpPostIds };
    delete remainingWpPostIds[siteKey];
    article.data.wp_post_ids = remainingWpPostIds;
    if (article.data.site === siteKey) {
      article.data.wp_post_id = null;
      article.data.wp_post_url = null;
    }
    await replaceDocumentText(editor, stringifyArticle(article));

    vscode.window.showInformationMessage(`サイト '${siteKey}' の投稿を削除しました。`);
  } catch (err) {
    reportError('投稿の削除に失敗しました', err);
  }
}

async function commandAskAi(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  const mode = await vscode.window.showQuickPick(
    [
      { label: '下書き生成', value: 'draft' as const },
      { label: '校正', value: 'proofread' as const },
      { label: '要約', value: 'summarize' as const },
    ],
    { placeHolder: '実行する内容を選択' }
  );
  if (!mode) return;

  try {
    const apiKey = await requireApiKey(context);
    const article = parseArticle(editor.document.getText());
    const selectedText = editor.document.getText(editor.selection);
    const text = selectedText.trim().length > 0 ? selectedText : article.content;

    const result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'AIに問い合わせています…' },
      () => api.askAi(getServerUrl(), apiKey, mode.value, text)
    );

    const content = result.result + buildSourcesSection(result.sources, result.searchNote);
    const doc = await vscode.workspace.openTextDocument({ content, language: 'markdown' });
    await vscode.window.showTextDocument(doc, { preview: false, viewColumn: vscode.ViewColumn.Beside });
  } catch (err) {
    reportError('AI呼び出しに失敗しました', err);
  }
}

async function commandSuggestTags(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const apiKey = await requireApiKey(context);
    const article = parseArticle(editor.document.getText());

    const suggestion = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'タグ/カテゴリを提案中…' },
      () => api.suggestTags(getServerUrl(), apiKey, article.content)
    );

    const items = [
      ...suggestion.categories.map((c) => ({ label: c, description: 'カテゴリ', itemType: 'category' as const })),
      ...suggestion.tags.map((t) => ({ label: t, description: 'タグ', itemType: 'tag' as const })),
    ];
    if (items.length === 0) {
      vscode.window.showInformationMessage('提案はありませんでした。');
      return;
    }

    const picked = await vscode.window.showQuickPick(items, {
      canPickMany: true,
      placeHolder: '記事に追加する項目を選択',
    });
    if (!picked || picked.length === 0) return;

    const categories = new Set(article.data.categories ?? []);
    const tags = new Set(article.data.tags ?? []);
    for (const item of picked) {
      if (item.itemType === 'category') categories.add(item.label);
      else tags.add(item.label);
    }
    article.data.categories = [...categories];
    article.data.tags = [...tags];

    await replaceDocumentText(editor, stringifyArticle(article));
    vscode.window.showInformationMessage('front matterに反映しました。');
  } catch (err) {
    reportError('タグ提案に失敗しました', err);
  }
}

async function commandGenerateImage(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);
    if (!projectId) {
      vscode.window.showErrorMessage(
        'プロジェクトが未選択です。front matterのproject_id、または「Let\'s Blog: Select Project」で設定してください。'
      );
      return;
    }

    const baseDir = path.dirname(editor.document.uri.fsPath);
    ImageGenPanel.createOrShow(context, editor, baseDir, projectId);
  } catch (err) {
    reportError('画像生成パネルの起動に失敗しました', err);
  }
}

async function commandGenerateSection(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());
    const articleTitle = typeof article.data.title === 'string' ? article.data.title : undefined;
    const sectionContext = resolveSectionContext(editor.document.getText(), editor.selection.active.line);
    SectionGenPanel.createOrShow(context, editor, articleTitle, sectionContext);
  } catch (err) {
    reportError('セクション生成パネルの起動に失敗しました', err);
  }
}

async function commandPlanArticle(context: vscode.ExtensionContext): Promise<void> {
  const actor = await getActor(context);
  if (!actor) {
    vscode.window.showErrorMessage('先に「Let\'s Blog: Login」でログインしてください。');
    return;
  }
  const projectId = getProjectId(context);
  if (!projectId) {
    vscode.window.showErrorMessage('先に「Let\'s Blog: Select Project」でプロジェクトを選択してください。');
    return;
  }
  PlanPanel.createOrShow(context);
}

async function commandSelectProject(context: vscode.ExtensionContext): Promise<void> {
  try {
    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    const projects = await api.listProjects(getServerUrl(), apiKey, actor);

    const validProjects = projects.filter((p) => p.githubRepository);
    if (validProjects.length === 0) {
      vscode.window.showWarningMessage(
        'GitHub連携済みのプロジェクトがありません。先に管理画面でプロジェクトのGitHubリポジトリを設定してください。'
      );
      return;
    }

    const currentProjectId = getProjectId(context);
    const picked = await vscode.window.showQuickPick(
      validProjects.map((p) => ({
        label: p.name,
        description: p.githubRepository + (currentProjectId === p.id ? ' (現在選択中)' : ''),
        projectId: p.id,
      })),
      { placeHolder: 'プロジェクトを選択' }
    );
    if (!picked) return;

    await setProjectId(context, picked.projectId);
    vscode.window.showInformationMessage(`プロジェクトを '${picked.label}' に設定しました。`);
  } catch (err) {
    reportError('プロジェクト選択に失敗しました', err);
  }
}

/**
 * Markdown本文中のローカル画像参照をbase64データURIへ置換する。プレビューはWebviewの外(APIサーバー)で
 * HTML化するため、投稿先を持たないローカル画像をそのまま渡すと壊れたリンクになってしまうのを防ぐ。
 */
function inlineLocalImages(content: string, baseDir: string): string {
  let rewritten = content;
  for (const image of extractLocalImageReferences(content, baseDir)) {
    if (!fs.existsSync(image.absolutePath)) continue;
    const mimeType = guessImageMimeType(image.absolutePath);
    if (!mimeType) continue;
    const dataUri = `data:${mimeType};base64,${fs.readFileSync(image.absolutePath).toString('base64')}`;
    rewritten = rewritten.split(image.reference).join(dataUri);
  }
  return rewritten;
}

async function commandPreviewArticle(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);
    if (!projectId) {
      vscode.window.showErrorMessage(
        'プロジェクトが未選択です。front matterのproject_id、または「Let\'s Blog: Select Project」で設定してください。'
      );
      return;
    }

    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    const serverUrl = getServerUrl();
    const baseDir = path.dirname(editor.document.uri.fsPath);
    const markdown = inlineLocalImages(article.content, baseDir);

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'プレビューを生成しています…' },
      async () => {
        const html = await api.renderPreviewHtml(serverUrl, apiKey, actor, projectId, markdown);

        let css = '';
        let warning: string | undefined;
        try {
          const themeCss = await api.getMasterThemeCss(serverUrl, apiKey, actor, projectId);
          if (themeCss.available) {
            css = themeCss.css;
          } else {
            warning = `マスター環境サイトのCSSを取得できませんでした: ${themeCss.reason ?? '不明なエラー'}`;
          }
        } catch (cssError) {
          warning = `マスター環境サイトのCSS取得に失敗しました: ${String(cssError instanceof Error ? cssError.message : cssError)}`;
        }

        PreviewPanel.createOrShow(html, css, warning);
      }
    );
  } catch (err) {
    reportError('プレビューの生成に失敗しました', err);
  }
}
