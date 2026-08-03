import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { getServerUrl, requireApiKey, setApiKey, getActor, setActor, getProjectId, setProjectId } from './config';
import { parseArticle, stringifyArticle, extractLocalImageReferences } from './frontMatter';
import * as api from './apiClient';
import { PlanPanel } from './planPanel';
import { PreviewPanel } from './previewPanel';

export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    vscode.commands.registerCommand('letsBlog.setApiKey', () => commandSetApiKey(context)),
    vscode.commands.registerCommand('letsBlog.selectSite', () => commandSelectSite(context)),
    vscode.commands.registerCommand('letsBlog.publish', () => commandPublish(context)),
    vscode.commands.registerCommand('letsBlog.askAi', () => commandAskAi(context)),
    vscode.commands.registerCommand('letsBlog.suggestTags', () => commandSuggestTags(context)),
    vscode.commands.registerCommand('letsBlog.generateImage', () => commandGenerateImage(context)),
    vscode.commands.registerCommand('letsBlog.selectActor', () => commandSelectActor(context)),
    vscode.commands.registerCommand('letsBlog.selectProject', () => commandSelectProject(context)),
    vscode.commands.registerCommand('letsBlog.planArticle', () => commandPlanArticle(context)),
    vscode.commands.registerCommand('letsBlog.publishToTestEnvironment', () => commandPublishToTestEnvironment(context)),
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

async function replaceDocumentText(editor: vscode.TextEditor, newText: string): Promise<void> {
  const fullRange = new vscode.Range(
    editor.document.positionAt(0),
    editor.document.positionAt(editor.document.getText().length)
  );
  await editor.edit((builder) => builder.replace(fullRange, newText));
  await editor.document.save();
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
    vscode.window.showErrorMessage(String(err instanceof Error ? err.message : err));
  }
}

/**
 * 指定サイトへ現在のエディタの記事を投稿する共通処理。
 * front matterのtitleチェック・画像収集・publishPost呼び出し・front matter書き戻し・完了通知を行う。
 * letsBlog.publish(front matterのsiteを使用)とletsBlog.publishToTestEnvironment(test環境サイトを使用)から共有される。
 */
async function publishToSite(context: vscode.ExtensionContext, editor: vscode.TextEditor, siteKey: string): Promise<void> {
  const apiKey = await requireApiKey(context);
  const serverUrl = getServerUrl();
  const article = parseArticle(editor.document.getText());

  if (!article.data.title) {
    vscode.window.showErrorMessage("front matterに 'title' がありません。");
    return;
  }

  const baseDir = path.dirname(editor.document.uri.fsPath);
  const images = extractLocalImageReferences(article.content, baseDir).filter((img) =>
    fs.existsSync(img.absolutePath)
  );

  const result = await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: 'WordPressへ投稿しています…' },
    () =>
      api.publishPost(serverUrl, apiKey, {
        site: siteKey,
        title: article.data.title as string,
        slug: article.data.slug,
        status: article.data.status ?? 'draft',
        categories: article.data.categories ?? [],
        tags: article.data.tags ?? [],
        wpPostId: article.data.wp_post_id != null ? String(article.data.wp_post_id) : undefined,
        markdown: article.content,
        images,
      })
  );

  article.data.site = siteKey;
  article.data.wp_post_id = result.wpPostId;
  article.data.wp_post_url = result.wpPostUrl;
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

async function commandPublish(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  try {
    const article = parseArticle(editor.document.getText());
    if (!article.data.site) {
      vscode.window.showErrorMessage("front matterに 'site' が未設定です。先に「Let's Blog: Select Site」を実行してください。");
      return;
    }
    await publishToSite(context, editor, article.data.site);
  } catch (err) {
    vscode.window.showErrorMessage(`投稿に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}

async function commandPublishToTestEnvironment(context: vscode.ExtensionContext): Promise<void> {
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
    if (!project.testSite) {
      vscode.window.showErrorMessage(`プロジェクト '${project.name}' にはtest環境サイトが紐づいていません。`);
      return;
    }

    await publishToSite(context, editor, project.testSite.siteKey);
  } catch (err) {
    vscode.window.showErrorMessage(`test環境への投稿に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
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

    const doc = await vscode.workspace.openTextDocument({ content: result.result, language: 'markdown' });
    await vscode.window.showTextDocument(doc, { preview: false, viewColumn: vscode.ViewColumn.Beside });
  } catch (err) {
    vscode.window.showErrorMessage(`AI呼び出しに失敗しました: ${String(err instanceof Error ? err.message : err)}`);
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
    vscode.window.showErrorMessage(`タグ提案に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}

async function commandGenerateImage(context: vscode.ExtensionContext): Promise<void> {
  const editor = getActiveMarkdownEditor();
  if (!editor) return;

  const prompt = await vscode.window.showInputBox({
    prompt: '生成したい画像のプロンプトを入力してください',
    ignoreFocusOut: true,
  });
  if (!prompt) return;

  try {
    const apiKey = await requireApiKey(context);
    const image = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Notification, title: '画像を生成しています…' },
      () => api.generateImage(getServerUrl(), apiKey, prompt)
    );

    const baseDir = path.dirname(editor.document.uri.fsPath);
    const destPath = path.join(baseDir, image.fileName);
    fs.writeFileSync(destPath, Buffer.from(image.dataBase64, 'base64'));

    await editor.edit((builder) => {
      builder.insert(editor.selection.active, `![${prompt}](${image.fileName})`);
    });

    vscode.window.showInformationMessage(`画像を生成し ${image.fileName} として保存しました。`);
  } catch (err) {
    vscode.window.showErrorMessage(`画像生成に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}

async function commandSelectActor(context: vscode.ExtensionContext): Promise<void> {
  try {
    const apiKey = await requireApiKey(context);
    const users = await api.listUsers(getServerUrl(), apiKey);

    if (users.length === 0) {
      vscode.window.showWarningMessage('利用可能なユーザーがありません。先に管理画面でユーザーを作成してください。');
      return;
    }

    const currentActor = await getActor(context);
    const picked = await vscode.window.showQuickPick(
      users.map((u) => ({
        label: u.email,
        description: u.role + (currentActor?.id === u.id ? ' (現在選択中)' : ''),
        actor: u,
      })),
      { placeHolder: 'ユーザーを選択' }
    );
    if (!picked) return;

    await setActor(context, picked.actor);
    vscode.window.showInformationMessage(`ユーザーを '${picked.label}' に設定しました。`);
  } catch (err) {
    vscode.window.showErrorMessage(`ユーザー選択に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}

async function commandPlanArticle(context: vscode.ExtensionContext): Promise<void> {
  const actor = await getActor(context);
  if (!actor) {
    vscode.window.showErrorMessage('先に「Let\'s Blog: Select User」でユーザーを選択してください。');
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
    vscode.window.showErrorMessage(`プロジェクト選択に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}

const IMAGE_MIME_TYPES: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.bmp': 'image/bmp',
};

/**
 * Markdown本文中のローカル画像参照をbase64データURIへ置換する。プレビューはWebviewの外(APIサーバー)で
 * HTML化するため、投稿先を持たないローカル画像をそのまま渡すと壊れたリンクになってしまうのを防ぐ。
 */
function inlineLocalImages(content: string, baseDir: string): string {
  let rewritten = content;
  for (const image of extractLocalImageReferences(content, baseDir)) {
    if (!fs.existsSync(image.absolutePath)) continue;
    const mimeType = IMAGE_MIME_TYPES[path.extname(image.absolutePath).toLowerCase()];
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
    vscode.window.showErrorMessage(`プレビューの生成に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}
