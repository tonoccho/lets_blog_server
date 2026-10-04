import { showRealSitePreview, RealSitePreviewParams } from '../realSitePreview';
import { createSignedPreviewUrl } from '../apiClient';
import { PreviewPanel } from '../previewPanel';

jest.mock('../apiClient', () => ({ createSignedPreviewUrl: jest.fn() }));
jest.mock('../previewPanel', () => ({
  PreviewPanel: { showRealSite: jest.fn(), showPluginGuidance: jest.fn() },
}));

const mockedCreate = createSignedPreviewUrl as jest.MockedFunction<typeof createSignedPreviewUrl>;
const mockedShowRealSite = PreviewPanel.showRealSite as jest.Mock;
const mockedShowGuidance = PreviewPanel.showPluginGuidance as jest.Mock;

/** プレビューコマンドの表示分岐(issue #1562): サイトの紐付け有無、URL取得の可否、題名のフォールバック。 */
describe('showRealSitePreview', () => {
  const onMessage = jest.fn();
  const availableSites = [{ siteId: 3, label: 'テスト', siteName: 'blog' }];

  function params(overrides: Partial<RealSitePreviewParams> = {}): RealSitePreviewParams {
    return {
      context: {} as RealSitePreviewParams['context'],
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

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('サイトが紐づいていなければ何もせず、呼び出し側が旧経路で扱えるようfalseを返す', async () => {
    const result = await showRealSitePreview(params({ site: { label: 'サイトなし', siteName: 'サイト未紐付け' } }));

    expect(result).toBe(false);
    expect(mockedCreate).not.toHaveBeenCalled();
    expect(mockedShowRealSite).not.toHaveBeenCalled();
    expect(mockedShowGuidance).not.toHaveBeenCalled();
  });

  it('URLが取得できれば、記事の内容を送って実サイトのパネルを開く', async () => {
    mockedCreate.mockResolvedValue({ kind: 'ready', url: 'https://blog.test/?p=1', expiresAt: 1 });
    const p = params();

    const result = await showRealSitePreview(p);

    expect(result).toBe(true);
    expect(mockedCreate).toHaveBeenCalledWith('token', undefined, 7, {
      siteId: 3,
      title: '記事の題名',
      contentHtml: '<p>本文</p>',
      categories: ['a'],
      tags: ['b'],
      featuredImageDataUri: 'data:image/png;base64,AAAA',
    });
    expect(mockedShowRealSite).toHaveBeenCalledWith(p.context, {
      siteLabel: 'テスト / blog',
      onMessage,
      availableSites,
      currentSiteId: 3,
      url: 'https://blog.test/?p=1',
    });
    expect(mockedShowGuidance).not.toHaveBeenCalled();
    expect(p.report).toHaveBeenCalledWith('blog のプレビューURLを取得しています…');
  });

  it('題名が空なら「(無題)」で依頼する', async () => {
    mockedCreate.mockResolvedValue({ kind: 'ready', url: 'https://blog.test/?p=1', expiresAt: 1 });

    await showRealSitePreview(params({ title: '' }));

    expect(mockedCreate.mock.calls[0][3].title).toBe('(無題)');
  });

  it('プラグインが使えなければ旧方式へ落とさず、導入の案内パネルを開く', async () => {
    mockedCreate.mockResolvedValue({ kind: 'pluginUnavailable', message: '未導入です', needsUpdate: false });
    const p = params();

    const result = await showRealSitePreview(p);

    expect(result).toBe(true);
    expect(mockedShowGuidance).toHaveBeenCalledWith(p.context, {
      siteLabel: 'テスト / blog',
      onMessage,
      availableSites,
      currentSiteId: 3,
      needsUpdate: false,
      message: '未導入です',
    });
    expect(mockedShowRealSite).not.toHaveBeenCalled();
  });

  it('要更新の案内は needsUpdate を引き継ぐ', async () => {
    mockedCreate.mockResolvedValue({ kind: 'pluginUnavailable', message: '要更新です', needsUpdate: true });

    await showRealSitePreview(params());

    expect(mockedShowGuidance.mock.calls[0][1].needsUpdate).toBe(true);
  });

  it('URL取得の失敗(409以外)はパネルを開かず呼び出し元へ伝える', async () => {
    mockedCreate.mockRejectedValue(new Error('boom'));

    await expect(showRealSitePreview(params())).rejects.toThrow('boom');
    expect(mockedShowRealSite).not.toHaveBeenCalled();
    expect(mockedShowGuidance).not.toHaveBeenCalled();
  });
});
