import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
  getServerUrl,
  requireApiKey,
  setApiKey,
  getApiKey,
  getActor,
  setActor,
  getProjectId,
  setProjectId,
  requireProjectId,
  getConfiguredAiProvider,
  setConfiguredAiProvider,
} from './config';
import {
  parseArticle,
  stringifyArticle,
  extractLocalImageReferences,
  resolveFeaturedImageReference,
  guessImageMimeType,
  validateScheduledPublication,
  buildArticleFrontMatter,
  suggestSlugFromTitle,
} from './frontMatter';
import { createArticleScaffold, openArticle, requireWorkspaceRoot } from './articleScaffold';
import * as api from './apiClient';
import { PlanPanel } from './planPanel';
import { ArticleCreationPanel } from './articleCreationPanel';
import { PreviewMessage, PreviewPanel, SiteOption } from './previewPanel';
import { ImageGenPanel } from './imageGenPanel';
import { ImageGalleryPanel } from './imageGalleryPanel';
import { DiagramEditorPanel, DIAGRAM_REFERENCE_PATTERN } from './diagramEditorPanel';
import { DiagramGalleryPanel } from './diagramGalleryPanel';
import { SectionGenPanel } from './sectionGenPanel';
import { AskAiPanel } from './askAiPanel';
import { resolveSectionContext } from './headingContext';
import { buildSourcesSection } from './markdownSources';
import { logger } from './logger';
import { messageOf, reportError } from './errorHandler';
import { buildSmartCardTag, buildStandardLink, parseHttpUrl } from './urlPaste';
import { ProofreadController } from './proofreadDiagnostics';
import { FrontMatterCompletionProvider } from './frontMatterCompletionProvider';
import { BodyCustomTagCompletionProvider } from './bodyCustomTagCompletionProvider';

/**
 * 拡張の有効化。ロガーの初期化と全コマンドの登録を行う。
 * VSCodeが拡張を読み込んだ際に一度だけ呼ばれる。
 */
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

  // issue #523: front matter検証(publish_scheduled_at/status/categories)は常時、本文のAI校正は
  // letsBlog.proofreadEnabled(既定false)でオプトインした場合のみ、編集の都度デバウンスして実行する。
  const proofreadController = new ProofreadController(context);
  context.subscriptions.push(
    proofreadController,
    vscode.languages.registerCodeActionsProvider({ language: 'markdown' }, proofreadController, {
      providedCodeActionKinds: [vscode.CodeActionKind.QuickFix],
    }),
    vscode.workspace.onDidChangeTextDocument((event) => proofreadController.scheduleCheck(event.document)),
    vscode.workspace.onDidOpenTextDocument((document) => proofreadController.scheduleCheck(document)),
    vscode.workspace.onDidCloseTextDocument((document) => proofreadController.clearDocument(document))
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('letsBlog.createArticle', () => commandCreateArticle(context)),
    vscode.commands.registerCommand('letsBlog.createArticleWithoutAi', () => commandCreateArticleWithoutAi(context)),
    vscode.commands.registerCommand('letsBlog.schedulePublication', () => commandSchedulePublication()),
    vscode.commands.registerCommand('letsBlog.login', () => commandLogin(context)),
    vscode.commands.registerCommand('letsBlog.setApiKey', () => commandSetApiKey(context)),
    vscode.commands.registerCommand('letsBlog.selectSite', () => commandSelectSite(context)),
    vscode.commands.registerCommand('letsBlog.publish', () => commandPublish(context)),
    vscode.commands.registerCommand('letsBlog.deletePost', () => commandDeletePost(context)),
    vscode.commands.registerCommand('letsBlog.askAi', () => commandAskAi(context)),
    vscode.commands.registerCommand('letsBlog.suggestTags', () => commandSuggestTags(context)),
    vscode.commands.registerCommand('letsBlog.switchAiProvider', () => commandSwitchAiProvider()),
    vscode.commands.registerCommand('letsBlog.generateImage', () => commandGenerateImage(context)),
    vscode.commands.registerCommand('letsBlog.imageGallery', () => commandImageGallery(context)),
    vscode.commands.registerCommand('letsBlog.addNewDiagram', () => commandAddNewDiagram(context)),
    vscode.commands.registerCommand('letsBlog.editDiagram', () => commandEditDiagram(context)),
    vscode.commands.registerCommand('letsBlog.diagramGallery', () => commandDiagramGallery(context)),
    vscode.commands.registerCommand('letsBlog.generateSection', () => commandGenerateSection(context)),
    vscode.commands.registerCommand('letsBlog.askAiSearch', () => commandAskAiSearch(context)),
    vscode.commands.registerCommand('letsBlog.selectProject', () => commandSelectProject(context)),
    vscode.commands.registerCommand('letsBlog.planArticle', () => commandPlanArticle(context)),
    vscode.commands.registerCommand('letsBlog.previewArticle', () => commandPreviewArticle(context)),
    vscode.commands.registerCommand('letsBlog.previewDevTools', () => PreviewPanel.openDevTools()),
    vscode.commands.registerCommand('letsBlog.pasteSmartCard', () => commandPasteSmartCard(context)),
    vscode.commands.registerCommand('letsBlog.pasteAsLink', () => commandPasteAsLink(context)),
    vscode.commands.registerCommand('letsBlog.proofreadNow', () => commandProofreadNow(proofreadController)),
    vscode.commands.registerCommand('letsBlog.fixInvalidStatus', (uri: vscode.Uri) =>
      commandFixInvalidStatus(context, uri)
    ),
    vscode.commands.registerCommand('letsBlog.removeInvalidCategory', (uri: vscode.Uri, category: string) =>
      commandRemoveInvalidCategory(uri, category)
    )
  );

  context.subscriptions.push(registerDiagramCursorContext());

  // issue #521: frontmatterのstatus/categories/tagsへコード補完を提供する。
  context.subscriptions.push(
    vscode.languages.registerCompletionItemProvider(
      { language: 'markdown' },
      new FrontMatterCompletionProvider(context),
      ' ',
      '-',
      ':'
    )
  );

  // issue #522: 本文中のカスタムタグ(`[tagname]〜[/tagname]`)へコード補完を提供する。
  context.subscriptions.push(
    vscode.languages.registerCompletionItemProvider(
      { language: 'markdown' },
      new BodyCustomTagCompletionProvider(context),
      '['
    )
  );
}

/**
 * カーソル行が挿入済みダイアグラム(assets/diagram-{id}-{timestamp}.svg)を参照しているかを
 * letsBlog.cursorOnDiagram コンテキストキーへ反映する。「Edit Diagram」メニュー項目の
 * 表示条件(package.jsonのwhen句)に使う。高頻度に発火するため、値が変化した場合のみ
 * setContextを呼ぶ。
 */
function registerDiagramCursorContext(): vscode.Disposable {
  let lastValue: boolean | undefined;

  const update = (editor: vscode.TextEditor | undefined): void => {
    let matched = false;
    if (editor && editor.document.languageId === 'markdown') {
      const line = editor.document.lineAt(editor.selection.active.line).text;
      matched = DIAGRAM_REFERENCE_PATTERN.test(line);
    }
    if (matched !== lastValue) {
      lastValue = matched;
      void vscode.commands.executeCommand('setContext', 'letsBlog.cursorOnDiagram', matched);
    }
  };

  update(vscode.window.activeTextEditor);
  const selectionListener = vscode.window.onDidChangeTextEditorSelection((e) => update(e.textEditor));
  const activeEditorListener = vscode.window.onDidChangeActiveTextEditor((editor) => update(editor));
  return vscode.Disposable.from(selectionListener, activeEditorListener);
}

/** 拡張の無効化。破棄処理はcontext.subscriptionsに登録済みのため、ここでは何もしない。 */
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

async function replaceDocumentText(editor: vscode.TextEditor, newText: string): Promise<void> {
  const fullRange = new vscode.Range(
    editor.document.positionAt(0),
    editor.document.positionAt(editor.document.getText().length)
  );
  await editor.edit((builder) => builder.replace(fullRange, newText));
  await editor.document.save();
}

/** カーソル位置(または選択範囲)をtextで置き換える。通常の貼り付けと同様の挙動。 */
async function insertTextAtSelection(editor: vscode.TextEditor, text: string): Promise<void> {
  await editor.edit((builder) => {
    if (editor.selection.isEmpty) {
      builder.insert(editor.selection.active, text);
    } else {
      builder.replace(editor.selection, text);
    }
  });
}

/**
 * [blogcard]/[amazon]組み込みタグのレンダリング時に再スクレイピングが発生しないよう、
 * URLペースト時点でcontent-cache APIを先行呼び出ししてキャッシュを温めておく(Issue #339)。
 * 挿入するタグ自体はこの結果を使わないため、失敗してもタグの挿入をやり直す必要はない
 * (プレビュー/投稿時に改めて取得される)。
 */
async function warmContentCache(context: vscode.ExtensionContext, url: string): Promise<void> {
  try {
    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    await api.resolveContentCache(getServerUrl(), apiKey, actor, url);
  } catch (err) {
    logger.debug(`貼り付け時のキャッシュ先行取得に失敗しました(プレビュー/投稿時に再取得されます): ${messageOf(err)}`);
  }
}

/**
 * Ctrl+Shift+V: クリップボードがURLの場合、そのURLに応じて[blogcard]/[amazon]組み込みタグを
 * 挿入する。タグ自体はURLの種別だけで即座に組み立てられるため、実際の情報取得(タイトル・価格等)は
 * 待たずにバックグラウンドでキャッシュを温めるだけに留める。URL以外の通常の貼り付けは
 * 既定の動作にフォールバックする。
 */
async function commandPasteSmartCard(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  const clipboardText = await vscode.env.clipboard.readText();
  const url = parseHttpUrl(clipboardText);
  if (!url) {
    await vscode.commands.executeCommand('editor.action.clipboardPasteAction');
    return;
  }

  await insertTextAtSelection(editor, buildSmartCardTag(url));
  void warmContentCache(context, url.toString());
}

/**
 * Ctrl+V: クリップボードがURLの場合、通常のMarkdownリンク`[Title | サイト名](URL)`として挿入する。
 * タイトル・サイト名を埋め込む必要があるため、content-cache APIの応答を待ってから挿入する
 * (これ自体がレンダリング時ではなく貼り付け時点での情報取得になる)。取得に失敗した場合は
 * URLそのものを貼り付ける。URL以外の通常の貼り付けは既定の動作にフォールバックする。
 */
async function commandPasteAsLink(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  const clipboardText = await vscode.env.clipboard.readText();
  const url = parseHttpUrl(clipboardText);
  if (!url) {
    await vscode.commands.executeCommand('editor.action.clipboardPasteAction');
    return;
  }

  try {
    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    const result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'URLの情報を取得しています…' },
      () => api.resolveContentCache(getServerUrl(), apiKey, actor, url.toString())
    );
    const title = result.type === 'AMAZON' ? result.data.productName : result.data.title;
    const siteName = result.type === 'AMAZON' ? undefined : result.data.siteName;
    await insertTextAtSelection(editor, buildStandardLink(url, title ?? undefined, siteName ?? undefined));
  } catch (err) {
    logger.debug(`URL情報の取得に失敗したため、URLそのものを貼り付けます: ${messageOf(err)}`);
    await insertTextAtSelection(editor, url.toString());
  }
}

/**
 * ロール名(roleName)に対応する表示名を解決する。ログイン成功メッセージを分かりやすくするための
 * 付加情報にすぎないため、取得に失敗してもログイン自体は失敗させず、undefinedを返す(issue #472)。
 */
async function resolveRoleDisplayName(
  serverUrl: string,
  apiKey: string,
  roleName: string
): Promise<string | undefined> {
  try {
    const roles = await api.getRoles(serverUrl, apiKey);
    return roles.find((r) => r.roleName === roleName)?.displayName;
  } catch {
    return undefined;
  }
}

/**
 * メールアドレス/パスワード(必要なら2FAコード)でLet's Blogにログインし、
 * 発行されたAPIキーをSecretStorageに保存する。ログインしたユーザーがそのままActorになる
 * (以前の「Select User」QuickPickによるActor選択は廃止し、ログインに一本化した)。
 */
async function commandLogin(context: vscode.ExtensionContext): Promise<void> {
  const email = await vscode.window.showInputBox({ prompt: 'メールアドレス', ignoreFocusOut: true });
  if (!email) return;

  const serverUrl = getServerUrl();
  if (!(await confirmCredentialTransport(serverUrl))) return;

  let password = await vscode.window.showInputBox({
    prompt: 'パスワード',
    password: true,
    ignoreFocusOut: true,
  });
  if (!password) return;

  let apiKey: string | null = null;
  try {
    let result = await api.login(serverUrl, email, password);
    // 送信済みの資格情報はこれ以降使わないため、保持し続けないよう参照を切る。
    password = '';

    if (result.twoFactorRequired) {
      let code = await vscode.window.showInputBox({
        prompt: '2段階認証コードを入力してください',
        ignoreFocusOut: true,
      });
      if (!code) return;
      result = await api.verifyTotpLogin(serverUrl, result.user.id, code);
      code = '';
    }

    apiKey = result.apiKey;
    if (!apiKey) {
      throw new Error('APIキーの取得に失敗しました。');
    }

    await setApiKey(context, apiKey);
    await setActor(context, result.user);
    // 別ユーザーでログインし直した場合に、前のユーザーの参照結果が残らないようにする。
    api.clearResponseCache();
    const roleLabel = await resolveRoleDisplayName(serverUrl, apiKey, result.user.role);
    vscode.window.showInformationMessage(
      `'${result.user.email}'${roleLabel ? ` (${roleLabel})` : ''} としてログインしました。`
    );
  } catch (err) {
    reportError('ログインに失敗しました', err);
  } finally {
    // 例外時も含め、平文の資格情報をこの関数のスコープに残さない。
    password = '';
    apiKey = null;
  }
}

async function commandSetApiKey(context: vscode.ExtensionContext): Promise<void> {
  let value = await vscode.window.showInputBox({
    prompt: "仲介APIサーバーのAPIキーを入力してください",
    password: true,
    ignoreFocusOut: true,
  });
  if (!value) {
    return;
  }
  try {
    await setApiKey(context, value);
    api.clearResponseCache();
    vscode.window.showInformationMessage('APIキーを保存しました。');
  } finally {
    value = '';
  }
}

/**
 * 資格情報を送信する前に、通信経路が保護されているかを確認する。
 * 平文HTTPではパスワードとAPIキーが傍受されうるため、利用者へ明示的な同意を求める。
 */
async function confirmCredentialTransport(serverUrl: string): Promise<boolean> {
  if (serverUrl.startsWith('https://')) {
    return true;
  }
  const proceed = await vscode.window.showWarningMessage(
    `接続先 '${serverUrl}' はHTTPS ではありません。パスワードとAPIキーが平文で送信されます。続行しますか?`,
    { modal: true },
    '続行する'
  );
  return proceed === '続行する';
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

  // 送信前に形式のみ確認する。過去日時は投稿自体を拒否せず、API側の判定
  // (環境・statusに応じて無視して通常投稿する。issue #520)に委ねる。
  const scheduled = validateScheduledPublication(article.data.publish_scheduled_at, new Date(), {
    requireFuture: false,
  });
  if (scheduled.error) {
    vscode.window.showErrorMessage(scheduled.error);
    return;
  }

  const actor = await getActor(context);
  // 投稿の識別はslugを用いてサーバー側DB(postsテーブル)で管理する。
  let existingPostId: string | undefined;
  if (article.data.slug) {
    const found = await api.lookupExistingPost(serverUrl, apiKey, siteKey, article.data.slug, actor);
    existingPostId = found?.wpPostId;
  }

  const result = await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: 'WordPressへ投稿しています…' },
    (progress) => {
      // 画像同梱の有無で待ち時間が大きく変わるため、何をしているかを明示する。
      progress.report({
        message:
          images.length > 0
            ? `本文と画像${images.length}件を送信しています…`
            : '本文を送信しています…',
      });
      return api.publishPost(
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
          publishScheduledAt: scheduled.value,
        },
        actor
      );
    }
  );

  // site/wp_post_id/wp_post_urlはfront matterへ書き込まない(DB(postsテーブル)側で
  // slugをキーに管理し、次回投稿時はlookupExistingPost経由で参照する)。statusのみ投稿の派生情報として残す。
  article.data.status = result.status;
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
 * 現在の記事が投稿済みのサイトを選んで削除する
 * (WordPressの場合、既定でゴミ箱へ移動する。完全削除は行わない)。
 *
 * 登録済みサイトそれぞれについてDB側の情報をサーバーAPI経由(lookupExistingPost)で照会し、
 * slugをキーに実際に投稿済みのサイトを削除候補とする。
 */
async function commandDeletePost(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());
    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);

    let candidates: { siteKey: string; wpPostId: string }[];
    if (article.data.slug) {
      const sites = await api.listSites(getServerUrl(), apiKey, actor);
      const found = await Promise.all(
        sites.map(async (s) => {
          const result = await api.lookupExistingPost(getServerUrl(), apiKey, s.siteKey, article.data.slug as string, actor);
          return result ? { siteKey: s.siteKey, wpPostId: result.wpPostId } : undefined;
        })
      );
      candidates = found.filter((c): c is { siteKey: string; wpPostId: string } => c != null);
    } else {
      candidates = [];
    }

    if (candidates.length === 0) {
      vscode.window.showErrorMessage('この記事はまだどのサイトにも投稿されていません。');
      return;
    }

    let target: { siteKey: string; wpPostId: string };
    if (candidates.length === 1) {
      target = candidates[0];
    } else {
      const picked = await vscode.window.showQuickPick(
        candidates.map((c) => ({ label: c.siteKey, description: c.wpPostId, candidate: c })),
        { placeHolder: '削除対象のサイトを選択' }
      );
      if (!picked) return;
      target = picked.candidate;
    }

    const confirmation = await vscode.window.showWarningMessage(
      `サイト '${target.siteKey}' の投稿(ID: ${target.wpPostId})を削除します(WordPressの場合はゴミ箱へ移動します)。よろしいですか?`,
      { modal: true },
      '削除する'
    );
    if (confirmation !== '削除する') return;

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '投稿を削除しています…' },
      () => api.deletePost(getServerUrl(), apiKey, actor, target.siteKey, target.wpPostId)
    );

    vscode.window.showInformationMessage(`サイト '${target.siteKey}' の投稿を削除しました。`);
  } catch (err) {
    reportError('投稿の削除に失敗しました', err);
  }
}

/** letsBlog.aiProviderのメニュー選択肢。値''は「サーバー(プロジェクト/システム設定)の既定値を使用」。 */
const AI_PROVIDER_ITEMS: { label: string; value: string }[] = [
  { label: 'サーバー既定値を使用', value: '' },
  { label: 'Ollama', value: 'OLLAMA' },
  { label: 'OpenAI (ChatGPT)', value: 'OPENAI' },
  { label: 'Claude (Anthropic)', value: 'CLAUDE' },
];

/**
 * AIを呼び出すコマンドの実行時に必ずプロバイダーを選ばせるための共通クイックピック(issue #530)。
 * letsBlog.aiProvider設定の現在値をチェックマークで示し、そのままEnterすれば現在値が選び直される。
 * 戻り値undefinedはEscapeによるキャンセル、空文字はサーバー既定値を使う選択。
 */
async function pickAiProvider(placeHolder: string): Promise<string | undefined> {
  const current = getConfiguredAiProvider();
  const items = AI_PROVIDER_ITEMS.map((item) => ({
    label: item.value === current ? `$(check) ${item.label}` : item.label,
    value: item.value,
  }));
  const picked = await vscode.window.showQuickPick(items, { placeHolder });
  return picked?.value;
}

/**
 * 「Let's Blog: AIプロバイダーを切り替える」コマンド。letsBlog.aiProvider設定を書き換え、
 * 以降のAskAi/Suggest Tags/Generate Section/Generate Imageの既定選択に反映される。
 */
async function commandSwitchAiProvider(): Promise<void> {
  const provider = await pickAiProvider('作業中に使うAIプロバイダーを選択');
  if (provider === undefined) return;
  await setConfiguredAiProvider(provider);
  const label = AI_PROVIDER_ITEMS.find((item) => item.value === provider)?.label ?? provider;
  vscode.window.showInformationMessage(`AIプロバイダーを「${label}」に切り替えました。`);
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

  // 必ずAIプロバイダーを選ばせる(issue #530)。同じクイックピックフローの続きとして提示する。
  const provider = await pickAiProvider('使用するAIプロバイダーを選択');
  if (provider === undefined) return;

  try {
    const apiKey = await requireApiKey(context);
    const article = parseArticle(editor.document.getText());
    const selectedText = editor.document.getText(editor.selection);
    const text = selectedText.trim().length > 0 ? selectedText : article.content;

    const result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'AIに問い合わせています…' },
      () => api.askAi(getServerUrl(), apiKey, mode.value, text, undefined, provider)
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

  // 必ずAIプロバイダーを選ばせる(issue #530)。
  const provider = await pickAiProvider('使用するAIプロバイダーを選択');
  if (provider === undefined) return;

  try {
    const apiKey = await requireApiKey(context);
    const article = parseArticle(editor.document.getText());
    // issue #525: プロジェクトのマスター環境サイトに既存のタグを優先して提案させる。
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);

    const [suggestion, existingTags] = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'タグ/カテゴリを提案中…' },
      () => Promise.all([
        api.suggestTags(getServerUrl(), apiKey, article.content, undefined, provider, projectId),
        projectId
          ? api.listExistingTags(getServerUrl(), apiKey, undefined, projectId)
          : Promise.resolve<string[]>([]),
      ])
    );
    const existingTagSet = new Set(existingTags.map((t) => t.toLowerCase()));

    const items = [
      ...suggestion.categories.map((c) => ({ label: c, description: 'カテゴリ', itemType: 'category' as const })),
      ...suggestion.tags.map((t) => ({
        label: t,
        description: existingTagSet.has(t.toLowerCase()) ? 'タグ (既存)' : 'タグ',
        itemType: 'tag' as const,
      })),
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

/**
 * issue #523: front matter検証と本文のAI校正を即時実行する。letsBlog.proofreadEnabledが
 * 無効(既定)でも、このコマンドは常に本文のAI校正まで実行する。
 */
async function commandProofreadNow(proofreadController: ProofreadController): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    await proofreadController.runManual(editor.document);
  } catch (err) {
    reportError('校正チェックに失敗しました', err);
  }
}

/** issue #523: front matterのstatusが不正な値だった際のクイックフィックス。有効な値から選び直す。 */
async function commandFixInvalidStatus(context: vscode.ExtensionContext, uri: vscode.Uri): Promise<void> {
  try {
    const document = await vscode.workspace.openTextDocument(uri);
    const editor = await vscode.window.showTextDocument(document);
    const apiKey = await requireApiKey(context);
    const statuses = await api.getPostStatuses(getServerUrl(), apiKey);

    const picked = await vscode.window.showQuickPick(
      statuses.map((s) => ({ label: s.label, description: s.value, value: s.value })),
      { placeHolder: '有効なステータスを選択' }
    );
    if (!picked) return;

    const article = parseArticle(editor.document.getText());
    article.data.status = picked.value;
    await replaceDocumentText(editor, stringifyArticle(article));
  } catch (err) {
    reportError('ステータスの修正に失敗しました', err);
  }
}

/** issue #523: front matterのcategoriesにサイトへ存在しない項目があった際のクイックフィックス。 */
async function commandRemoveInvalidCategory(uri: vscode.Uri, category: string): Promise<void> {
  try {
    const document = await vscode.workspace.openTextDocument(uri);
    const editor = await vscode.window.showTextDocument(document);
    const article = parseArticle(editor.document.getText());
    article.data.categories = (article.data.categories ?? []).filter((c) => c !== category);
    await replaceDocumentText(editor, stringifyArticle(article));
  } catch (err) {
    reportError('カテゴリの削除に失敗しました', err);
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

/**
 * サーバーに保存済みの生成画像を一覧し、記事へ取り込む。
 * 画像生成パネルで作った画像を後から再利用するための入口。
 */
async function commandImageGallery(context: vscode.ExtensionContext): Promise<void> {
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
    ImageGalleryPanel.createOrShow(context, editor, baseDir, projectId);
  } catch (err) {
    reportError('画像ギャラリーの起動に失敗しました', err);
  }
}

/** front matterのproject_idを解決する。未設定ならエラーを表示してundefinedを返す。 */
function resolveDiagramProjectId(editor: vscode.TextEditor, context: vscode.ExtensionContext): number | undefined {
  const article = parseArticle(editor.document.getText());
  const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);
  if (!projectId) {
    vscode.window.showErrorMessage(
      'プロジェクトが未選択です。front matterのproject_id、または「Let\'s Blog: Select Project」で設定してください。'
    );
    return undefined;
  }
  return projectId;
}

/** カーソル位置に空のdraw.ioエディタを開き、記事に新規ダイアグラムを挿入する(issue #476)。 */
async function commandAddNewDiagram(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const projectId = resolveDiagramProjectId(editor, context);
    if (!projectId) return;

    const baseDir = path.dirname(editor.document.uri.fsPath);
    DiagramEditorPanel.createOrShow(context, editor, baseDir, projectId, { kind: 'create' });
  } catch (err) {
    reportError('ダイアグラムエディタの起動に失敗しました', err);
  }
}

/**
 * カーソル行が参照している既存ダイアグラムをdraw.ioエディタで開く(issue #476)。
 * 右クリックメニューの表示条件(letsBlog.cursorOnDiagram)は registerDiagramCursorContext が管理する。
 */
async function commandEditDiagram(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const line = editor.document.lineAt(editor.selection.active.line).text;
    const match = DIAGRAM_REFERENCE_PATTERN.exec(line);
    if (!match) {
      vscode.window.showErrorMessage('カーソル行にダイアグラム参照が見つかりません。');
      return;
    }

    const projectId = resolveDiagramProjectId(editor, context);
    if (!projectId) return;

    const existingFileName = match[1];
    const diagramId = Number(match[2]);

    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    const detail = await api.getDiagramDetail(getServerUrl(), apiKey, actor, diagramId);

    const baseDir = path.dirname(editor.document.uri.fsPath);
    DiagramEditorPanel.createOrShow(context, editor, baseDir, projectId, {
      kind: 'edit',
      diagramId,
      name: detail.name,
      xml: detail.xml,
      existingFileName,
    });
  } catch (err) {
    reportError('ダイアグラムの読み込みに失敗しました', err);
  }
}

/**
 * サーバーに保存済みのダイアグラムを一覧し、記事へ取り込む。
 * Diagram Editorで作ったダイアグラムを後から再利用するための入口(issue #476)。
 */
async function commandDiagramGallery(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const projectId = resolveDiagramProjectId(editor, context);
    if (!projectId) return;

    const baseDir = path.dirname(editor.document.uri.fsPath);
    DiagramGalleryPanel.createOrShow(context, editor, baseDir, projectId);
  } catch (err) {
    reportError('ダイアグラムギャラリーの起動に失敗しました', err);
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

/**
 * エディタ右クリックメニューの「Ask AI」。Web検索を踏まえた質問応答パネルを開く(issue #526)。
 * 右クリック時点の選択範囲(無ければカーソル位置)がApply時の挿入先になる。
 */
async function commandAskAiSearch(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    AskAiPanel.createOrShow(context, editor);
  } catch (err) {
    reportError('Ask AIパネルの起動に失敗しました', err);
  }
}

/**
 * GitHub Issueを起点にせず、コマンドから直接記事を作成する。
 * Issueが無い記事(単発の告知や覚書など)を書き始めるための入口。
 */
async function commandCreateArticle(context: vscode.ExtensionContext): Promise<void> {
  try {
    await requireApiKey(context);
    ArticleCreationPanel.createOrShow(context);
  } catch (err) {
    reportError('記事作成パネルの起動に失敗しました', err);
  }
}

/**
 * AIチャットを介さず、タイトル・スラッグの直接入力だけで記事を新規作成する。
 * 生成される記事の配置とfront matterはAI駆動のフロー(ArticleCreationPanel/PlanPanel)と
 * 同じ(articleScaffold.ts / buildArticleFrontMatter に集約)。AIを一切使わないため
 * APIキーは不要で、プロジェクトは「Let's Blog: Select Project」で選択済みのものを使う。
 * カテゴリ選択(issue #524)はAPIキー設定済みの場合のみ行い、未設定/取得失敗時は
 * 選択せずに作成を続行する。
 */
async function commandCreateArticleWithoutAi(context: vscode.ExtensionContext): Promise<void> {
  try {
    const workspaceRoot = requireWorkspaceRoot();
    const projectId = requireProjectId(context);

    const title = await vscode.window.showInputBox({
      prompt: 'タイトル',
      ignoreFocusOut: true,
      validateInput: (value) => (value.trim() ? undefined : 'タイトルは必須です。'),
    });
    if (!title) return;

    const slug = await vscode.window.showInputBox({
      prompt: 'スラッグ (articles/<slug>/ のディレクトリ名になります)',
      value: suggestSlugFromTitle(title),
      ignoreFocusOut: true,
      // ディレクトリ名になるため、パス区切りなどが混入しないことを確認する(articleCreation.jsのslug検証と同じ規則)。
      validateInput: (value) => {
        const trimmed = value.trim();
        if (!trimmed) return 'スラッグは必須です。';
        if (!/^[a-z0-9][a-z0-9-]*$/.test(trimmed)) {
          return 'スラッグは半角英数字とハイフンのみで入力してください(先頭は英数字)。';
        }
        return undefined;
      },
    });
    if (!slug) return;

    const categories = await pickCategoriesForNewArticle(context, projectId);

    const result = await createArticleScaffold({
      workspaceRoot,
      slug: slug.trim(),
      frontMatter: buildArticleFrontMatter({ title: title.trim(), slug: slug.trim(), categories }),
      content: '',
    });
    if (!result) return;

    await openArticle(result.articlePath);
    vscode.window.showInformationMessage(`articles/${slug.trim()}/article.md を作成しました。`);
  } catch (err) {
    reportError('記事の作成に失敗しました', err);
  }
}

/**
 * サイトの既存カテゴリ(親カテゴリ名付き)を取得し、複数選択のQuickPickで選ばせる(issue #524)。
 * APIキー/actor未設定、プロジェクト未紐付け、取得失敗など、カテゴリを提示できない場合は
 * 静かに空配列を返し、記事作成そのものは(No AIコマンドの通り)継続させる。
 */
async function pickCategoriesForNewArticle(
  context: vscode.ExtensionContext,
  projectId: number
): Promise<string[]> {
  try {
    const apiKey = await getApiKey(context);
    const actor = await getActor(context);
    if (!apiKey || !actor) return [];

    const categories = await api.listExistingCategoriesWithParents(getServerUrl(), apiKey, actor, projectId);
    if (categories.length === 0) return [];

    const items = categories.map((category) => ({
      label: category.name,
      description: category.parentName ? `親: ${category.parentName}` : undefined,
    }));
    const picked = await vscode.window.showQuickPick(items, {
      canPickMany: true,
      placeHolder: 'カテゴリを選択(複数選択可、未選択のまま確定すると設定しません)',
    });
    return (picked ?? []).map((item) => item.label);
  } catch (err) {
    logger.warn('カテゴリ一覧の取得に失敗しました(カテゴリ選択をスキップします)', { reason: messageOf(err) });
    return [];
  }
}

/**
 * front matterのpublish_scheduled_atを対話的に設定する。
 *
 * 日付と時刻を順に選ばせ、ISO 8601(UTC)へ変換して書き込む。
 * VSCodeの標準UIにはカレンダーピッカーが無いため、QuickPickで日付候補を出しつつ
 * 任意の日付も入力できるようにしている。
 */
async function commandSchedulePublication(): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());

    const current = typeof article.data.publish_scheduled_at === 'string'
      ? article.data.publish_scheduled_at
      : undefined;

    const action = await vscode.window.showQuickPick(
      [
        { label: '公開予定日時を設定する', value: 'set' as const },
        ...(current
          ? [{ label: `公開予定日時を解除する (現在: ${current})`, value: 'clear' as const }]
          : []),
      ],
      { placeHolder: current ? `現在の設定: ${current}` : '公開予定日時は未設定です' }
    );
    if (!action) return;

    if (action.value === 'clear') {
      delete article.data.publish_scheduled_at;
      await replaceDocumentText(editor, stringifyArticle(article));
      vscode.window.showInformationMessage('公開予定日時を解除しました。');
      return;
    }

    const date = await pickScheduledDate();
    if (!date) return;
    const time = await pickScheduledTime();
    if (!time) return;

    // 入力はローカルタイムゾーンとして解釈し、front matterにはUTCのISO 8601で保存する。
    const localDateTime = new Date(`${date}T${time}:00`);
    if (Number.isNaN(localDateTime.getTime())) {
      vscode.window.showErrorMessage(`日時として解釈できません: ${date} ${time}`);
      return;
    }
    const isoValue = localDateTime.toISOString();

    const validation = validateScheduledPublication(isoValue);
    if (validation.error) {
      vscode.window.showErrorMessage(validation.error);
      return;
    }

    article.data.publish_scheduled_at = isoValue;
    await replaceDocumentText(editor, stringifyArticle(article));
    vscode.window.showInformationMessage(
      `公開予定日時を ${localDateTime.toLocaleString()} (${isoValue}) に設定しました。` +
        ' 本番サイトへ投稿したときに有効になります。'
    );
  } catch (err) {
    reportError('公開予定日時の設定に失敗しました', err);
  }
}

/** 日付を選ばせる。今日から2週間分の候補に加え、任意の日付も入力できる。 */
async function pickScheduledDate(): Promise<string | undefined> {
  const today = new Date();
  const candidates: vscode.QuickPickItem[] = [];
  for (let offset = 0; offset < 14; offset++) {
    const day = new Date(today.getFullYear(), today.getMonth(), today.getDate() + offset);
    candidates.push({
      label: formatLocalDate(day),
      description: offset === 0 ? '今日' : offset === 1 ? '明日' : day.toLocaleDateString(undefined, { weekday: 'long' }),
    });
  }
  candidates.push({ label: 'その他の日付を入力…', description: 'YYYY-MM-DD' });

  const picked = await vscode.window.showQuickPick(candidates, { placeHolder: '公開する日付を選択' });
  if (!picked) return undefined;
  if (!picked.label.startsWith('その他')) {
    return picked.label;
  }

  return vscode.window.showInputBox({
    prompt: '公開する日付 (YYYY-MM-DD)',
    validateInput: (value) =>
      /^\d{4}-\d{2}-\d{2}$/.test(value) ? undefined : 'YYYY-MM-DD 形式で入力してください。',
    ignoreFocusOut: true,
  });
}

/** 時刻を選ばせる。よく使う時刻の候補に加え、任意の時刻も入力できる。 */
async function pickScheduledTime(): Promise<string | undefined> {
  const candidates: vscode.QuickPickItem[] = [
    { label: '09:00', description: '朝' },
    { label: '12:00', description: '昼' },
    { label: '18:00', description: '夕方' },
    { label: '21:00', description: '夜' },
    { label: 'その他の時刻を入力…', description: 'HH:MM' },
  ];
  const picked = await vscode.window.showQuickPick(candidates, { placeHolder: '公開する時刻を選択(ローカル時間)' });
  if (!picked) return undefined;
  if (!picked.label.startsWith('その他')) {
    return picked.label;
  }

  return vscode.window.showInputBox({
    prompt: '公開する時刻 (HH:MM、ローカル時間)',
    validateInput: (value) =>
      /^([01]\d|2[0-3]):[0-5]\d$/.test(value) ? undefined : 'HH:MM 形式で入力してください。',
    ignoreFocusOut: true,
  });
}

/** Dateをローカルタイムゾーンの YYYY-MM-DD へ整形する(toISOStringはUTCになるため使わない)。 */
function formatLocalDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
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
 * ローカル画像ファイルをbase64データURIへ変換する。ファイルが存在しない、または
 * 拡張子からMIMEタイプを判定できない場合はundefinedを返す。
 */
function toDataUri(absolutePath: string): string | undefined {
  if (!fs.existsSync(absolutePath)) return undefined;
  const mimeType = guessImageMimeType(absolutePath);
  if (!mimeType) return undefined;
  return `data:${mimeType};base64,${fs.readFileSync(absolutePath).toString('base64')}`;
}

/**
 * Markdown本文中のローカル画像参照をbase64データURIへ置換する。プレビューはWebviewの外(APIサーバー)で
 * HTML化するため、投稿先を持たないローカル画像をそのまま渡すと壊れたリンクになってしまうのを防ぐ。
 */
function inlineLocalImages(content: string, baseDir: string): string {
  let rewritten = content;
  for (const image of extractLocalImageReferences(content, baseDir)) {
    const dataUri = toDataUri(image.absolutePath);
    if (!dataUri) continue;
    rewritten = rewritten.split(image.reference).join(dataUri);
  }
  return rewritten;
}

/**
 * プレビューに使うCSSの取得元サイトを選ばせる。
 * プロジェクトに紐づくサイトが1つだけなら確認を挟まずそれを使い、
 * 複数ある場合のみ選択肢を出す(常にダイアログを出すと毎回の操作が増えるため)。
 */
interface PreviewSiteChoice extends vscode.QuickPickItem {
  siteId?: number;
  siteName: string;
}

function buildPreviewSiteChoices(project: api.ProjectDetail): PreviewSiteChoice[] {
  const choices: PreviewSiteChoice[] = [];
  const environments: [string, api.ProjectSite | null][] = [
    ['ローカル', project.localSite],
    ['テスト', project.testSite],
    ['本番', project.productionSite],
  ];
  for (const [label, site] of environments) {
    if (site) {
      choices.push({ label, description: site.name, siteId: site.id, siteName: site.name });
    }
  }
  return choices;
}

/** 複数の警告文を改行区切りでまとめる。 */
function appendWarning(base: string | undefined, next: string): string {
  return base ? `${base}\n${next}` : next;
}

/** サイト未紐付け環境を表す選択肢。プロジェクトに紐づくサイトが1つも無くてもプレビュー自体は可能(CSSなしで表示する)。 */
const NO_SITE_CHOICE: PreviewSiteChoice = { label: 'サイトなし', siteName: 'サイト未紐付け' };

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

    const project = await api.getProject(serverUrl, apiKey, actor, projectId);
    const choices = buildPreviewSiteChoices(project);
    // パネル内の環境切り替えセレクトに渡す選択肢。ローカル/テスト/本番の見た目を
    // 記事ごとに開き直さず切り替えて比較できるようにする(要件: 環境間のCSS差分確認)。
    const availableSites: SiteOption[] = choices.map((c) => ({
      siteId: c.siteId ?? null,
      label: c.label,
      siteName: c.siteName,
    }));

    let initialSite: PreviewSiteChoice | undefined;
    if (choices.length === 0) {
      initialSite = NO_SITE_CHOICE;
    } else if (choices.length === 1) {
      initialSite = choices[0];
    } else {
      initialSite = await vscode.window.showQuickPick(choices, {
        placeHolder: 'プレビューに使うサイトのCSSを選択',
      });
      if (!initialSite) return;
    }

    const baseDir = path.dirname(editor.document.uri.fsPath);
    const markdown = inlineLocalImages(article.content, baseDir);

    const featuredImage = resolveFeaturedImageReference(article.data, baseDir);
    const featuredImageDataUri = featuredImage ? toDataUri(featuredImage.absolutePath) : undefined;
    const baseWarning = featuredImage && !featuredImageDataUri
      ? `アイキャッチ画像が見つかりません: ${featuredImage.reference}`
      : undefined;
    const title = (article.data.title as string | undefined) ?? '';

    /** 指定サイトのCSS・テーマ構造を取得し、プレビューパネルへ描画する。環境切り替え時にも同じ経路を通す。 */
    const renderForSite = async (
      targetSite: PreviewSiteChoice,
      progress: vscode.Progress<{ message?: string }>
    ): Promise<void> => {
      progress.report({ message: 'Markdownを変換しています…' });
      const html = await api.renderPreviewHtml(serverUrl, apiKey, actor, projectId, markdown);

      progress.report({ message: `${targetSite.siteName} のCSSを取得しています…` });
      let css = '';
      let warning = baseWarning;
      if (targetSite.siteId == null) {
        warning = appendWarning(warning, 'プロジェクトにサイトが紐づいていないため、CSSなしで表示しています。');
      } else {
        try {
          const themeCss = await api.getThemeCss(serverUrl, apiKey, actor, projectId, targetSite.siteId);
          if (themeCss.available) {
            css = themeCss.css;
          } else {
            warning = appendWarning(
              warning,
              `${targetSite.siteName} のCSSを取得できませんでした: ${themeCss.reason ?? '不明なエラー'}`
            );
          }
        } catch (cssError) {
          warning = appendWarning(warning, `${targetSite.siteName} のCSS取得に失敗しました: ${messageOf(cssError)}`);
        }
      }

      // サイト内の既存記事ページを骨格に、実テーマのDOM構造(タイトル/カテゴリ/日付/アイキャッチ等)を
      // 保ったまま表示できるか試す。取得できた場合はアイキャッチも骨格側へ差し替え済みのため、
      // PreviewPanel側の簡易アイキャッチ表示は使わない(二重表示を避ける)。
      // 参照記事が無い等で再現できない場合は、従来のプレーンな表示へフォールバックする。
      let bodyHtml = html;
      let usingSkeleton = false;
      let previewPostId: string | undefined;
      if (targetSite.siteId != null) {
        progress.report({ message: `${targetSite.siteName} の実際のテーマ構造を再現しています…` });
        try {
          const existingPreviewPostId = PreviewPanel.currentPanel?.getPreviewPostId(targetSite.siteId);
          const skeleton = await api.renderPreviewSkeleton(
            serverUrl,
            apiKey,
            actor,
            projectId,
            targetSite.siteId,
            title,
            html,
            featuredImageDataUri,
            existingPreviewPostId,
            article.data.slug,
            article.data.categories,
            article.data.tags
          );
          if (skeleton.available && skeleton.html) {
            bodyHtml = skeleton.html;
            usingSkeleton = true;
            // available=trueでも、アイキャッチアップロード失敗等の非致命的な警告が
            // 付随している場合がある(ローカル/テスト環境の非公開投稿経路)。
            if (skeleton.warning) {
              warning = appendWarning(warning, `${targetSite.siteName}: ${skeleton.warning}`);
            }
          } else {
            // デバッグログのみだと、利用者は「なぜヘッダー/サイドバー等の実テーマ構造が
            // 表示されていないか」に気付けない(環境によって参照記事の有無が異なり、
            // 骨格が使える環境と使えない環境が混在しうるため)。プレビューへも明示する。
            const reason = skeleton.reason ?? '不明な理由';
            logger.debug(`テーマ構造の再現をスキップしました: ${reason}`);
            warning = appendWarning(
              warning,
              `${targetSite.siteName} の実際のテーマ構造(ヘッダー/サイドバー等)は再現できませんでした: ${reason}`
            );
          }
          // トップページのクロールでは拾えない、投稿ページ限定で読み込まれるCSS(is_single()等)を
          // 補うため、骨格取得時に実際のナビゲーション先で収集されたCSSがあればマージする。
          // 本文の差し替え位置を特定できずavailableがfalseの場合でも、ナビゲーション自体には
          // 成功していればcssは含まれ得るため、availableに関わらずマージする。
          if (skeleton.css) {
            css = css ? `${css}\n${skeleton.css}` : skeleton.css;
          }
          // ローカル/テスト環境では非公開投稿として実表示している場合があり、その投稿IDが
          // 返ってくる。次回同じ環境でのプレビューで使い回す/パネルを閉じた際に削除するため、
          // パネル作成/更新後に保持する(この時点ではまだcurrentPanelが無いことがあるため)。
          previewPostId = skeleton.previewPostId ?? undefined;
        } catch (skeletonError) {
          logger.debug(`テーマ構造の再現取得に失敗しました: ${messageOf(skeletonError)}`);
        }
      }

      PreviewPanel.createOrShow(
        context,
        bodyHtml,
        css,
        warning,
        `${targetSite.label} / ${targetSite.siteName}`,
        usingSkeleton ? undefined : featuredImageDataUri,
        onPreviewMessage,
        availableSites,
        targetSite.siteId ?? null,
        (siteId, postId) => api.deletePreviewPost(serverUrl, apiKey, actor, projectId, siteId, postId)
      );
      if (previewPostId && targetSite.siteId != null) {
        PreviewPanel.currentPanel?.recordPreviewPostId(targetSite.siteId, previewPostId);
      }
    };

    /** パネル内のセレクトで環境が切り替えられたときに、その環境のCSS/骨格を再取得して描画し直す。 */
    const onPreviewMessage = (message: PreviewMessage): void => {
      if (message.type !== 'switchSite') return;
      const nextSite =
        choices.find((c) => (c.siteId ?? null) === message.siteId) ??
        (message.siteId == null ? NO_SITE_CHOICE : undefined);
      if (!nextSite) return;
      void vscode.window.withProgress(
        { location: vscode.ProgressLocation.Notification, title: `${nextSite.siteName} のプレビューを生成しています…` },
        (progress) => renderForSite(nextSite, progress)
      );
    };

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'プレビューを生成しています…' },
      (progress) => renderForSite(initialSite as PreviewSiteChoice, progress)
    );
  } catch (err) {
    reportError('プレビューの生成に失敗しました', err);
  }
}
