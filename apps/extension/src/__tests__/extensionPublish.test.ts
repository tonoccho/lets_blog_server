import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  executedCommands,
  lastCreatedWebviewPanel,
  openedExternalUrls,
  progressRuns,
  queueErrorResponse,
  queueInformationResponse,
  queueQuickPick,
  queueWarningResponse,
  quickPickCalls,
  shownErrors,
  shownInformations,
  shownWarnings,
} from '../__mocks__/vscode';
import { clearResponseCache } from '../apiClient';
import { Harness, startExtension } from './support/extensionHarness';
import { FakeEditor } from './support/fakeEditor';
import { jsonReply } from './support/localHttpServer';
import { findingFor, reviewRoutes } from './support/reviewRoutes';

/**
 * 投稿(publish)と投稿の削除(deletePost)。外部通信(gateway)は実サーバー、VS Code は代替。
 * 画像は一時ディレクトリの実ファイルで、レビュー(5 ステップ)も実サーバーへ依頼する。
 */
let harness: Harness | undefined;
let articleDir: string;

const PROJECT = {
  id: 7,
  name: 'プロジェクト',
  slug: 'p',
  localSite: { id: 1, name: 'ローカル', siteKey: 'local' },
  testSite: { id: 2, name: 'テスト', siteKey: 'test' },
  productionSite: { id: 3, name: '本番', siteKey: 'prod' },
  masterEnvironment: 'production',
};

beforeEach(() => {
  articleDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-publish-'));
  fs.mkdirSync(path.join(articleDir, 'assets'));
  fs.writeFileSync(path.join(articleDir, 'assets', 'a.png'), 'PNGDATA');
  fs.writeFileSync(path.join(articleDir, 'assets', 'eye.png'), 'EYEDATA');
});

afterEach(async () => {
  await harness?.stop();
  harness = undefined;
  fs.rmSync(articleDir, { recursive: true, force: true });
});

function editorFor(text: string): FakeEditor {
  return new FakeEditor(text, path.join(articleDir, 'article.md'));
}

async function start(text: string, options: Parameters<typeof startExtension>[0] = {}): Promise<FakeEditor> {
  harness = await startExtension(options);
  return harness.open(editorFor(text))!;
}

const PUBLISH_PATH = 'POST /api/posts/publish';
const publishedBody = (): string => harness!.requests(PUBLISH_PATH)[0].body;
const publishedReply = (status = 'draft'): ReturnType<typeof jsonReply> =>
  jsonReply({ wpPostId: '55', wpPostUrl: 'https://blog.test/?p=55', status });

/** レビューと投稿先の環境を用意する(既定は 3 環境つきのプロジェクト)。 */
function serve(extra: Record<string, ReturnType<typeof jsonReply>> = {}, project: unknown = PROJECT): void {
  harness!.api({
    ...reviewRoutes(7),
    'GET /api/projects/7': jsonReply(project),
    [PUBLISH_PATH]: publishedReply(),
    ...extra,
  });
}

const pickEnvironment = (label: string): void => {
  queueQuickPick((items) => items.find((i: { label: string }) => i.label === label));
};

const ARTICLE = '---\ntitle: 記事\nslug: my-post\nstatus: draft\n---\n本文です。\n';

describe('commandPublish', () => {
  it('レビューを通ったら環境を選ばせ、本番へは front matter の status で投稿し、結果の status を書き戻す', async () => {
    const editor = await start(ARTICLE);
    serve({ 'GET /api/posts/prod/by-slug/my-post': { status: 404, body: '{}' } });
    pickEnvironment('本番');
    queueInformationResponse('開く');

    await harness!.run('letsBlog.publish');

    expect(quickPickCalls[0].items.map((i: { label: string }) => i.label)).toEqual(['ローカル', 'テスト', '本番']);
    expect(harness!.requests(PUBLISH_PATH)).toHaveLength(1);
    expect(publishedBody()).toContain('name="site"');
    expect(publishedBody()).toContain('prod');
    expect(publishedBody()).toMatch(/name="status"\r\n\r\ndraft/);
    expect(publishedBody()).not.toContain('name="wpPostId"');
    expect(progressRuns.at(-1)?.reports[0].message).toBe('本文を送信しています…');
    expect(editor.text).toContain('status: draft');
    expect(shownInformations).toEqual(['投稿しました(status: draft)']);
    expect(openedExternalUrls).toEqual(['https://blog.test/?p=55']);
  });

  it('ローカルは動作確認用に即公開し、既存の投稿があればそのIDで更新する', async () => {
    const editor = await start(ARTICLE);
    serve({
      'GET /api/posts/local/by-slug/my-post': jsonReply({ wpPostId: '55', status: 'draft' }),
      [PUBLISH_PATH]: publishedReply('publish'),
    });
    pickEnvironment('ローカル');

    await harness!.run('letsBlog.publish');

    expect(publishedBody()).toMatch(/name="status"\r\n\r\npublish/);
    expect(publishedBody()).toMatch(/name="wpPostId"\r\n\r\n55/);
    expect(editor.text).toContain('status: publish');
    expect(openedExternalUrls).toEqual([]);
  });

  it('slug が無い記事は既存投稿を照会せず、status が無ければ draft で投稿する', async () => {
    await start('---\ntitle: 記事\n---\n本文です。\n');
    serve();
    pickEnvironment('テスト');

    await harness!.run('letsBlog.publish');

    expect(harness!.server.recorded.filter((r) => r.path.includes('by-slug'))).toEqual([]);
    expect(publishedBody()).toMatch(/name="status"\r\n\r\npublish/);

    harness!.server.recorded.length = 0;
    pickEnvironment('本番');
    await harness!.run('letsBlog.publish');
    expect(publishedBody()).toMatch(/name="status"\r\n\r\ndraft/);
  });

  it('本文の画像とアイキャッチを同梱し、見つからない画像は警告して除く', async () => {
    const text = [
      '---',
      'title: 記事',
      'featured_image: assets/eye.png',
      '---',
      '![a](assets/a.png)',
      '![b](assets/missing.png)',
      '![eye](assets/eye.png)',
      '',
    ].join('\n');
    await start(text);
    serve();
    pickEnvironment('本番');

    await harness!.run('letsBlog.publish');

    expect(progressRuns.at(-1)?.reports[0].message).toBe('本文と画像2件を送信しています…');
    const body = publishedBody();
    expect(body.match(/name="images"/g)).toHaveLength(2);
    expect(body).toContain('filename="assets/a.png"');
    expect(body).toContain('filename="assets/eye.png"');
    expect(body).toContain('name="featuredImageFilename"');
    expect(shownWarnings).toEqual(['以下の画像ファイルが見つからないため、投稿に含まれません: assets/missing.png']);
  });

  it('アイキャッチが本文に無ければ追加で同梱し、実ファイルが無ければ警告に含めて同梱しない', async () => {
    await start('---\ntitle: 記事\nfeatured_image: assets/eye.png\n---\n本文\n');
    serve();
    pickEnvironment('本番');
    await harness!.run('letsBlog.publish');
    expect(publishedBody().match(/name="images"/g)).toHaveLength(1);
    expect(shownWarnings).toEqual([]);

    harness!.open(editorFor('---\ntitle: 記事\nfeatured_image: assets/none.png\n---\n本文\n'));
    harness!.server.recorded.length = 0;
    pickEnvironment('本番');
    await harness!.run('letsBlog.publish');
    expect(publishedBody()).not.toContain('name="images"');
    expect(shownWarnings).toEqual(['以下の画像ファイルが見つからないため、投稿に含まれません: assets/none.png']);
  });

  it('title が無い記事、書式の不正な公開予定日時は、投稿せずエラーにする', async () => {
    await start('---\nslug: x\n---\n本文\n');
    serve();
    pickEnvironment('本番');
    await harness!.run('letsBlog.publish');
    expect(shownErrors).toEqual(["front matterに 'title' がありません。"]);

    shownErrors.length = 0;
    harness!.open(editorFor("---\ntitle: 記事\npublish_scheduled_at: 'not-a-date'\n---\n本文\n"));
    pickEnvironment('本番');
    await harness!.run('letsBlog.publish');
    expect(shownErrors).toHaveLength(1);
    expect(harness!.requests(PUBLISH_PATH)).toHaveLength(0);
  });

  it('公開予定日時があれば、そのまま同梱する', async () => {
    await start("---\ntitle: 記事\npublish_scheduled_at: '2099-01-01T00:00:00.000Z'\n---\n本文\n");
    serve();
    pickEnvironment('本番');

    await harness!.run('letsBlog.publish');

    expect(publishedBody()).toContain('name="publishScheduledAt"');
    expect(publishedBody()).toContain('2099-01-01T00:00:00.000Z');
  });

  it('未対応の指摘があれば投稿せず、チェックリストへの導線を示す。選ぶとチェックリストを開く', async () => {
    await start(ARTICLE);
    serve();
    harness!.api({ ...reviewRoutes(7, { PROOFREADING: findingFor('PROOFREADING', '本文です') }) });
    queueErrorResponse('指摘チェックリストを表示');

    await harness!.run('letsBlog.publish');

    expect(shownErrors[0]).toContain('未対応の指摘が1件あるため、投稿しませんでした。');
    expect(executedCommands).toContainEqual({ command: 'letsBlog.reviewChecklist', args: [] });
    expect(lastCreatedWebviewPanel?.webview.html).toContain('reviewChecklist');
    expect(harness!.requests(PUBLISH_PATH)).toHaveLength(0);
  });

  it('導線を選ばずに閉じたら、チェックリストは開かない', async () => {
    await start(ARTICLE);
    harness!.api({ ...reviewRoutes(7, { PROOFREADING: findingFor('PROOFREADING', '本文です') }) });

    await harness!.run('letsBlog.publish');

    expect(shownErrors).toHaveLength(1);
    expect(executedCommands.filter((c) => c.command === 'letsBlog.reviewChecklist')).toEqual([]);
  });

  it('プロジェクトが分からない・投稿先サイトが無い・環境を選ばなかった場合は、投稿しない', async () => {
    await start('---\ntitle: 記事\nproject_id: 8\n---\n   \n', { projectId: null });
    harness!.api({ 'GET /api/projects/8': jsonReply({ ...PROJECT, id: 8, localSite: null, testSite: null, productionSite: null }) });
    await harness!.run('letsBlog.publish');
    expect(shownErrors).toEqual(["プロジェクト 'プロジェクト' には投稿先サイトが紐づいていません。"]);

    shownErrors.length = 0;
    harness!.open(editorFor('---\ntitle: 記事\n---\n   \n'));
    await harness!.run('letsBlog.publish');
    expect(shownErrors[0]).toContain('プロジェクトが未選択です');

    shownErrors.length = 0;
    clearResponseCache();
    harness!.api({ 'GET /api/projects/8': jsonReply({ ...PROJECT, id: 8 }) });
    harness!.open(editorFor('---\ntitle: 記事\nproject_id: 8\n---\n   \n'));
    await harness!.run('letsBlog.publish');
    expect(harness!.requests(PUBLISH_PATH)).toHaveLength(0);
    expect(shownErrors).toEqual([]);
  });

  it('投稿の失敗は、投稿の失敗として知らせる', async () => {
    await start(ARTICLE);
    serve({ [PUBLISH_PATH]: { status: 403, body: 'forbidden' }, 'GET /api/posts/prod/by-slug/my-post': { status: 404, body: '{}' } });
    pickEnvironment('本番');

    await harness!.run('letsBlog.publish');

    expect(shownErrors[0]).toContain('投稿に失敗しました');
  });
});

describe('commandDeletePost', () => {
  const SITES = [
    { id: 1, name: 'ローカル', siteKey: 'local' },
    { id: 2, name: '本番', siteKey: 'prod' },
  ];

  it('投稿済みのサイトが 1 つなら確認のうえ削除し、知らせる', async () => {
    await start(ARTICLE);
    harness!.api({
      'GET /api/sites': jsonReply(SITES),
      'GET /api/posts/local/by-slug/my-post': { status: 404, body: '{}' },
      'GET /api/posts/prod/by-slug/my-post': jsonReply({ wpPostId: '55', status: 'publish' }),
      'DELETE /api/posts/prod/55': { status: 204 },
    });
    queueWarningResponse('削除する');

    await harness!.run('letsBlog.deletePost');

    expect(harness!.requests('DELETE /api/posts/prod/55')).toHaveLength(1);
    expect(shownWarnings[0]).toContain("サイト 'prod' の投稿(ID: 55)を削除します");
    expect(shownInformations).toEqual(["サイト 'prod' の投稿を削除しました。"]);
  });

  it('複数のサイトに投稿済みなら削除対象を選ばせる。選ばなければ削除しない', async () => {
    await start(ARTICLE);
    harness!.api({
      'GET /api/sites': jsonReply(SITES),
      'GET /api/posts/local/by-slug/my-post': jsonReply({ wpPostId: '11', status: 'publish' }),
      'GET /api/posts/prod/by-slug/my-post': jsonReply({ wpPostId: '55', status: 'publish' }),
      'DELETE /api/posts/local/11': { status: 204 },
    });
    queueQuickPick(() => undefined);
    await harness!.run('letsBlog.deletePost');
    expect(shownWarnings).toEqual([]);

    queueQuickPick((items) => items.find((i: { label: string }) => i.label === 'local'));
    queueWarningResponse('削除する');
    await harness!.run('letsBlog.deletePost');
    expect(harness!.requests('DELETE /api/posts/local/11')).toHaveLength(1);
  });

  it('確認で断ったら削除しない', async () => {
    await start(ARTICLE);
    harness!.api({
      'GET /api/sites': jsonReply(SITES),
      'GET /api/posts/local/by-slug/my-post': { status: 404, body: '{}' },
      'GET /api/posts/prod/by-slug/my-post': jsonReply({ wpPostId: '55', status: 'publish' }),
    });

    await harness!.run('letsBlog.deletePost');

    expect(harness!.server.recorded.filter((r) => r.method === 'DELETE')).toEqual([]);
    expect(shownInformations).toEqual([]);
  });

  it('どのサイトにも投稿されていない記事、slug の無い記事では、案内する', async () => {
    await start(ARTICLE);
    harness!.api({
      'GET /api/sites': jsonReply(SITES),
      'GET /api/posts/local/by-slug/my-post': { status: 404, body: '{}' },
      'GET /api/posts/prod/by-slug/my-post': { status: 404, body: '{}' },
    });
    await harness!.run('letsBlog.deletePost');
    expect(shownErrors).toEqual(['この記事はまだどのサイトにも投稿されていません。']);

    shownErrors.length = 0;
    harness!.open(editorFor('---\ntitle: 記事\n---\n本文\n'));
    await harness!.run('letsBlog.deletePost');
    expect(shownErrors).toEqual(['この記事はまだどのサイトにも投稿されていません。']);
  });

  it('削除の失敗は、削除の失敗として知らせる', async () => {
    await start(ARTICLE);
    harness!.api({
      'GET /api/sites': { status: 403, body: 'forbidden' },
    });

    await harness!.run('letsBlog.deletePost');

    expect(shownErrors[0]).toContain('投稿の削除に失敗しました');
  });
});
