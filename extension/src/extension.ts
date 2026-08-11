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
  validateScheduledPublication,
} from './frontMatter';
import * as api from './apiClient';
import { PlanPanel } from './planPanel';
import { ArticleCreationPanel } from './articleCreationPanel';
import { PreviewPanel } from './previewPanel';
import { ImageGenPanel } from './imageGenPanel';
import { ImageGalleryPanel } from './imageGalleryPanel';
import { SectionGenPanel } from './sectionGenPanel';
import { resolveSectionContext } from './headingContext';
import { logger } from './logger';
import { messageOf, reportError } from './errorHandler';

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

  context.subscriptions.push(
    vscode.commands.registerCommand('letsBlog.createArticle', () => commandCreateArticle(context)),
    vscode.commands.registerCommand('letsBlog.schedulePublication', () => commandSchedulePublication()),
    vscode.commands.registerCommand('letsBlog.login', () => commandLogin(context)),
    vscode.commands.registerCommand('letsBlog.setApiKey', () => commandSetApiKey(context)),
    vscode.commands.registerCommand('letsBlog.selectSite', () => commandSelectSite(context)),
    vscode.commands.registerCommand('letsBlog.publish', () => commandPublish(context)),
    vscode.commands.registerCommand('letsBlog.deletePost', () => commandDeletePost(context)),
    vscode.commands.registerCommand('letsBlog.askAi', () => commandAskAi(context)),
    vscode.commands.registerCommand('letsBlog.suggestTags', () => commandSuggestTags(context)),
    vscode.commands.registerCommand('letsBlog.generateImage', () => commandGenerateImage(context)),
    vscode.commands.registerCommand('letsBlog.imageGallery', () => commandImageGallery(context)),
    vscode.commands.registerCommand('letsBlog.generateSection', () => commandGenerateSection(context)),
    vscode.commands.registerCommand('letsBlog.selectProject', () => commandSelectProject(context)),
    vscode.commands.registerCommand('letsBlog.planArticle', () => commandPlanArticle(context)),
    vscode.commands.registerCommand('letsBlog.previewArticle', () => commandPreviewArticle(context))
  );
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
    vscode.window.showInformationMessage(`'${result.user.email}' としてログインしました。`);
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

  // 予約投稿は本番サイトでのみ有効。送信前に形式と未来日時であることを確認する。
  const scheduled = validateScheduledPublication(article.data.publish_scheduled_at);
  if (scheduled.error) {
    vscode.window.showErrorMessage(scheduled.error);
    return;
  }

  const existingPostId = resolveExistingPostId(article.data, siteKey);
  const actor = await getActor(context);

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

async function pickPreviewSite(
  serverUrl: string,
  apiKey: string,
  actor: api.Actor | undefined,
  projectId: number
): Promise<PreviewSiteChoice | undefined> {
  const project = await api.getProject(serverUrl, apiKey, actor, projectId);
  const choices = buildPreviewSiteChoices(project);

  if (choices.length === 0) {
    // サイト未紐付けでもプレビュー自体は可能(CSSなしで表示する)。
    return { label: 'サイトなし', siteName: 'サイト未紐付け' };
  }
  if (choices.length === 1) {
    return choices[0];
  }
  return vscode.window.showQuickPick(choices, {
    placeHolder: 'プレビューに使うサイトのCSSを選択',
  });
}

/** 複数の警告文を改行区切りでまとめる。 */
function appendWarning(base: string | undefined, next: string): string {
  return base ? `${base}\n${next}` : next;
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

    const site = await pickPreviewSite(serverUrl, apiKey, actor, projectId);
    if (!site) return;

    const baseDir = path.dirname(editor.document.uri.fsPath);
    const markdown = inlineLocalImages(article.content, baseDir);

    const featuredImage = resolveFeaturedImageReference(article.data, baseDir);
    const featuredImageDataUri = featuredImage ? toDataUri(featuredImage.absolutePath) : undefined;
    let warning: string | undefined;
    if (featuredImage && !featuredImageDataUri) {
      warning = `アイキャッチ画像が見つかりません: ${featuredImage.reference}`;
    }

    await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'プレビューを生成しています…' },
      async (progress) => {
        progress.report({ message: 'Markdownを変換しています…' });
        const html = await api.renderPreviewHtml(serverUrl, apiKey, actor, projectId, markdown);

        progress.report({ message: `${site.siteName} のCSSを取得しています…` });
        let css = '';
        if (site.siteId == null) {
          warning = appendWarning(warning, 'プロジェクトにサイトが紐づいていないため、CSSなしで表示しています。');
        } else {
          try {
            const themeCss = await api.getThemeCss(serverUrl, apiKey, actor, projectId, site.siteId);
            if (themeCss.available) {
              css = themeCss.css;
            } else {
              warning = appendWarning(
                warning,
                `${site.siteName} のCSSを取得できませんでした: ${themeCss.reason ?? '不明なエラー'}`
              );
            }
          } catch (cssError) {
            warning = appendWarning(warning, `${site.siteName} のCSS取得に失敗しました: ${messageOf(cssError)}`);
          }
        }

        PreviewPanel.createOrShow(html, css, warning, `${site.label} / ${site.siteName}`, featuredImageDataUri);
      }
    );
  } catch (err) {
    reportError('プレビューの生成に失敗しました', err);
  }
}
