import { render, screen } from '@testing-library/react';

const notFound = jest.fn(() => {
  throw new Error('NEXT_NOT_FOUND');
});
jest.mock('next/navigation', () => ({ notFound: () => notFound() }));

const getProject = jest.fn();
const listSites = jest.fn();
const getGa = jest.fn();
const getAdsense = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  getProject: (...a: unknown[]) => getProject(...a),
  listSites: (...a: unknown[]) => listSites(...a),
  getProjectGoogleAnalyticsReport: (...a: unknown[]) => getGa(...a),
  getProjectAdSenseReport: (...a: unknown[]) => getAdsense(...a),
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
