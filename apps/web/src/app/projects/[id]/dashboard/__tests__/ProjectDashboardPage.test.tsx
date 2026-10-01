import { render, screen } from '@testing-library/react';

const notFound = jest.fn(() => {
  throw new Error('NEXT_NOT_FOUND');
});
jest.mock('next/navigation', () => ({ notFound: () => notFound() }));

const getProject = jest.fn();
const listSites = jest.fn();
const getGa = jest.fn();
const getAdsense = jest.fn();
const listProjectUsers = jest.fn();
const listAiConnections = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  getProject: (...a: unknown[]) => getProject(...a),
  listSites: (...a: unknown[]) => listSites(...a),
  getProjectGoogleAnalyticsReport: (...a: unknown[]) => getGa(...a),
  getProjectAdSenseReport: (...a: unknown[]) => getAdsense(...a),
  listProjectUsers: (...a: unknown[]) => listProjectUsers(...a),
  listAiConnections: (...a: unknown[]) => listAiConnections(...a),
}));
jest.mock('@/lib/session', () => ({ requireAdminSession: jest.fn().mockResolvedValue(undefined) }));
jest.mock('@/components/Breadcrumb', () => ({ Breadcrumb: () => <nav /> }));
jest.mock('../../ProjectSectionNav', () => ({ ProjectSectionNav: () => null }));
jest.mock('../../EnvironmentSlot', () => ({
  EnvironmentSlot: ({
    environment,
    site,
    candidateSites,
  }: {
    environment: string;
    site: { siteKey: string } | null;
    candidateSites: unknown[];
  }) => (
    <div data-testid={`slot-${environment}`}>
      {site ? site.siteKey : '未設定'}:{candidateSites.length}
    </div>
  ),
}));
jest.mock('../GoogleAnalyticsWidget', () => ({ GoogleAnalyticsWidget: () => <p>GAレポート</p> }));
jest.mock('../AdSenseWidget', () => ({ AdSenseWidget: () => <p>AdSenseレポート</p> }));

import ProjectDashboardPage from '../page';

const site = (id: number, siteKey: string) => ({ id, siteKey, name: siteKey, baseUrl: 'https://x' });

describe('プロジェクトダッシュボード page.tsx(issue #1500: 環境設定ウィジェット)', () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    notFound.mockClear();
    getProject.mockReset().mockResolvedValue({
      id: 7,
      name: '案件',
      localSite: null,
      testSite: site(1, 'test-key'),
      productionSite: site(2, 'prod-key'),
    });
    listSites.mockReset().mockResolvedValue([site(1, 'test-key'), site(2, 'prod-key')]);
    getGa.mockReset().mockResolvedValue({ eligible: false });
    getAdsense.mockReset().mockResolvedValue({ eligible: false });
    listProjectUsers.mockReset().mockResolvedValue([]);
    listAiConnections.mockReset().mockResolvedValue([]);
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errorSpy.mockRestore());

  const renderPage = async () => render(await ProjectDashboardPage({ params: Promise.resolve({ id: '7' }) }));

  it('「環境設定」ウィジェットにlocal/test/productionの3スロットを候補サイト付きで描く', async () => {
    await renderPage();

    expect(screen.getByRole('heading', { name: '環境設定' })).toBeInTheDocument();
    expect(screen.getByTestId('slot-local')).toHaveTextContent('未設定:2');
    expect(screen.getByTestId('slot-test')).toHaveTextContent('test-key:2');
    expect(screen.getByTestId('slot-production')).toHaveTextContent('prod-key:2');
  });

  it('GA/AdSenseの取得が失敗しても環境設定ウィジェットは表示される', async () => {
    getGa.mockRejectedValue(new Error('ga down'));
    getAdsense.mockRejectedValue(new Error('adsense down'));

    await renderPage();

    expect(screen.getByTestId('slot-production')).toHaveTextContent('prod-key');
    expect(screen.getByRole('heading', { name: '環境設定' })).toBeInTheDocument();
  });

  it('サイト一覧の取得に失敗したら握り潰さず記録し、環境設定ウィジェットは表示する', async () => {
    listSites.mockRejectedValue(new Error('sites down'));

    await renderPage();

    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('サイト一覧'), expect.any(Error));
    expect(screen.getByTestId('slot-local')).toHaveTextContent('未設定:0');
  });

  it('サイト一覧の取得に失敗したら「候補サイトなし」と区別できる失敗表示を出す', async () => {
    listSites.mockRejectedValue(new Error('sites down'));

    await renderPage();

    expect(screen.getByRole('alert')).toHaveTextContent('サイト一覧を取得できませんでした');
  });

  it('サイト一覧の取得に成功したら失敗表示は出さない', async () => {
    await renderPage();

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('プロジェクト取得に失敗したら記録してnotFoundになる', async () => {
    getProject.mockRejectedValue(new Error('404'));

    await expect(renderPage()).rejects.toThrow('NEXT_NOT_FOUND');
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('プロジェクト情報'), expect.any(Error));
  });
});

describe('プロジェクトダッシュボード page.tsx(issue #1502: メンバーウィジェット)', () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    notFound.mockClear();
    getProject.mockReset().mockResolvedValue({ id: 7, name: '案件', localSite: null, testSite: null, productionSite: null });
    listSites.mockReset().mockResolvedValue([]);
    getGa.mockReset().mockResolvedValue({ eligible: false });
    getAdsense.mockReset().mockResolvedValue({ eligible: false });
    listAiConnections.mockReset().mockResolvedValue([]);
    listProjectUsers.mockReset().mockResolvedValue([
      { userId: 1, email: null, displayName: '山田太郎', wpRole: 'administrator' },
    ]);
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errorSpy.mockRestore());

  const renderPage = async () => render(await ProjectDashboardPage({ params: Promise.resolve({ id: '7' }) }));

  it('listProjectUsersで取得したメンバーをウィジェットに描く', async () => {
    await renderPage();

    expect(listProjectUsers).toHaveBeenCalledWith(7);
    expect(screen.getByRole('heading', { name: 'メンバー' })).toBeInTheDocument();
    expect(screen.getByText('山田太郎')).toBeInTheDocument();
  });

  it('メンバーが0人なら「0人」と表示する', async () => {
    listProjectUsers.mockResolvedValue([]);

    await renderPage();

    expect(screen.getByText('0人')).toBeInTheDocument();
  });

  it('メンバー取得に失敗したら「0人」ではなく取得失敗を示し、ページ自体は表示される', async () => {
    listProjectUsers.mockRejectedValue(new Error('users down'));

    await renderPage();

    expect(screen.getByRole('alert')).toHaveTextContent('メンバーを取得できませんでした');
    expect(screen.queryByText('0人')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'メンバー' })).toBeInTheDocument();
    expect(errorSpy).toHaveBeenCalled();
  });
});

describe('プロジェクトダッシュボード page.tsx(issue #1501: AI接続状況ウィジェット)', () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    notFound.mockClear();
    getProject.mockReset().mockResolvedValue({ id: 7, name: '案件', localSite: null, testSite: null, productionSite: null });
    listSites.mockReset().mockResolvedValue([]);
    getGa.mockReset().mockResolvedValue({ eligible: true });
    getAdsense.mockReset().mockResolvedValue({ eligible: true });
    listProjectUsers.mockReset().mockResolvedValue([]);
    listAiConnections.mockReset();
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errorSpy.mockRestore());

  const renderPage = async () => render(await ProjectDashboardPage({ params: Promise.resolve({ id: '7' }) }));

  it('listAiConnectionsが終わらなくても、GA/AdSense/環境設定ウィジェットは表示され、AI接続状況は読み込み中になる', async () => {
    listAiConnections.mockReturnValue(new Promise(() => undefined));

    await renderPage();

    expect(listAiConnections).toHaveBeenCalledWith(7);
    expect(screen.getByRole('heading', { name: '環境設定' })).toBeInTheDocument();
    expect(screen.getByText('GAレポート')).toBeInTheDocument();
    expect(screen.getByText('AdSenseレポート')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'AI接続状況' })).toBeInTheDocument();
    expect(screen.getByText('読み込み中…')).toBeInTheDocument();
  });
});
