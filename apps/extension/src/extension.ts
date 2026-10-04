import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
  getServerUrl,
  allowsInsecureTls,
  requireAccessToken,
  storeTokens,
  getAccessToken,
  getActor,
  setActor,
  getProjectId,
  setProjectId,
  requireProjectId,
  getConfiguredAiProvider,
  setConfiguredAiProvider,
  logout,
} from './config';
import * as deviceAuth from './deviceAuth';
import { decodeJwtPayload, extractEmail, extractPrimaryRoleName } from './jwtClaims';
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
import { submitArticle } from './articleSubmit';
import { resolveGitBackend } from './vscodeGit';
import { PlanPanel } from './planPanel';
import { ArticleCreationPanel } from './articleCreationPanel';
import { PreviewMessage, PreviewPanel, SiteOption } from './previewPanel';
import { showRealSitePreview } from './realSitePreview';
import { ImageGenPanel } from './imageGenPanel';
import { ImageGalleryPanel } from './imageGalleryPanel';
import { DiagramEditorPanel, DIAGRAM_REFERENCE_PATTERN } from './diagramEditorPanel';
import { DiagramGalleryPanel } from './diagramGalleryPanel';
import { SectionGenPanel } from './sectionGenPanel';
import { AskAiPanel } from './askAiPanel';
import { resolveSectionContext } from './headingContext';
import { buildSourcesSection } from './markdownSources';
import { logger } from './logger';
import { articleSlugOfPath, EDIT_ACTION, editRejectedArticle, formatFindings, RejectionFindings } from './rejectionEdit';
import { checkRejections, RejectionCheckDeps, RejectionPoller, resolvePollIntervalMs } from './rejectionNotifier';
import { CancelledError, messageOf, reportError } from './errorHandler';
import { buildSmartCardTag, buildStandardLink, parseHttpUrl } from './urlPaste';
import { ProofreadController } from './proofreadDiagnostics';
import { publishBlockedMessage } from './publishReviewLogic';
import { previewUnresolvedMessage } from './previewReviewLogic';
import { ReviewChecklistPanel } from './reviewChecklistPanel';
import { ReviewChecklistStore } from './reviewChecklistStore';
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
      if (event.affectsConfiguration('letsBlog.rejectionPollIntervalMs')) {
        rejectionPoller.restart();
      }
    })
  );

  // issue #1347: 自分が提出した記事の差し戻しを、起動時と一定間隔ごとに確認して通知する。
  const rejectionDeps = createRejectionCheckDeps(context);
  const rejectionPoller = new RejectionPoller(
    () => checkRejections(rejectionDeps),
    () =>
      resolvePollIntervalMs(vscode.workspace.getConfiguration('letsBlog').get<number>('rejectionPollIntervalMs'))
  );
  context.subscriptions.push(rejectionPoller);
  rejectionPoller.start();

  // issue #1216: 指摘チェックリストの対応状態とレビュー結果(本文スナップショット込み)は
  // ワークスペース状態(context.workspaceState)へローカル保存する。サーバー側は追加しない。
  const reviewChecklistStore = new ReviewChecklistStore(context);

  // issue #523: front matter検証(publish_scheduled_at/status/categories)は編集の都度デバウンスして実行する。
  // issue #1215: 本文のAIレビュー(5ステップ)は自動実行せず、letsBlog.proofreadNowコマンドの明示操作だけで実行する。
  const proofreadController = new ProofreadController(context, reviewChecklistStore);
  context.subscriptions.push(
    proofreadController,
    vscode.languages.registerCodeActionsProvider({ language: 'markdown' }, proofreadController, {
      providedCodeActionKinds: [vscode.CodeActionKind.QuickFix],
    }),
    vscode.window.onDidChangeVisibleTextEditors((editors) => editors.forEach((e) => proofreadController.refreshEditor(e))),
    vscode.workspace.onDidChangeTextDocument((event) => proofreadController.scheduleCheck(event.document)),
    vscode.workspace.onDidOpenTextDocument((document) => proofreadController.scheduleCheck(document)),
    vscode.workspace.onDidCloseTextDocument((document) => proofreadController.clearDocument(document))
  );

  context.subscriptions.push(
    vscode.commands.registerCommand('letsBlog.createArticle', () => commandCreateArticle(context)),
    vscode.commands.registerCommand('letsBlog.createArticleWithoutAi', () => commandCreateArticleWithoutAi(context)),
    vscode.commands.registerCommand('letsBlog.schedulePublication', () => commandSchedulePublication()),
    vscode.commands.registerCommand('letsBlog.login', () => commandLogin(context)),
    vscode.commands.registerCommand('letsBlog.logout', () => commandLogout(context)),
    vscode.commands.registerCommand('letsBlog.selectSite', () => commandSelectSite(context)),
    vscode.commands.registerCommand('letsBlog.publish', () => commandPublish(context, proofreadController)),
    vscode.commands.registerCommand('letsBlog.submitArticle', () => commandSubmitArticle(context)),
    vscode.commands.registerCommand('letsBlog.resubmitArticle', () => commandSubmitArticle(context)),
    vscode.commands.registerCommand('letsBlog.showRejectionFindings', () => commandShowRejectionFindings(context)),
    vscode.commands.registerCommand('letsBlog.checkRejections', () => commandCheckRejections(rejectionDeps)),
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
    vscode.commands.registerCommand('letsBlog.previewArticle', () => commandPreviewArticle(context, proofreadController)),
    vscode.commands.registerCommand('letsBlog.previewDevTools', () => PreviewPanel.openDevTools()),
    vscode.commands.registerCommand('letsBlog.pasteSmartCard', () => commandPasteSmartCard(context)),
    vscode.commands.registerCommand('letsBlog.pasteAsLink', () => commandPasteAsLink(context)),
    vscode.commands.registerCommand('letsBlog.proofreadNow', () => commandProofreadNow(proofreadController)),
    vscode.commands.registerCommand('letsBlog.reviewChecklist', () =>
      commandReviewChecklist(context, reviewChecklistStore)
    ),
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

/** 差し戻し確認(issue #1347)へVSCode APIと保存済みログイン・プロジェクトを接続する。 */
function createRejectionCheckDeps(context: vscode.ExtensionContext): RejectionCheckDeps {
  return {
    getProjectId: () => getProjectId(context),
    getAccessToken: () => getAccessToken(context),
    getActor: () => getActor(context),
    fetchMyReviews: (apiKey, actor, projectId) => api.listMyArticleReviews(apiKey, actor, projectId),
    // 別のマシンで開くと再通知される(Issue #1347: 既読管理は拡張側だけで完結させる)。
    state: {
      get: (key) => context.globalState.get<string[]>(key),
      update: (key, value) => context.globalState.update(key, value),
    },
    notify: (message, review) =>
      void vscode.window.showWarningMessage(message, EDIT_ACTION).then((choice) => {
        if (choice === EDIT_ACTION) {
          void commandEditRejectedArticle(context, review);
        }
      }),
    reportError: (message) => void vscode.window.showErrorMessage(message),
  };
}

const RESUBMIT_ACTION = '再提出';
let findingsChannel: vscode.OutputChannel | undefined;

function rejectionFindings(context: vscode.ExtensionContext): RejectionFindings {
  return new RejectionFindings({
    get: (key) => context.globalState.get<Record<string, string>>(key),
    update: (key, value) => context.globalState.update(key, value),
  });
}

/** 指摘事項を出力チャネル「Let's Blog: 指摘事項」へ出して表示する(専用のWebViewは使わない)。 */
function showFindingsChannel(slug: string, comment: string): void {
  findingsChannel ??= vscode.window.createOutputChannel("Let's Blog: 指摘事項");
  findingsChannel.clear();
  findingsChannel.appendLine(formatFindings(slug, comment));
  findingsChannel.show(true);
}

/**
 * 差し戻し通知の「編集」(issue #1348)。記事のブランチへ切り替えて article.md を通常のエディタで開き、
 * 指摘事項を出力チャネルへ出す。情報メッセージの「再提出」で #1342 の提出処理へ進める。
 */
async function commandEditRejectedArticle(
  context: vscode.ExtensionContext,
  review: { articleSlug: string; rejectComment?: string | null }
): Promise<void> {
  try {
    const root = requireWorkspaceRoot();
    const result = await editRejectedArticle({
      root,
      review,
      backend: await resolveGitBackend(root),
      findings: rejectionFindings(context),
      openArticle: async (articlePath) => {
        await vscode.window.showTextDocument(await vscode.workspace.openTextDocument(articlePath));
      },
      showFindings: showFindingsChannel,
    });
    if (result.status !== 'opened') {
      void vscode.window.showWarningMessage(result.reason);
      return;
    }
    const choice = await vscode.window.showInformationMessage(
      `記事「${review.articleSlug}」の指摘事項を「Let's Blog: 指摘事項」に表示しました。修正したら「${RESUBMIT_ACTION}」を選んでください。`,
      RESUBMIT_ACTION
    );
    if (choice === RESUBMIT_ACTION) {
      await commandSubmitArticle(context);
    }
  } catch (err) {
    reportError('記事を開けませんでした', err);
  }
}

/** 編集中の記事の指摘事項を、出力チャネルへもう一度表示する(issue #1348)。 */
function commandShowRejectionFindings(context: vscode.ExtensionContext): void {
  const slug = articleSlugOfPath(vscode.window.activeTextEditor?.document.uri.fsPath ?? '');
  const comment = slug === undefined ? undefined : rejectionFindings(context).get(slug);
  if (slug === undefined || comment === undefined) {
    void vscode.window.showInformationMessage('この記事に対する指摘事項は保存されていません。');
    return;
  }
  showFindingsChannel(slug, comment);
}

/** 間隔を待たずに差し戻しを確認する(issue #1347)。 */
async function commandCheckRejections(deps: RejectionCheckDeps): Promise<void> {
  const outcome = await checkRejections(deps, { manual: true });
  if (outcome.status === 'skipped') {
    void vscode.window.showInformationMessage(
      outcome.reason === 'not-logged-in'
        ? "ログインしていないため確認できません。「Let's Blog: Login」を先に実行してください。"
        : "プロジェクトが未選択のため確認できません。「Let's Blog: Select Project」を先に実行してください。"
    );
  } else if (outcome.status === 'checked' && outcome.notified === 0) {
    void vscode.window.showInformationMessage('新たな差し戻しはありません。');
  }
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
    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);
    await api.resolveContentCache(apiKey, actor, url);
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
    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);
    const result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'URLの情報を取得しています…' },
      () => api.resolveContentCache(apiKey, actor, url.toString())
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
  accessToken: string,
  roleName: string
): Promise<string | undefined> {
  try {
    const roles = await api.getRoles(accessToken);
    return roles.find((r) => r.roleName === roleName)?.displayName;
  } catch {
    return undefined;
  }
}

/** デバイス認可リクエスト自体のタイムアウト(ミリ秒)。ポーリングの総待ち時間(expires_in)とは別。 */
const DEVICE_AUTHORIZATION_REQUEST_TIMEOUT_MS = 30_000;

/** slow_down応答を受けた際、ポーリング間隔へ上乗せする時間(RFC 8628が推奨する挙動)。 */
const POLL_SLOW_DOWN_INCREMENT_MS = 5_000;

/**
 * Device Authorization Grant(RFC 8628)でログインする(issue #565)。
 * VSCode拡張はOAuthのリダイレクト先を持てないため、Authorization Codeではなくこのフローを使う。
 *
 * 1. Keycloakへデバイス認可をリクエストする
 * 2. user_codeを提示し、検証URLを既定ブラウザで自動的に開く
 * 3. 承認されるまでトークンエンドポイントをポーリングする(進捗通知から利用者がキャンセル可能)
 * 4. 取得したaccess_token/refresh_tokenをSecretStorageへ保存する
 *
 * 拡張がメールアドレス/パスワードを扱っていた以前の方式は廃止した(「誰であるか」を拡張が
 * 自己申告するのではなく、Keycloakが発行したJWTをサーバー側で検証する方式へ移行するため)。
 * ログイン中のユーザー表示(Actor)はJWTのクレーム(email/realm_access.roles)から復元する。
 */
async function commandLogin(context: vscode.ExtensionContext): Promise<void> {
  const serverUrl = getServerUrl();
  const insecure = allowsInsecureTls();

  try {
    const requestController = new AbortController();
    const requestTimer = setTimeout(() => requestController.abort(), DEVICE_AUTHORIZATION_REQUEST_TIMEOUT_MS);
    let authorization: deviceAuth.DeviceAuthorization;
    try {
      authorization = await deviceAuth.requestDeviceAuthorization(serverUrl, insecure, requestController.signal);
    } finally {
      clearTimeout(requestTimer);
    }

    const verificationUrl = authorization.verificationUriComplete ?? authorization.verificationUri;
    void vscode.env.openExternal(vscode.Uri.parse(verificationUrl));

    const tokens = await vscode.window.withProgress<deviceAuth.TokenResult>(
      {
        location: vscode.ProgressLocation.Notification,
        title: `ブラウザで開いた画面にコード「${authorization.userCode}」を入力して承認してください…`,
        cancellable: true,
      },
      (progress, cancellationToken) => pollForDeviceToken(serverUrl, insecure, authorization, progress, cancellationToken)
    );

    await storeTokens(context, tokens);
    // 別ユーザーでログインし直した場合に、前のユーザーの参照結果が残らないようにする。
    api.clearResponseCache();

    const claims = decodeJwtPayload(tokens.accessToken);
    const email = extractEmail(claims);
    const roleName = extractPrimaryRoleName(claims);
    await setActor(context, { email, role: roleName ?? '' });

    const roleLabel = roleName ? await resolveRoleDisplayName(tokens.accessToken, roleName) : undefined;
    vscode.window.showInformationMessage(
      `'${email}'${roleLabel ? ` (${roleLabel})` : ''} としてログインしました。`
    );
  } catch (err) {
    reportError('ログインに失敗しました', err);
  }
}

/**
 * ログアウトする(issue #1099)。端末側の資格情報(SecretStorage)の削除は
 * config.logout()がKeycloakへの通信結果によらず必ず行う。ここではその結果に応じて
 * 利用者への通知文言を切り替えるだけの薄いUI層(commandLoginと同じ役割分担)。
 */
async function commandLogout(context: vscode.ExtensionContext): Promise<void> {
  const result = await logout(context);
  // 前のユーザーの参照結果が残らないようにする(commandLoginと同じ理由、issue #1099)。
  api.clearResponseCache();

  if (!result.wasLoggedIn) {
    vscode.window.showInformationMessage('ログアウトしました。');
    return;
  }
  if (result.keycloakSessionEnded) {
    vscode.window.showInformationMessage('ログアウトしました。');
  } else {
    vscode.window.showWarningMessage(
      'ログアウトしました(端末側の資格情報は削除済みです)。' +
        'ただし、Keycloak側のセッション終了に失敗したため、サーバー側のセッションが残っている可能性があります。'
    );
  }
}

/**
 * トークンエンドポイントを、承認されるかタイムアウト/拒否されるまでポーリングする。
 * 進捗通知(withProgress)がキャンセルされた場合はCancelledErrorへ変換し、
 * errorHandler.reportErrorが「操作をキャンセルしました」として扱えるようにする。
 */
async function pollForDeviceToken(
  serverUrl: string,
  allowInsecureTls: boolean,
  authorization: deviceAuth.DeviceAuthorization,
  progress: vscode.Progress<{ message?: string }>,
  cancellationToken: vscode.CancellationToken
): Promise<deviceAuth.TokenResult> {
  const controller = new AbortController();
  const cancelListener = cancellationToken.onCancellationRequested(() => controller.abort());
  try {
    let intervalMs = Math.max(authorization.interval, 1) * 1000;
    const deadline = Date.now() + authorization.expiresIn * 1000;

    for (;;) {
      if (cancellationToken.isCancellationRequested) {
        throw new CancelledError('ログインをキャンセルしました');
      }
      const remainingSec = Math.max(0, Math.round((deadline - Date.now()) / 1000));
      progress.report({
        message: `コード: ${authorization.userCode} ・ 承認を待っています…(あと約${remainingSec}秒で期限切れ)`,
      });

      await sleepOrAbort(intervalMs, controller.signal);
      if (cancellationToken.isCancellationRequested) {
        throw new CancelledError('ログインをキャンセルしました');
      }
      if (Date.now() >= deadline) {
        throw new Error("認可コードの有効期限が切れました。「Let's Blog: Login」をやり直してください。");
      }

      let outcome: deviceAuth.PollOutcome;
      try {
        outcome = await deviceAuth.pollForToken(serverUrl, authorization.deviceCode, allowInsecureTls, controller.signal);
      } catch (error) {
        if (controller.signal.aborted) {
          throw new CancelledError('ログインをキャンセルしました');
        }
        throw error;
      }

      switch (outcome.kind) {
        case 'success':
          return outcome.tokens;
        case 'pending':
          continue;
        case 'slow_down':
          // RFC 8628: slow_downを受けたらポーリング間隔を広げる。
          intervalMs += POLL_SLOW_DOWN_INCREMENT_MS;
          continue;
        case 'denied':
          throw new Error("ログインが拒否されました。「Let's Blog: Login」をやり直してください。");
        case 'expired':
          throw new Error("認可コードの有効期限が切れました。「Let's Blog: Login」をやり直してください。");
      }
    }
  } finally {
    cancelListener.dispose();
  }
}

/** ms待機する。signalがabortされた場合は即座にCancelledErrorで抜ける(進捗キャンセル時の応答性のため)。 */
function sleepOrAbort(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new CancelledError('ログインをキャンセルしました'));
      return;
    }
    const onAbort = (): void => {
      clearTimeout(timer);
      reject(new CancelledError('ログインをキャンセルしました'));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

async function commandSelectSite(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const apiKey = await requireAccessToken(context);
    const sites = await api.listSites(apiKey);
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
  const apiKey = await requireAccessToken(context);
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
    const found = await api.lookupExistingPost(apiKey, siteKey, article.data.slug, actor);
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

async function commandPublish(
  context: vscode.ExtensionContext,
  proofreadController: ProofreadController
): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    // issue #1217: 投稿先を選ばせてから止める無駄を避けるため、環境の選択より前にレビューする。
    // 未対応の指摘が1件でもあれば投稿しない(全件が「修正済み」または「スキップ」になるまで)。
    const review = await proofreadController.reviewForPublish(editor.document);
    if (review.blocked) {
      const openChecklist = '指摘チェックリストを表示';
      const choice = await vscode.window.showErrorMessage(publishBlockedMessage(review.unresolvedCount), openChecklist);
      if (choice === openChecklist) {
        await vscode.commands.executeCommand('letsBlog.reviewChecklist');
      }
      return;
    }

    const article = parseArticle(editor.document.getText());
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);
    if (!projectId) {
      vscode.window.showErrorMessage(
        'プロジェクトが未選択です。front matterのproject_id、または「Let\'s Blog: Select Project」で設定してください。'
      );
      return;
    }

    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);
    const project = await api.getProject(apiKey, actor, projectId);

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
    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);

    let candidates: { siteKey: string; wpPostId: string }[];
    if (article.data.slug) {
      const sites = await api.listSites(apiKey, actor);
      const found = await Promise.all(
        sites.map(async (s) => {
          const result = await api.lookupExistingPost(apiKey, s.siteKey, article.data.slug as string, actor);
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
      () => api.deletePost(apiKey, actor, target.siteKey, target.wpPostId)
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
    const apiKey = await requireAccessToken(context);
    const article = parseArticle(editor.document.getText());
    const selectedText = editor.document.getText(editor.selection);
    const text = selectedText.trim().length > 0 ? selectedText : article.content;
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);

    const result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'AIに問い合わせています…' },
      () => api.askAi(apiKey, mode.value, text, undefined, provider, projectId)
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
    const apiKey = await requireAccessToken(context);
    const article = parseArticle(editor.document.getText());
    // issue #525: プロジェクトのマスター環境サイトに既存のタグを優先して提案させる。
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);

    const [suggestion, existingTags] = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: 'タグ/カテゴリを提案中…' },
      () => Promise.all([
        api.suggestTags(apiKey, article.content, undefined, provider, projectId),
        projectId
          ? api.listExistingTags(apiKey, undefined, projectId)
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
 * issue #523 / #1215: front matter検証と、本文の5ステップAIレビュー(日本語チェック→校正チェック→
 * 校閲→読者視点でのチェック→文体チェック)を即時実行する。本文のレビューはこのコマンドでのみ実行される。
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

/**
 * issue #1216: 指摘チェックリストを別タブに表示する。永続化済みの対応状態(未実行なら空)を
 * ステップごとにまとめて表示し、レビューが(再)実行されると自動で更新される。
 */
async function commandReviewChecklist(
  context: vscode.ExtensionContext,
  reviewChecklistStore: ReviewChecklistStore
): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  const panel = ReviewChecklistPanel.createOrShow(context, reviewChecklistStore);
  panel.show(editor.document.uri.toString());
}

/** issue #523: front matterのstatusが不正な値だった際のクイックフィックス。有効な値から選び直す。 */
async function commandFixInvalidStatus(context: vscode.ExtensionContext, uri: vscode.Uri): Promise<void> {
  try {
    const document = await vscode.workspace.openTextDocument(uri);
    const editor = await vscode.window.showTextDocument(document);
    const apiKey = await requireAccessToken(context);
    const statuses = await api.getPostStatuses(apiKey);

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

    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);
    const detail = await api.getDiagramDetail(apiKey, actor, diagramId);

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
 * 編集中の記事(`articles/<slug>/article.md`)を提出する(issue #1342)。記事ディレクトリだけをコミットして
 * 現在のブランチをpushし(資格情報は利用者のgit設定/VSCode本体に委ねる)、サーバーの提出APIでPRを作る。
 */
async function commandSubmitArticle(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const root = requireWorkspaceRoot();
    await editor.document.save();
    const text = editor.document.getText();
    const projectId = (parseArticle(text).data.project_id as number | undefined) ?? requireProjectId(context);
    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);

    const result = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '記事を提出しています…' },
      async () =>
        submitArticle({
          root,
          articlePath: editor.document.uri.fsPath,
          text,
          backend: await resolveGitBackend(root),
          createSubmission: (request) => api.submitArticleReview(apiKey, actor, projectId, request),
        })
    );

    if (result.status !== 'submitted') {
      vscode.window.showErrorMessage(`記事を提出できませんでした。${result.reason}`);
      return;
    }
    const open = 'Pull Request を開く';
    const message = result.created
      ? `Pull Request #${result.prNumber} を作成しました: ${result.url}`
      : `既存の Pull Request #${result.prNumber} を再提出しました: ${result.url}`;
    const choice = await vscode.window.showInformationMessage(message, open);
    if (choice === open) {
      await vscode.env.openExternal(vscode.Uri.parse(result.url));
    }
  } catch (err) {
    reportError('記事の提出に失敗しました', err);
  }
}

/**
 * GitHub Issueを起点にせず、コマンドから直接記事を作成する。
 * Issueが無い記事(単発の告知や覚書など)を書き始めるための入口。
 */
async function commandCreateArticle(context: vscode.ExtensionContext): Promise<void> {
  try {
    await requireAccessToken(context);
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
    const apiKey = await getAccessToken(context);
    const actor = await getActor(context);
    if (!apiKey || !actor) return [];

    const categories = await api.listExistingCategoriesWithParents(apiKey, actor, projectId);
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
    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);
    const projects = await api.listProjects(apiKey, actor);

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
 * プレビューを表示するサイトを選ばせる。
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

/** プレビュー生成の前にレビューし、未対応の指摘があればメッセージで示す。プレビューは止めない。 */
async function reviewBeforePreviewRender(
  document: vscode.TextDocument,
  proofreadController: ProofreadController
): Promise<void> {
  const outcome = await proofreadController.reviewForPreview(document);
  if (outcome.failed) {
    // 失敗時の扱いは#1224。ここではプレビューを止めず、事実だけ伝える。
    void vscode.window.showWarningMessage(`プレビュー前のレビューに失敗しました: ${outcome.error}`);
    return;
  }
  const message = previewUnresolvedMessage(outcome.unresolvedCount);
  if (!message) return;
  const openChecklist = '指摘チェックリストを表示';
  // awaitしない: メッセージを閉じるのを待たせず、プレビューをそのまま表示する。
  void vscode.window.showWarningMessage(message, openChecklist).then((choice) => {
    if (choice === openChecklist) {
      return vscode.commands.executeCommand('letsBlog.reviewChecklist');
    }
    return undefined;
  });
}

async function commandPreviewArticle(
  context: vscode.ExtensionContext,
  proofreadController: ProofreadController
): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    // issue #1226: レビューはここ(コマンドの入口)で1回だけ行う。renderForSiteはパネル内の環境切り替えからも
    // 呼ばれるため、そこに置くと切り替えのたびにレビューが走ってしまう。未対応の指摘があってもプレビューは止めない。
    await reviewBeforePreviewRender(editor.document, proofreadController);

    const article = parseArticle(editor.document.getText());
    const projectId = (article.data.project_id as number | undefined) ?? getProjectId(context);
    if (!projectId) {
      vscode.window.showErrorMessage(
        'プロジェクトが未選択です。front matterのproject_id、または「Let\'s Blog: Select Project」で設定してください。'
      );
      return;
    }

    const apiKey = await requireAccessToken(context);
    const actor = await getActor(context);

    const project = await api.getProject(apiKey, actor, projectId);
    const choices = buildPreviewSiteChoices(project);
    // プレビューは実サイトで表示する(issue #1562)。サイトが紐づいていなければ表示できないので案内を出す。
    if (choices.length === 0) {
      PreviewPanel.showNoSite(context);
      return;
    }
    // パネル内の環境切り替えセレクトに渡す選択肢。ローカル/テスト/本番の見た目を
    // 記事ごとに開き直さず切り替えて比較できるようにする(要件: 環境間の見た目の差分確認)。
    const availableSites: SiteOption[] = choices.map((c) => ({
      siteId: c.siteId ?? null,
      label: c.label,
      siteName: c.siteName,
    }));

    let initialSite: PreviewSiteChoice | undefined;
    if (choices.length === 1) {
      initialSite = choices[0];
    } else {
      initialSite = await vscode.window.showQuickPick(choices, {
        placeHolder: 'プレビューするサイトを選択',
      });
      if (!initialSite) return;
    }

    const baseDir = path.dirname(editor.document.uri.fsPath);
    const markdown = inlineLocalImages(article.content, baseDir);

    const featuredImage = resolveFeaturedImageReference(article.data, baseDir);
    const featuredImageDataUri = featuredImage ? toDataUri(featuredImage.absolutePath) : undefined;
    const title = (article.data.title as string | undefined) ?? '';

    /** 指定サイトの実サイトのプレビューを取得し、パネルへ表示する。環境切り替え時にも同じ経路を通す。 */
    const renderForSite = async (
      targetSite: PreviewSiteChoice,
      progress: vscode.Progress<{ message?: string }>
    ): Promise<void> => {
      progress.report({ message: 'Markdownを変換しています…' });
      const html = await api.renderPreviewHtml(apiKey, actor, projectId, markdown);

      // 実サイトのプレビューURL(issue #1562)で表示する。プラグインが必須のため、使えないサイトでは
      // 導入の案内を出す。旧方式(テーマCSSの取得・非公開投稿での表示)は#1564で削除した。
      await showRealSitePreview({
        context,
        apiKey,
        actor,
        projectId,
        site: targetSite,
        availableSites,
        onMessage: onPreviewMessage,
        html,
        title,
        categories: article.data.categories,
        tags: article.data.tags,
        featuredImageDataUri,
        report: (message) => progress.report({ message }),
      });
    };

    /** パネル内のセレクトで環境が切り替えられたときに、その環境のプレビューを取得して表示し直す。 */
    const onPreviewMessage = (message: PreviewMessage): void => {
      if (message.type !== 'switchSite') return;
      const nextSite = choices.find((c) => (c.siteId ?? null) === message.siteId);
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
