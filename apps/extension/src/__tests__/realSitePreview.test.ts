import { showRealSitePreview, RealSitePreviewParams } from '../realSitePreview';
import { PreviewPanel } from '../previewPanel';
import { lastCreatedWebviewPanel, resetMocks, setConfiguration } from '../__mocks__/vscode';
import { LocalHttpServer, jsonReply } from './support/localHttpServer';
import { createContext } from './support/extensionContext';

// 外部通信(gateway の署名付きプレビューURL API)だけを実サーバーで受ける。
// apiClient.ts・previewPanel.ts は実物で、開いたパネルの表示内容(vscode スタブのパネル)で確かめる。
let server: LocalHttpServer;

/** プレビューコマンドの表示分岐(issue #1562): サイトの紐付け有無、URL取得の可否、題名のフォールバック。 */
describe('showRealSitePreview', () => {
  const onMessage = jest.fn();
  const availableSites = [{ siteId: 3, label: 'テスト', siteName: 'blog' }];

  function params(overrides: Partial<RealSitePreviewParams> = {}): RealSitePreviewParams {
    return {
      context: createContext(),
      apiKey: 'token',
      actor: undefined,
      projectId: 7,
      site: { label: 'テスト', siteId: 3, siteName: 'blog' },
      availableSites,
      onMessage,
      html: '<p>本文</p>',
      title: '記事の題名',
      categories: ['a'],
      tags: ['b'],
      featuredImageDataUri: 'data:image/png;base64,AAAA',
      report: jest.fn(),
      ...overrides,
    };
  }

  beforeEach(async () => {
    resetMocks();
    PreviewPanel.currentPanel = undefined;
    onMessage.mockClear();
    server = await LocalHttpServer.start();
    setConfiguration('letsBlog.serverUrl', server.url);
  });

  afterEach(async () => {
    PreviewPanel.currentPanel = undefined;
    await server.stop();
  });

  const html = (): string => lastCreatedWebviewPanel?.webview.html ?? '';

  it('サイトが紐づいていなければ何もせず、呼び出し側が旧経路で扱えるようfalseを返す', async () => {
    const result = await showRealSitePreview(params({ site: { label: 'サイトなし', siteName: 'サイト未紐付け' } }));

    expect(result).toBe(false);
    expect(server.recorded).toHaveLength(0);
    expect(lastCreatedWebviewPanel).toBeUndefined();
  });

  it('URLが取得できれば、記事の内容を送って実サイトのパネルを開く', async () => {
    server.respondWith(jsonReply({ url: 'https://blog.test/?p=1', expiresAt: 1 }));
    const p = params();

    const result = await showRealSitePreview(p);

    expect(result).toBe(true);
    expect(server.recorded[0].path).toBe('/api/projects/7/preview/signed-url');
    expect(JSON.parse(server.recorded[0].body)).toEqual({
      siteId: 3,
      title: '記事の題名',
      contentHtml: '<p>本文</p>',
      categories: ['a'],
      tags: ['b'],
      featuredImageDataUri: 'data:image/png;base64,AAAA',
    });
    expect(html()).toContain('src="https://blog.test/?p=1"');
    expect(lastCreatedWebviewPanel?.title).toBe('Article Preview (テスト / blog)');
    // 環境切り替えは availableSites が 1 件だけなので出さない(静的なサイトラベルの表示)。
    expect(html()).not.toContain('<select');
    expect(p.report).toHaveBeenCalledWith('blog のプレビューURLを取得しています…');
  });

  it('題名が空なら「(無題)」で依頼する', async () => {
    server.respondWith(jsonReply({ url: 'https://blog.test/?p=1', expiresAt: 1 }));

    await showRealSitePreview(params({ title: '' }));

    expect(JSON.parse(server.recorded[0].body).title).toBe('(無題)');
  });

  it('プラグインが使えなければ旧方式へ落とさず、導入の案内パネルを開く', async () => {
    server.respondWith(
      jsonReply({ error: '未導入です', details: { code: 'LETSBLOG_PLUGIN_NOT_INSTALLED' } }, 409)
    );
    const p = params();

    const result = await showRealSitePreview(p);

    expect(result).toBe(true);
    expect(html()).toContain('導入されていません');
    expect(html()).toContain('未導入です');
    expect(html()).not.toContain('<iframe');
    expect(lastCreatedWebviewPanel?.title).toBe('Article Preview (テスト / blog)');
  });

  it('要更新の案内は needsUpdate を引き継ぐ', async () => {
    server.respondWith(
      jsonReply({ error: '要更新です', details: { code: 'LETSBLOG_PLUGIN_NEEDS_UPDATE' } }, 409)
    );

    await showRealSitePreview(params());

    expect(html()).toContain('更新が必要です');
    expect(html()).toContain('要更新です');
  });

  it('URL取得の失敗(409以外)はパネルを開かず呼び出し元へ伝える', async () => {
    server.respondWith(jsonReply({ error: 'boom' }, 403));

    await expect(showRealSitePreview(params())).rejects.toMatchObject({ status: 403 });
    expect(lastCreatedWebviewPanel).toBeUndefined();
  });
});
