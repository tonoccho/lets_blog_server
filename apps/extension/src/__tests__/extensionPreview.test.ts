import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {
  allWebviewPanels,
  executedCommands,
  lastCreatedWebviewPanel,
  progressRuns,
  queueQuickPick,
  queueWarningResponse,
  shownErrors,
  shownWarnings,
} from '../__mocks__/vscode';
import { clearResponseCache } from '../apiClient';
import { Harness, startExtension } from './support/extensionHarness';
import { FakeEditor } from './support/fakeEditor';
import { jsonReply } from './support/localHttpServer';
import { waitFor } from './support/panelKit';
import { findingFor, reviewRoutes } from './support/reviewRoutes';

/**
 * 記事のプレビュー(previewArticle)。レビュー・Markdown の変換・署名付き URL の発行は実サーバー(gateway)、
 * VS Code(プレビューのパネル・QuickPick・通知)は代替。画像は一時ディレクトリの実ファイル。
 */
let harness: Harness | undefined;
let articleDir: string;

const SITE = (id: number, name: string) => ({ id, name, siteKey: `k${id}` });
const PROJECT = {
  id: 7,
  name: 'P',
  slug: 'p',
  localSite: SITE(1, 'ローカル'),
  testSite: SITE(2, 'テスト'),
  productionSite: SITE(3, '本番'),
  masterEnvironment: 'production',
};
const RENDER = 'POST /api/projects/7/preview/render';
const SIGNED = 'POST /api/projects/7/preview/signed-url';

beforeEach(() => {
  articleDir = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-preview-'));
  fs.mkdirSync(path.join(articleDir, 'assets'));
  fs.writeFileSync(path.join(articleDir, 'assets', 'a.png'), 'PNG');
  fs.writeFileSync(path.join(articleDir, 'assets', 'eye.png'), 'EYE');
  fs.writeFileSync(path.join(articleDir, 'assets', 'x.xyz'), 'XYZ');
});

afterEach(async () => {
  await harness?.stop();
  harness = undefined;
  fs.rmSync(articleDir, { recursive: true, force: true });
});

async function start(text: string, options: Parameters<typeof startExtension>[0] = {}): Promise<FakeEditor> {
  harness = await startExtension(options);
  return harness.open(new FakeEditor(text, path.join(articleDir, 'article.md')))!;
}

function serve(project: unknown = PROJECT, extra: Record<string, ReturnType<typeof jsonReply>> = {}): void {
  harness!.api({
    ...reviewRoutes(7),
    'GET /api/projects/7': jsonReply(project),
    [RENDER]: jsonReply({ html: '<p>変換後</p>' }),
    [SIGNED]: jsonReply({ url: 'https://blog.test/?preview=1', expiresAt: 1 }),
    ...extra,
  });
}

const html = (): string => lastCreatedWebviewPanel?.webview.html ?? '';
const ARTICLE = '---\ntitle: 記事\n---\n本文です。\n';

describe('commandPreviewArticle', () => {
  it('サイトが 1 つなら選ばずに、実サイトのプレビュー URL を iframe で表示する', async () => {
    await start(ARTICLE);
    serve({ ...PROJECT, localSite: null, testSite: null });

    await harness!.run('letsBlog.previewArticle');

    expect(html()).toContain('src="https://blog.test/?preview=1"');
    expect(lastCreatedWebviewPanel?.title).toBe('Article Preview (本番 / 本番)');
    expect(html()).not.toContain('<select');
    expect(JSON.parse(harness!.requests(RENDER)[0].body)).toEqual({ markdown: '本文です。\n' });
    expect(JSON.parse(harness!.requests(SIGNED)[0].body)).toEqual({
      siteId: 3,
      title: '記事',
      contentHtml: '<p>変換後</p>',
    });
    expect(shownErrors).toEqual([]);
    expect(progressRuns.map((r) => r.options.title)).toContain('プレビューを生成しています…');
  });

  it('サイトが複数なら選ばせ、選んだサイトを表示する。パネルには切り替えのセレクトが付く', async () => {
    await start(ARTICLE);
    serve();
    queueQuickPick((items) => items.find((i: { label: string }) => i.label === 'テスト'));

    await harness!.run('letsBlog.previewArticle');

    expect(JSON.parse(harness!.requests(SIGNED)[0].body).siteId).toBe(2);
    expect(html()).toContain('<select id="letsblog-site-switcher">');
  });

  it('サイトを選ばなければ、プレビューを作らない', async () => {
    await start(ARTICLE);
    serve();

    await harness!.run('letsBlog.previewArticle');

    expect(harness!.requests(RENDER)).toHaveLength(0);
    expect(lastCreatedWebviewPanel).toBeUndefined();
  });

  it('プロジェクトにサイトが無ければ、サイトを紐づける案内を表示する', async () => {
    await start(ARTICLE);
    serve({ ...PROJECT, localSite: null, testSite: null, productionSite: null });

    await harness!.run('letsBlog.previewArticle');

    expect(html()).toContain('サイトが紐づいていません');
    expect(harness!.requests(RENDER)).toHaveLength(0);
  });

  it('プロジェクトが分からなければ案内する(front matter の project_id が優先される)', async () => {
    await start(ARTICLE, { projectId: null });
    await harness!.run('letsBlog.previewArticle');
    expect(shownErrors[0]).toContain('プロジェクトが未選択です');
  });

  it('本文の画像とアイキャッチは data URI にして送り、存在しない画像・種別不明の画像はそのままにする', async () => {
    await start(
      [
        '---',
        'title: 記事',
        'featured_image: assets/eye.png',
        '---',
        '![a](assets/a.png)',
        '![none](assets/none.png)',
        '![x](assets/x.xyz)',
        '',
      ].join('\n'),
      {}
    );
    serve({ ...PROJECT, localSite: null, testSite: null });

    await harness!.run('letsBlog.previewArticle');

    const markdown = JSON.parse(harness!.requests(RENDER)[0].body).markdown as string;
    expect(markdown).toContain(`data:image/png;base64,${Buffer.from('PNG').toString('base64')}`);
    expect(markdown).toContain('assets/none.png');
    expect(markdown).toContain('assets/x.xyz');
    expect(JSON.parse(harness!.requests(SIGNED)[0].body).featuredImageDataUri).toBe(
      `data:image/png;base64,${Buffer.from('EYE').toString('base64')}`
    );
  });

  it('アイキャッチの画像ファイルが無ければ、アイキャッチなしで送る', async () => {
    await start('---\ntitle: 記事\nfeatured_image: assets/none.png\n---\n本文\n');
    serve({ ...PROJECT, localSite: null, testSite: null });

    await harness!.run('letsBlog.previewArticle');

    expect(JSON.parse(harness!.requests(SIGNED)[0].body)).not.toHaveProperty('featuredImageDataUri');
  });

  it('題名の無い記事も、題名を空にしてプレビューできる', async () => {
    await start('---\nslug: x\n---\n本文\n');
    serve({ ...PROJECT, localSite: null, testSite: null });

    await harness!.run('letsBlog.previewArticle');

    expect(JSON.parse(harness!.requests(SIGNED)[0].body).title).toBe('(無題)');
  });

  it('プラグインが使えないサイトでは、導入の案内を表示する', async () => {
    await start(ARTICLE);
    serve({ ...PROJECT, localSite: null, testSite: null }, {
      [SIGNED]: jsonReply({ error: '未導入', details: { code: 'LETSBLOG_PLUGIN_NOT_INSTALLED' } }, 409),
    });

    await harness!.run('letsBlog.previewArticle');

    expect(html()).toContain('導入されていません');
    expect(html()).not.toContain('<iframe');
  });

  it('生成に失敗したら、プレビューの失敗として知らせる', async () => {
    await start(ARTICLE);
    serve({ ...PROJECT, localSite: null, testSite: null }, { [RENDER]: { status: 403, body: 'forbidden' } });

    await harness!.run('letsBlog.previewArticle');

    expect(shownErrors[0]).toContain('プレビューの生成に失敗しました');
  });

  describe('環境の切り替え', () => {
    async function openWithSwitcher(): Promise<void> {
      await start(ARTICLE);
      serve();
      queueQuickPick((items) => items.find((i: { label: string }) => i.label === '本番'));
      await harness!.run('letsBlog.previewArticle');
    }

    it('パネルで環境を切り替えると、その環境のプレビューを取得し直す', async () => {
      await openWithSwitcher();
      const panel = lastCreatedWebviewPanel!;
      harness!.api({
        ...reviewRoutes(7),
        'GET /api/projects/7': jsonReply(PROJECT),
        [RENDER]: jsonReply({ html: '<p>変換後</p>' }),
        [SIGNED]: jsonReply({ url: 'https://test.blog.test/?preview=2', expiresAt: 1 }),
      });

      panel.webview.postMessageToExtension({ type: 'switchSite', siteId: 2 });
      await waitFor(() => panel.webview.html.includes('https://test.blog.test/?preview=2'));

      expect(JSON.parse(harness!.requests(SIGNED).at(-1)!.body).siteId).toBe(2);
      expect(progressRuns.at(-1)?.options.title).toBe('テスト のプレビューを生成しています…');
    });

    it('パネルが未知のメッセージを送ってきても、何も取得しない', async () => {
      await openWithSwitcher();
      const before = harness!.requests(SIGNED).length;

      lastCreatedWebviewPanel!.webview.postMessageToExtension({ type: 'unknown' });
      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(harness!.requests(SIGNED)).toHaveLength(before);
    });

    it('存在しない環境への切り替えは、何も取得しない', async () => {
      await openWithSwitcher();
      const before = harness!.requests(SIGNED).length;

      lastCreatedWebviewPanel!.webview.postMessageToExtension({ type: 'switchSite', siteId: 999 });
      lastCreatedWebviewPanel!.webview.postMessageToExtension({ type: 'switchSite', siteId: null });
      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(harness!.requests(SIGNED)).toHaveLength(before);
    });
  });

  describe('プレビュー前のレビュー', () => {
    it('未対応の指摘があれば、プレビューは表示したうえでチェックリストへの導線を示す', async () => {
      await start(ARTICLE);
      serve({ ...PROJECT, localSite: null, testSite: null });
      harness!.api({
        ...reviewRoutes(7, { PROOFREADING: findingFor('PROOFREADING', '本文です') }),
        'GET /api/projects/7': jsonReply({ ...PROJECT, localSite: null, testSite: null }),
        [RENDER]: jsonReply({ html: '<p>変換後</p>' }),
        [SIGNED]: jsonReply({ url: 'https://blog.test/?preview=1', expiresAt: 1 }),
      });
      queueWarningResponse('指摘チェックリストを表示');

      await harness!.run('letsBlog.previewArticle');
      await waitFor(() => executedCommands.some((c) => c.command === 'letsBlog.reviewChecklist'));

      expect(shownWarnings[0]).toContain('未対応の指摘が1件あります。プレビューは表示しました。');
      expect(allWebviewPanels().some((p) => p.webview.html.includes('reviewChecklist'))).toBe(true);
    });

    it('導線を選ばなければ、チェックリストは開かない', async () => {
      await start(ARTICLE);
      harness!.api({
        ...reviewRoutes(7, { PROOFREADING: findingFor('PROOFREADING', '本文です') }),
        'GET /api/projects/7': jsonReply({ ...PROJECT, localSite: null, testSite: null }),
        [RENDER]: jsonReply({ html: '<p>変換後</p>' }),
        [SIGNED]: jsonReply({ url: 'https://blog.test/?preview=1', expiresAt: 1 }),
      });

      await harness!.run('letsBlog.previewArticle');
      await new Promise((resolve) => setTimeout(resolve, 50));

      expect(shownWarnings).toHaveLength(1);
      expect(executedCommands.filter((c) => c.command === 'letsBlog.reviewChecklist')).toEqual([]);
    });

    it('レビュー自体が失敗しても、プレビューは止めずに事実だけ知らせる', async () => {
      await start(ARTICLE);
      clearResponseCache();
      harness!.api({
        ...reviewRoutes(7, {
          JAPANESE: { status: 403, body: 'x' },
          PROOFREADING: { status: 403, body: 'x' },
          FACT_CHECK: { status: 403, body: 'x' },
          READER_PERSPECTIVE: { status: 403, body: 'x' },
          STYLE: { status: 403, body: 'x' },
        }),
        'GET /api/projects/7': jsonReply({ ...PROJECT, localSite: null, testSite: null }),
        [RENDER]: jsonReply({ html: '<p>変換後</p>' }),
        [SIGNED]: jsonReply({ url: 'https://blog.test/?preview=1', expiresAt: 1 }),
      });

      await harness!.run('letsBlog.previewArticle');

      expect(shownWarnings[0]).toContain('プレビュー前のレビューに失敗しました');
      expect(html()).toContain('src="https://blog.test/?preview=1"');
    });
  });
});
