import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  configurationUpdates,
  executedCommands,
  inputBoxCalls,
  lastCreatedWebviewPanel,
  openedDocuments,
  openedExternalUrls,
  queueInputBox,
  queueQuickPick,
  quickPickCalls,
  setClipboardText,
  setConfiguration,
  setShowTextDocumentResolver,
  setWarningResponse,
  setWorkspaceFolders,
  shownErrors,
  shownInformations,
  shownWarnings,
  untitledDocuments,
} from '../__mocks__/vscode';
import * as vscodeStub from '../__mocks__/vscode';
import * as api from '../apiClient';
import { getProjectId } from '../config';
import { Harness, startExtension } from './support/extensionHarness';
import { FakeEditor } from './support/fakeEditor';
import { jsonReply } from './support/localHttpServer';
import { waitFor } from './support/panelKit';

/**
 * 記事の編集・作成に関するコマンド(extension.ts)。外部通信(gateway)は実サーバー、
 * VS Code(エディタ・QuickPick・入力欄・通知)は代替。拡張自身のモジュールは実物のまま動かす。
 */
let harness: Harness | undefined;
const ARTICLE = '---\ntitle: 記事\nslug: my-article\nstatus: draft\n---\n本文です。\n';

afterEach(async () => {
  jest.restoreAllMocks();
  await harness?.stop();
  harness = undefined;
});

async function start(editorText: string | null = ARTICLE, options: Parameters<typeof startExtension>[0] = {}): Promise<FakeEditor | undefined> {
  harness = await startExtension(options);
  return editorText === null ? undefined : harness.open(new FakeEditor(editorText));
}

/** 並び順の先頭の項目を選ぶ利用者。 */
const pickFirst = (items: any[]): unknown => items[0];

describe('エディタが無いときの共通の案内', () => {
  const COMMANDS = [
    'letsBlog.pasteSmartCard',
    'letsBlog.pasteAsLink',
    'letsBlog.selectSite',
    'letsBlog.publish',
    'letsBlog.submitArticle',
    'letsBlog.resubmitArticle',
    'letsBlog.deletePost',
    'letsBlog.askAi',
    'letsBlog.suggestTags',
    'letsBlog.proofreadNow',
    'letsBlog.reviewChecklist',
    'letsBlog.generateImage',
    'letsBlog.imageGallery',
    'letsBlog.addNewDiagram',
    'letsBlog.editDiagram',
    'letsBlog.diagramGallery',
    'letsBlog.generateSection',
    'letsBlog.askAiSearch',
    'letsBlog.schedulePublication',
    'letsBlog.previewArticle',
  ];

  it.each(COMMANDS)('%s は、Markdownを開いてから実行するよう案内して何もしない', async (command) => {
    await start(null);

    await harness!.run(command);

    expect(shownErrors).toEqual(['Markdownファイルを開いた状態で実行してください。']);
    expect(harness!.server.recorded.filter((r) => !r.path.includes('my-reviews'))).toEqual([]);
  });
});

describe('URLの貼り付け', () => {
  it('Ctrl+Shift+V: URLならカード記法を挿入し、情報取得はバックグラウンドで先に行う', async () => {
    const editor = (await start())!;
    harness!.api({
      'GET /api/content-cache': jsonReply({ url: 'https://example.test/a', type: 'BLOGCARD', data: { title: 'T' } }),
    });
    editor.setCursor(editor.text.length);
    setClipboardText(' https://example.test/a \n');

    await harness!.run('letsBlog.pasteSmartCard');
    await waitFor(() => harness!.requests('GET /api/content-cache').length === 1);

    expect(editor.text.endsWith('[blogcard https://example.test/a]')).toBe(true);
    expect(harness!.requests('GET /api/content-cache')[0].path).toBe('/api/content-cache?url=https%3A%2F%2Fexample.test%2Fa');
  });

  it('Amazon の URL なら amazon タグで、選択範囲があればそれを置き換える', async () => {
    const editor = (await start())!;
    harness!.api({ 'GET /api/content-cache': jsonReply({ url: 'x', type: 'AMAZON', data: {} }) });
    editor.select(editor.text.indexOf('本文です。'), editor.text.indexOf('本文です。') + 5);
    setClipboardText('https://www.amazon.co.jp/dp/B000');

    await harness!.run('letsBlog.pasteSmartCard');
    await waitFor(() => harness!.requests('GET /api/content-cache').length === 1);

    expect(editor.text).toContain('[amazon https://www.amazon.co.jp/dp/B000]');
    expect(editor.text).not.toContain('本文です。');
  });

  it('先行取得に失敗しても貼り付けは成立する(未ログインでも例外にしない)', async () => {
    const editor = (await start(ARTICLE, { login: false }))!;
    setClipboardText('https://example.test/a');

    await expect(harness!.run('letsBlog.pasteSmartCard')).resolves.toBeUndefined();

    expect(editor.text).toContain('[blogcard https://example.test/a]');
    expect(shownErrors).toEqual([]);
  });

  it('Ctrl+Shift+V: URL以外の貼り付けは既定の貼り付けに任せる', async () => {
    const editor = (await start())!;
    setClipboardText('ただの文章 です');

    await harness!.run('letsBlog.pasteSmartCard');

    expect(editor.text).toBe(ARTICLE);
    expect(executedCommands).toContainEqual({ command: 'editor.action.clipboardPasteAction', args: [] });
  });

  it('Ctrl+V: URLなら取得したタイトルとサイト名のリンクを挿入する', async () => {
    const editor = (await start())!;
    harness!.api({
      'GET /api/content-cache': jsonReply({ url: 'x', type: 'BLOGCARD', data: { title: 'タイトル', siteName: 'サイト' } }),
    });
    setClipboardText('https://example.test/a');

    await harness!.run('letsBlog.pasteAsLink');

    expect(editor.text).toContain('[タイトル | サイト](https://example.test/a)');
  });

  it('Ctrl+V: Amazon の応答は商品名を使い、サイト名はホスト名にする', async () => {
    const editor = (await start())!;
    harness!.api({
      'GET /api/content-cache': jsonReply({ url: 'x', type: 'AMAZON', data: { productName: '商品' } }),
    });
    setClipboardText('https://www.amazon.co.jp/dp/B000');

    await harness!.run('letsBlog.pasteAsLink');

    expect(editor.text).toContain('[商品 | www.amazon.co.jp](https://www.amazon.co.jp/dp/B000)');
  });

  it('Ctrl+V: 情報を取得できなければ、タイトルをURLにしたリンクを挿入する', async () => {
    const editor = (await start())!;
    harness!.api({ 'GET /api/content-cache': jsonReply({ url: 'x', type: 'BLOGCARD', data: {} }) });
    setClipboardText('https://example.test/a');

    await harness!.run('letsBlog.pasteAsLink');

    expect(editor.text).toContain('[https://example.test/a | example.test](https://example.test/a)');
  });

  it('Ctrl+V: 取得に失敗した場合はURLそのものを貼り付ける', async () => {
    const editor = (await start(ARTICLE, { login: false }))!;
    setClipboardText('https://example.test/a');

    await harness!.run('letsBlog.pasteAsLink');

    expect(editor.text).toContain('https://example.test/a');
    expect(editor.text).not.toContain('|');
  });

  it('Ctrl+V: URL以外の貼り付けは既定の貼り付けに任せる', async () => {
    const editor = (await start())!;
    setClipboardText('文章');

    await harness!.run('letsBlog.pasteAsLink');

    expect(editor.text).toBe(ARTICLE);
    expect(executedCommands).toContainEqual({ command: 'editor.action.clipboardPasteAction', args: [] });
  });
});

describe('サイトの選択', () => {
  it('選んだサイトを front matter の site に書き込み、保存して知らせる', async () => {
    const editor = (await start())!;
    harness!.api({ 'GET /api/sites': jsonReply([{ id: 1, name: 'ブログ', siteKey: 'blog' }]) });
    queueQuickPick(pickFirst);

    await harness!.run('letsBlog.selectSite');

    expect(editor.text).toContain('site: blog');
    expect(editor.saveCount).toBe(1);
    expect(shownInformations).toEqual(["サイトを 'blog' に設定しました。"]);
    expect(quickPickCalls[0].items).toEqual([{ label: 'ブログ', description: 'blog', siteKey: 'blog' }]);
  });

  it('登録済みのサイトが無ければ警告する', async () => {
    await start();
    harness!.api({ 'GET /api/sites': jsonReply([]) });

    await harness!.run('letsBlog.selectSite');

    expect(shownWarnings[0]).toContain('登録済みのサイトがありません');
  });

  it('選択をやめたら何も書き換えない', async () => {
    const editor = (await start())!;
    harness!.api({ 'GET /api/sites': jsonReply([{ id: 1, name: 'ブログ', siteKey: 'blog' }]) });

    await harness!.run('letsBlog.selectSite');

    expect(editor.text).toBe(ARTICLE);
  });

  it('サイト一覧を取得できなければ失敗として知らせる', async () => {
    await start();
    harness!.api({ 'GET /api/sites': { status: 403, body: 'forbidden' } });

    await harness!.run('letsBlog.selectSite');

    expect(shownErrors[0]).toContain('サイトの選択に失敗しました');
  });
});

describe('AIプロバイダーの切り替えと AI 呼び出し', () => {
  it('switchAiProvider は選んだ値を設定へ書き込み、表示名で知らせる(現在値には印が付く)', async () => {
    await start(null);
    setConfiguration('letsBlog.aiProvider', 'OPENAI');
    queueQuickPick((items) => items.find((i) => i.value === 'CLAUDE'));

    await harness!.run('letsBlog.switchAiProvider');

    expect(quickPickCalls[0].items.map((i: { label: string }) => i.label)).toEqual([
      'サーバー既定値を使用',
      'Ollama',
      '$(check) OpenAI (ChatGPT)',
      'Claude (Anthropic)',
    ]);
    expect(configurationUpdates.map((u) => [u.key, u.value])).toEqual([['letsBlog.aiProvider', 'CLAUDE']]);
    expect(shownInformations).toEqual(['AIプロバイダーを「Claude (Anthropic)」に切り替えました。']);
  });

  it('switchAiProvider は、選択をやめたら何も変えない', async () => {
    await start(null);

    await harness!.run('letsBlog.switchAiProvider');

    expect(configurationUpdates).toEqual([]);
    expect(shownInformations).toEqual([]);
  });

  it('askAi は選択範囲(無ければ記事本文)をAIへ送り、結果を出典つきで新しい文書に開く', async () => {
    const editor = (await start())!;
    harness!.api({
      'POST /api/ai/draft': jsonReply({ result: '下書き', sources: [{ title: '出典', url: 'https://example.test/s' }] }),
    });
    queueQuickPick((items) => items.find((i) => i.value === 'draft'));
    queueQuickPick((items) => items.find((i) => i.value === 'OLLAMA'));

    await harness!.run('letsBlog.askAi');

    expect(JSON.parse(harness!.requests('POST /api/ai/draft')[0].body)).toEqual({
      mode: 'draft',
      text: expect.stringContaining('本文です。'),
      provider: 'OLLAMA',
      projectId: 7,
    });
    expect(untitledDocuments).toEqual([
      { content: '下書き\n\n---\n**出典:**\n- [出典](https://example.test/s)\n', language: 'markdown' },
    ]);

    editor.select(editor.text.indexOf('本文'), editor.text.indexOf('本文') + 2);
    queueQuickPick((items) => items.find((i) => i.value === 'summarize'));
    queueQuickPick((items) => items.find((i) => i.value === ''));
    await harness!.run('letsBlog.askAi');
    expect(JSON.parse(harness!.requests('POST /api/ai/draft')[1].body)).toEqual({ mode: 'summarize', text: '本文', projectId: 7 });
  });

  it('askAi は、モードやプロバイダーの選択をやめたら何も送らない', async () => {
    await start();

    await harness!.run('letsBlog.askAi');
    queueQuickPick((items) => items[0]);
    await harness!.run('letsBlog.askAi');

    expect(harness!.requests('POST /api/ai/draft')).toHaveLength(0);
  });

  it('askAi の失敗は、AI呼び出しの失敗として知らせる', async () => {
    await start();
    harness!.api({ 'POST /api/ai/draft': { status: 403, body: 'forbidden' } });
    queueQuickPick(pickFirst);
    queueQuickPick(pickFirst);

    await harness!.run('letsBlog.askAi');

    expect(shownErrors[0]).toContain('AI呼び出しに失敗しました');
  });
});

describe('タグ・カテゴリの提案', () => {
  const SUGGESTION = { categories: ['技術'], tags: ['AI', 'ai-existing'] };

  it('提案から選んだ項目を front matter へ反映する(既存タグには印を付ける)', async () => {
    const editor = (await start('---\ntitle: 記事\ncategories:\n  - 既存\n---\n本文\n'))!;
    harness!.api({
      'POST /api/ai/tags': jsonReply(SUGGESTION),
      'GET /api/projects/7/article-plan/tags': jsonReply(['AI-Existing']),
    });
    queueQuickPick(pickFirst);
    queueQuickPick((items) => items);

    await harness!.run('letsBlog.suggestTags');

    const proposal = quickPickCalls[1].items as { label: string; description: string }[];
    expect(proposal.map((i) => [i.label, i.description])).toEqual([
      ['技術', 'カテゴリ'],
      ['AI', 'タグ'],
      ['ai-existing', 'タグ (既存)'],
    ]);
    expect(editor.text).toContain('技術');
    expect(editor.text).toContain('既存');
    expect(editor.text).toContain('ai-existing');
    expect(shownInformations).toEqual(['front matterに反映しました。']);
  });

  it('記事にカテゴリもタグも無くても、選んだ項目から新しく作る', async () => {
    const editor = (await start('---\ntitle: 記事\n---\n本文\n'))!;
    harness!.api({
      'POST /api/ai/tags': jsonReply({ categories: ['技術'], tags: ['AI'] }),
      'GET /api/projects/7/article-plan/tags': jsonReply([]),
    });
    queueQuickPick(pickFirst);
    queueQuickPick((items) => items);

    await harness!.run('letsBlog.suggestTags');

    expect(editor.text).toContain('- 技術');
    expect(editor.text).toContain('- AI');
  });

  it('プロジェクトが分からなければ既存タグは取らずに提案する', async () => {
    await start(ARTICLE, { projectId: null });
    harness!.api({ 'POST /api/ai/tags': jsonReply({ categories: [], tags: ['AI'] }) });
    queueQuickPick(pickFirst);
    queueQuickPick(() => undefined);

    await harness!.run('letsBlog.suggestTags');

    expect(harness!.requests('GET /api/projects/7/article-plan/tags')).toHaveLength(0);
    expect(JSON.parse(harness!.requests('POST /api/ai/tags')[0].body)).toEqual({ text: expect.any(String), provider: undefined });
  });

  it('提案が無ければ知らせ、何も選ばなければ書き換えない', async () => {
    const editor = (await start())!;
    harness!.api({
      'POST /api/ai/tags': jsonReply({ categories: [], tags: [] }),
      'GET /api/projects/7/article-plan/tags': jsonReply([]),
    });
    queueQuickPick(pickFirst);
    await harness!.run('letsBlog.suggestTags');
    expect(shownInformations).toEqual(['提案はありませんでした。']);

    harness!.api({
      'POST /api/ai/tags': jsonReply(SUGGESTION),
      'GET /api/projects/7/article-plan/tags': jsonReply([]),
    });
    queueQuickPick(pickFirst);
    queueQuickPick(() => []);
    await harness!.run('letsBlog.suggestTags');
    expect(editor.text).toBe(ARTICLE);
  });

  it('プロバイダーの選択をやめたら何も送らない。失敗は知らせる', async () => {
    await start();
    await harness!.run('letsBlog.suggestTags');
    expect(harness!.requests('POST /api/ai/tags')).toHaveLength(0);

    harness!.api({
      'POST /api/ai/tags': { status: 403, body: 'forbidden' },
      'GET /api/projects/7/article-plan/tags': { status: 403, body: 'forbidden' },
    });
    queueQuickPick(pickFirst);
    await harness!.run('letsBlog.suggestTags');
    expect(shownErrors[0]).toContain('タグ提案に失敗しました');
    // 並行して投げた 2 つの問い合わせの片方が先に失敗しても、もう片方が届き終わるまで待つ。
    await waitFor(() => harness!.requests('POST /api/ai/tags').length === 1);
  });
});

describe('公開予定日時', () => {
  const futureDate = '2099-01-02';

  it('日付と時刻を選ぶと、UTC の ISO 8601 で front matter へ書き込む', async () => {
    const editor = (await start())!;
    queueQuickPick(pickFirst);
    queueQuickPick((items) => {
      expect(items.slice(0, 2).map((i: { description: string }) => i.description)).toEqual(['今日', '明日']);
      expect(items).toHaveLength(15);
      return items[13];
    });
    queueQuickPick((items) => items.find((i: { label: string }) => i.label === '18:00'));

    await harness!.run('letsBlog.schedulePublication');

    expect(editor.text).toMatch(/publish_scheduled_at: '?\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00\.000Z'?/);
    expect(shownInformations[0]).toContain('公開予定日時を');
    expect(shownInformations[0]).toContain('本番サイトへ投稿したときに有効になります');
  });

  it('その他の日付・時刻は入力欄で受け取り、書式を検証する', async () => {
    const editor = (await start())!;
    queueQuickPick(pickFirst);
    queueQuickPick((items) => items[items.length - 1]);
    queueInputBox((options) => {
      expect(options.validateInput('2099/01/02')).toBe('YYYY-MM-DD 形式で入力してください。');
      expect(options.validateInput(futureDate)).toBeUndefined();
      return futureDate;
    });
    queueQuickPick((items) => items[items.length - 1]);
    queueInputBox((options) => {
      expect(options.validateInput('25:00')).toBe('HH:MM 形式で入力してください。');
      expect(options.validateInput('09:30')).toBeUndefined();
      return '09:30';
    });

    await harness!.run('letsBlog.schedulePublication');

    expect(editor.text).toContain('publish_scheduled_at');
    expect(inputBoxCalls).toHaveLength(2);
  });

  it('設定済みなら現在値を示し、解除できる', async () => {
    const editor = (await start('---\ntitle: t\npublish_scheduled_at: \'2099-01-01T00:00:00.000Z\'\n---\n本文\n'))!;
    queueQuickPick((items, options) => {
      expect(options.placeHolder).toContain('現在の設定');
      return items.find((i: { value: string }) => i.value === 'clear');
    });

    await harness!.run('letsBlog.schedulePublication');

    expect(editor.text).not.toContain('publish_scheduled_at');
    expect(shownInformations).toEqual(['公開予定日時を解除しました。']);
  });

  it('途中でやめたら何も書き換えない', async () => {
    const editor = (await start())!;
    const last = (items: any[]): unknown => items[items.length - 1];

    // 操作の選択でやめる。
    await harness!.run('letsBlog.schedulePublication');
    // 日付の選択でやめる。
    queueQuickPick(pickFirst);
    await harness!.run('letsBlog.schedulePublication');
    // 時刻の選択でやめる。
    queueQuickPick(pickFirst);
    queueQuickPick(pickFirst);
    await harness!.run('letsBlog.schedulePublication');
    // 日付の入力でやめる。
    queueQuickPick(pickFirst);
    queueQuickPick(last);
    queueInputBox(() => undefined);
    await harness!.run('letsBlog.schedulePublication');
    // 時刻の入力でやめる。
    queueQuickPick(pickFirst);
    queueQuickPick(pickFirst);
    queueQuickPick(last);
    queueInputBox(() => undefined);
    await harness!.run('letsBlog.schedulePublication');

    expect(editor.text).toBe(ARTICLE);
    expect(shownErrors).toEqual([]);
  });

  it('日時として解釈できない入力と、過去の日時はエラーにする', async () => {
    const editor = (await start())!;
    queueQuickPick(pickFirst);
    queueQuickPick((items) => items[items.length - 1]);
    queueInputBox(() => '9999-99-99');
    queueQuickPick(pickFirst);
    await harness!.run('letsBlog.schedulePublication');
    expect(shownErrors[0]).toBe('日時として解釈できません: 9999-99-99 09:00');

    queueQuickPick(pickFirst);
    queueQuickPick((items) => items[items.length - 1]);
    queueInputBox(() => '2000-01-01');
    queueQuickPick(pickFirst);
    await harness!.run('letsBlog.schedulePublication');
    expect(shownErrors).toHaveLength(2);
    expect(editor.text).toBe(ARTICLE);
  });

  it('front matter が壊れていれば設定の失敗として知らせる', async () => {
    await start(`---\ntitle: [schedule\n---\n本文\n`);

    await harness!.run('letsBlog.schedulePublication');

    expect(shownErrors[0]).toContain('公開予定日時の設定に失敗しました');
  });
});

describe('クイックフィックス', () => {
  const uri = { fsPath: '/workspace/articles/my-article/article.md', toString: () => 'file:///workspace/articles/my-article/article.md' };

  it('fixInvalidStatus は有効なステータスから選び直させ、記事へ書き込む', async () => {
    const editor = new FakeEditor('---\ntitle: t\nstatus: bogus\n---\n本文\n');
    await start(null);
    setShowTextDocumentResolver(() => editor);
    harness!.api({ 'GET /api/metadata/post-statuses': jsonReply([{ value: 'draft', label: '下書き' }, { value: 'publish', label: '公開' }]) });
    queueQuickPick((items) => items.find((i) => i.value === 'publish'));

    await harness!.run('letsBlog.fixInvalidStatus', uri);

    expect(editor.text).toContain('status: publish');
    expect(openedDocuments).toContain('/workspace/articles/my-article/article.md');
  });

  it('fixInvalidStatus は、選択をやめたら書き換えず、取得に失敗したら知らせる', async () => {
    const editor = new FakeEditor('---\ntitle: t\nstatus: bogus\n---\n本文\n');
    await start(null);
    setShowTextDocumentResolver(() => editor);
    harness!.api({ 'GET /api/metadata/post-statuses': jsonReply([{ value: 'draft', label: '下書き' }]) });

    await harness!.run('letsBlog.fixInvalidStatus', uri);
    expect(editor.text).toContain('status: bogus');

    harness!.api({ 'GET /api/metadata/post-statuses': { status: 403, body: 'x' } });
    harness!.context.secretValues.clear();
    await harness!.run('letsBlog.fixInvalidStatus', uri);
    expect(shownErrors[0]).toContain('ステータスの修正に失敗しました');
  });

  it('removeInvalidCategory は指定のカテゴリだけを取り除く(カテゴリが無ければ空になる)', async () => {
    const editor = new FakeEditor('---\ntitle: t\ncategories:\n  - a\n  - news\n---\n本文\n');
    await start(null);
    setShowTextDocumentResolver(() => editor);

    await harness!.run('letsBlog.removeInvalidCategory', uri, 'news');
    expect(editor.text).toContain('- a');
    expect(editor.text).not.toContain('news');

    const without = new FakeEditor('---\ntitle: t\n---\n本文\n');
    setShowTextDocumentResolver(() => without);
    await harness!.run('letsBlog.removeInvalidCategory', uri, 'news');
    expect(shownErrors).toEqual([]);
  });

  it('removeInvalidCategory は、記事を開けなければ失敗として知らせる', async () => {
    await start(null);
    setShowTextDocumentResolver(() => {
      throw new Error('開けない');
    });

    await harness!.run('letsBlog.removeInvalidCategory', uri, 'news');

    expect(shownErrors[0]).toContain('カテゴリの削除に失敗しました');
  });
});

describe('校正とチェックリスト', () => {
  const STEPS = ['JAPANESE', 'PROOFREADING', 'FACT_CHECK', 'READER_PERSPECTIVE', 'STYLE'];
  const reviewTable = (): Record<string, ReturnType<typeof jsonReply>> => ({
    'GET /api/metadata/post-statuses': jsonReply([{ value: 'draft', label: '下書き' }]),
    ...Object.fromEntries(
      STEPS.map((step) => [`POST /api/projects/7/ai/review-steps/${step}/suggestions`, jsonReply({ suggestions: [], skipped: false })])
    ),
  });

  it('proofreadNow は5ステップのレビューをサーバーへ依頼する', async () => {
    await start();
    harness!.api(reviewTable());

    await harness!.run('letsBlog.proofreadNow');

    expect(harness!.server.recorded.filter((r) => r.path.includes('review-steps'))).toHaveLength(5);
    expect(shownErrors).toEqual([]);
  });

  it('proofreadNow の失敗は、校正チェックの失敗として知らせる', async () => {
    await start();
    harness!.api({ ...reviewTable(), ...Object.fromEntries(STEPS.map((step) => [`POST /api/projects/7/ai/review-steps/${step}/suggestions`, { status: 403, body: 'x' }])) });

    await harness!.run('letsBlog.proofreadNow');

    expect(shownErrors[0]).toContain('校正チェックに失敗しました');
  });

  it('reviewChecklist は、この記事の指摘チェックリストをパネルで開く', async () => {
    await start();

    await harness!.run('letsBlog.reviewChecklist');

    expect(lastCreatedWebviewPanel).toBeDefined();
    expect(lastCreatedWebviewPanel?.webview.html).toContain('reviewChecklist');
  });
});

describe('パネルを開くコマンド', () => {
  const htmlOfPanel = (): string => lastCreatedWebviewPanel?.webview.html ?? '';
  const NO_PROJECT_MESSAGE = 'プロジェクトが未選択です。front matterのproject_id、または「Let\'s Blog: Select Project」で設定してください。';
  /**
   * 壊れた front matter。gray-matter は同じ文字列の 2 回目以降の解析を(失敗せず)空として返すため、
   * テストごとに文字列を変えて、毎回解析に失敗する状況を作る。
   */
  let brokenCount = 0;
  const broken = (): string => `---\ntitle: [broken${(brokenCount += 1)}\n---\n本文\n`;

  it.each([
    ['letsBlog.generateImage', 'imageGen'],
    ['letsBlog.imageGallery', 'imageGallery'],
    ['letsBlog.addNewDiagram', 'diagramEditor'],
    ['letsBlog.diagramGallery', 'diagramGallery'],
  ])('%s は、選択中のプロジェクトでパネルを開く', async (command, asset) => {
    await start();

    await harness!.run(command);

    expect(htmlOfPanel()).toContain(asset);
    expect(shownErrors).toEqual([]);
  });

  it.each([
    'letsBlog.generateImage',
    'letsBlog.imageGallery',
    'letsBlog.addNewDiagram',
    'letsBlog.diagramGallery',
  ])('%s は、プロジェクトが分からなければ案内して開かない', async (command) => {
    await start(ARTICLE, { projectId: null });

    await harness!.run(command);

    expect(shownErrors).toEqual([NO_PROJECT_MESSAGE]);
    expect(lastCreatedWebviewPanel).toBeUndefined();
  });

  it('front matter の project_id は選択中のプロジェクトより優先される(画像生成)', async () => {
    await start('---\ntitle: t\nproject_id: 99\n---\n本文\n', { projectId: null });
    harness!.api({ 'GET /api/ai/image-options': jsonReply({}) });

    await harness!.run('letsBlog.generateImage');
    lastCreatedWebviewPanel?.webview.postMessageToExtension({ command: 'loadOptions' });
    await waitFor(() => harness!.requests('GET /api/ai/image-options').length === 1);

    expect(harness!.requests('GET /api/ai/image-options')[0].path).toBe('/api/ai/image-options?projectId=99');
  });

  it.each([
    ['letsBlog.generateImage', '画像生成パネルの起動に失敗しました'],
    ['letsBlog.imageGallery', '画像ギャラリーの起動に失敗しました'],
    ['letsBlog.addNewDiagram', 'ダイアグラムエディタの起動に失敗しました'],
    ['letsBlog.diagramGallery', 'ダイアグラムギャラリーの起動に失敗しました'],
    ['letsBlog.generateSection', 'セクション生成パネルの起動に失敗しました'],
  ])('%s は、front matter が壊れていれば起動の失敗として知らせる', async (command, message) => {
    await start(broken());

    await harness!.run(command);

    expect(shownErrors[0]).toContain(message);
  });

  it('generateSection はカーソル位置の見出しの文脈でパネルを開く(題名が文字列でなくても開ける)', async () => {
    const editor = (await start('---\ntitle: 記事\n---\n## 背景\n本文\n'))!;
    editor.setCursorLine(4);

    await harness!.run('letsBlog.generateSection');
    expect(htmlOfPanel()).toContain('sectionGen');

    lastCreatedWebviewPanel?.fireDispose();
    harness!.open(new FakeEditor('---\ntitle: 123\n---\n本文\n'));
    await harness!.run('letsBlog.generateSection');
    expect(htmlOfPanel()).toContain('sectionGen');
  });

  it('askAiSearch は Ask AI パネルを開く。パネルを作れなければ失敗として知らせる', async () => {
    await start();
    await harness!.run('letsBlog.askAiSearch');
    expect(htmlOfPanel()).toContain('askAi');

    lastCreatedWebviewPanel?.fireDispose();
    jest.spyOn(vscodeStub.window, 'createWebviewPanel').mockImplementation(() => {
      throw new Error('Webviewを作れない');
    });
    await harness!.run('letsBlog.askAiSearch');
    expect(shownErrors[0]).toContain('Ask AIパネルの起動に失敗しました');
  });

  describe('editDiagram', () => {
    const DIAGRAM_ARTICLE = '---\ntitle: t\n---\n![構成図](assets/diagram-3-1700000000000.svg)\n';

    it('カーソル行の参照するダイアグラムを取得して、編集モードのエディタを開く', async () => {
      const editor = (await start(DIAGRAM_ARTICLE))!;
      editor.setCursorLine(3);
      harness!.api({ 'GET /api/diagrams/3': jsonReply({ id: 3, name: '構成図', xml: '<mxfile/>', svg: '<svg/>' }) });

      await harness!.run('letsBlog.editDiagram');
      lastCreatedWebviewPanel?.webview.postMessageToExtension({ command: 'ready' });
      await waitFor(() => (lastCreatedWebviewPanel?.webview.posted.length ?? 0) > 0);

      expect(lastCreatedWebviewPanel?.webview.posted[0]).toMatchObject({
        command: 'init',
        payload: expect.objectContaining({ mode: 'edit', name: '構成図', xml: '<mxfile/>' }),
      });
    });

    it('参照の無い行・プロジェクト不明・取得失敗は、それぞれ案内する', async () => {
      const editor = (await start(DIAGRAM_ARTICLE, { projectId: null }))!;
      editor.setCursorLine(2);
      await harness!.run('letsBlog.editDiagram');
      expect(shownErrors).toEqual(['カーソル行にダイアグラム参照が見つかりません。']);

      shownErrors.length = 0;
      editor.setCursorLine(3);
      await harness!.run('letsBlog.editDiagram');
      expect(shownErrors).toEqual([NO_PROJECT_MESSAGE]);

      shownErrors.length = 0;
      await harness!.context.workspaceState.update('letsBlog.projectId', 7);
      harness!.api({ 'GET /api/diagrams/3': { status: 404, body: 'none' } });
      await harness!.run('letsBlog.editDiagram');
      expect(shownErrors[0]).toContain('ダイアグラムの読み込みに失敗しました');
    });
  });
});

describe('記事作成・プロジェクト選択', () => {
  it('createArticle は、ログイン済みなら記事作成パネルを開き、未ログインなら失敗として知らせる', async () => {
    await start(null);
    await harness!.run('letsBlog.createArticle');
    expect(lastCreatedWebviewPanel?.webview.html).toContain('articleCreation');

    lastCreatedWebviewPanel?.fireDispose();
    harness!.context.secretValues.clear();
    await harness!.run('letsBlog.createArticle');
    expect(shownErrors[0]).toContain('記事作成パネルの起動に失敗しました');
  });

  it('planArticle は、ログイン・プロジェクトのどちらかが欠けていれば案内し、揃えば開く', async () => {
    await start(null, { login: false });
    await harness!.run('letsBlog.planArticle');
    expect(shownErrors).toEqual(["先に「Let's Blog: Login」でログインしてください。"]);

    await stop();
    await start(null, { projectId: null });
    await harness!.run('letsBlog.planArticle');
    expect(shownErrors).toEqual(["先に「Let's Blog: Select Project」でプロジェクトを選択してください。"]);

    await stop();
    await start(null);
    await harness!.run('letsBlog.planArticle');
    expect(lastCreatedWebviewPanel?.webview.html).toContain('plan');
  });

  it('selectProject は GitHub 連携済みのプロジェクトから選ばせ、既定のプロジェクトにする', async () => {
    await start(null, { projectId: 2 });
    harness!.api({
      'GET /api/projects': jsonReply([
        { id: 1, name: 'A', slug: 'a', githubRepository: 'o/a' },
        { id: 2, name: 'B', slug: 'b', githubRepository: 'o/b' },
        { id: 3, name: 'C', slug: 'c', githubRepository: null },
      ]),
    });
    queueQuickPick((items) => items.find((i: { label: string }) => i.label === 'A'));

    await harness!.run('letsBlog.selectProject');

    expect(quickPickCalls[0].items.map((i: { description: string }) => i.description)).toEqual(['o/a', 'o/b (現在選択中)']);
    expect(getProjectId(harness!.context)).toBe(1);
    expect(shownInformations).toEqual(["プロジェクトを 'A' に設定しました。"]);
  });

  it('selectProject は、連携済みが無ければ警告し、選択をやめたら変えず、失敗は知らせる', async () => {
    await start(null, { projectId: 2 });
    harness!.api({ 'GET /api/projects': jsonReply([{ id: 3, name: 'C', slug: 'c', githubRepository: null }]) });
    await harness!.run('letsBlog.selectProject');
    expect(shownWarnings[0]).toContain('GitHub連携済みのプロジェクトがありません');

    api.clearResponseCache();
    harness!.api({ 'GET /api/projects': jsonReply([{ id: 1, name: 'A', slug: 'a', githubRepository: 'o/a' }]) });
    await harness!.run('letsBlog.selectProject');
    expect(getProjectId(harness!.context)).toBe(2);

    api.clearResponseCache();
    harness!.api({ 'GET /api/projects': { status: 403, body: 'x' } });
    await harness!.run('letsBlog.selectProject');
    expect(shownErrors[0]).toContain('プロジェクト選択に失敗しました');
  });
});

async function stop(): Promise<void> {
  await harness?.stop();
  harness = undefined;
}

describe('createArticleWithoutAi', () => {
  let workspaceRoot: string;

  beforeEach(() => {
    workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-noai-'));
    setWorkspaceFolders([{ uri: { fsPath: workspaceRoot } }]);
  });

  afterEach(() => {
    fs.rmSync(workspaceRoot, { recursive: true, force: true });
  });

  /** setWorkspaceFolders は resetMocks で消えるため、拡張を起動してから設定し直す。 */
  async function startWithWorkspace(options: Parameters<typeof startExtension>[0] = {}): Promise<void> {
    await start(null, options);
    setWorkspaceFolders([{ uri: { fsPath: workspaceRoot } }]);
  }

  it('タイトルとスラッグの入力だけで記事を作り、開いて知らせる(スラッグはタイトルから提案する)', async () => {
    await startWithWorkspace();
    harness!.api({ 'GET /api/projects/7/article-plan/categories/hierarchy': jsonReply([]) });
    queueInputBox((options) => {
      expect(options.validateInput(' ')).toBe('タイトルは必須です。');
      expect(options.validateInput('題')).toBeUndefined();
      return 'My First Post';
    });
    queueInputBox((options) => {
      expect(options.value).toBe('my-first-post');
      expect(options.validateInput(' ')).toBe('スラッグは必須です。');
      expect(options.validateInput('Bad Slug')).toBe('スラッグは半角英数字とハイフンのみで入力してください(先頭は英数字)。');
      expect(options.validateInput('good-slug')).toBeUndefined();
      return ' my-first-post ';
    });

    await harness!.run('letsBlog.createArticleWithoutAi');

    const articlePath = path.join(workspaceRoot, 'articles', 'my-first-post', 'article.md');
    expect(fs.readFileSync(articlePath, 'utf-8')).toContain('title: My First Post');
    expect(openedDocuments).toEqual([articlePath, articlePath]);
    expect(shownInformations).toEqual(['articles/my-first-post/article.md を作成しました。']);
  });

  it('既存カテゴリがあれば複数選択させ、選んだものを front matter に入れる', async () => {
    await startWithWorkspace();
    harness!.api({
      'GET /api/projects/7/article-plan/categories/hierarchy': jsonReply([
        { name: '子', parentName: '親' },
        { name: '親', parentName: null },
      ]),
    });
    queueInputBox(() => 'タイトル');
    queueInputBox(() => 'title-slug');
    queueQuickPick((items) => {
      expect(items).toEqual([
        { label: '子', description: '親: 親' },
        { label: '親', description: undefined },
      ]);
      return [items[0]];
    });

    await harness!.run('letsBlog.createArticleWithoutAi');

    const text = fs.readFileSync(path.join(workspaceRoot, 'articles', 'title-slug', 'article.md'), 'utf-8');
    expect(text).toContain('- 子');
    expect(text).not.toContain('- 親');
  });

  it('カテゴリを選ばずに閉じても、カテゴリなしで作成を続ける', async () => {
    await startWithWorkspace();
    harness!.api({ 'GET /api/projects/7/article-plan/categories/hierarchy': jsonReply([{ name: '子', parentName: null }]) });
    queueInputBox(() => 'タイトル');
    queueInputBox(() => 'title-slug');

    await harness!.run('letsBlog.createArticleWithoutAi');

    const text = fs.readFileSync(path.join(workspaceRoot, 'articles', 'title-slug', 'article.md'), 'utf-8');
    expect(text).not.toContain('categories');
  });

  it('未ログインやカテゴリ取得の失敗は静かに無視して、作成を続ける', async () => {
    await startWithWorkspace({ login: false });
    queueInputBox(() => 'タイトル');
    queueInputBox(() => 'slug-a');
    await harness!.run('letsBlog.createArticleWithoutAi');
    expect(fs.existsSync(path.join(workspaceRoot, 'articles', 'slug-a', 'article.md'))).toBe(true);

    await stop();
    await startWithWorkspace();
    harness!.api({ 'GET /api/projects/7/article-plan/categories/hierarchy': { status: 403, body: 'x' } });
    queueInputBox(() => 'タイトル');
    queueInputBox(() => 'slug-b');
    await harness!.run('letsBlog.createArticleWithoutAi');
    expect(fs.existsSync(path.join(workspaceRoot, 'articles', 'slug-b', 'article.md'))).toBe(true);
    expect(shownErrors).toEqual([]);
  });

  it('入力をやめたり、上書きを断ったりしたら、何も作らない', async () => {
    await startWithWorkspace();
    await harness!.run('letsBlog.createArticleWithoutAi');
    queueInputBox(() => 'タイトル');
    await harness!.run('letsBlog.createArticleWithoutAi');
    expect(fs.existsSync(path.join(workspaceRoot, 'articles'))).toBe(false);

    fs.mkdirSync(path.join(workspaceRoot, 'articles', 'taken'), { recursive: true });
    setWarningResponse('No');
    harness!.api({ 'GET /api/projects/7/article-plan/categories/hierarchy': jsonReply([]) });
    queueInputBox(() => 'タイトル');
    queueInputBox(() => 'taken');
    await harness!.run('letsBlog.createArticleWithoutAi');
    expect(fs.existsSync(path.join(workspaceRoot, 'articles', 'taken', 'article.md'))).toBe(false);
    expect(shownInformations).toEqual([]);
  });

  it('ワークスペースもプロジェクトも無ければ、作成の失敗として知らせる', async () => {
    await start(null, { projectId: null });
    setWorkspaceFolders(undefined);
    await harness!.run('letsBlog.createArticleWithoutAi');
    expect(shownErrors[0]).toContain('記事の作成に失敗しました');
    expect(shownErrors[0]).toContain('ワークスペースフォルダが開かれていません');

    shownErrors.length = 0;
    setWorkspaceFolders([{ uri: { fsPath: workspaceRoot } }]);
    await harness!.run('letsBlog.createArticleWithoutAi');
    expect(shownErrors[0]).toContain('Select Project');
  });
});

describe('使われていない補助の確認', () => {
  it('外部ブラウザで開いた URL の記録は、このファイルのどのテストでも空のまま', async () => {
    await start(null);
    expect(openedExternalUrls).toEqual([]);
  });
});
