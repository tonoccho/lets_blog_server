/**
 * プロジェクトダッシュボード page.tsx。自前の @/lib/session・@/lib/apiClient・Breadcrumb・ProjectSectionNav・
 * 各ウィジェット・Server Action は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
import { render, screen } from '@testing-library/react';

jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  notFound: () => {
    throw new Error('NEXT_NOT_FOUND');
  },
}));

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import ProjectDashboardPage from '../page';

const site = (id: number, siteKey: string) => ({ id, siteKey, name: `名前-${siteKey}`, baseUrl: 'https://x' });

/** gateway の向こう側(各サービス)の代役。テストごとに応答を差し替える。 */
let getProject: () => Response | Promise<Response>;
let listProjectUsers: () => Response | Promise<Response>;
let getGa: () => Response | Promise<Response>;
let getAdsense: () => Response | Promise<Response>;
let listAiConnections: () => Response | Promise<Response>;
let requested: string[];

const OWN_PROJECT = { id: 7, name: '案件', localSite: null, testSite: null, productionSite: null };

beforeEach(() => {
  requested = [];
  resetServerRuntime(ADMIN_SESSION);
  getProject = () => jsonResponse(OWN_PROJECT);
  listProjectUsers = () => jsonResponse([]);
  getGa = () => jsonResponse({ eligible: false });
  getAdsense = () => jsonResponse({ eligible: false });
  listAiConnections = () => jsonResponse([]);
  installFetch({
    'GET /api/projects/:id': () => {
      requested.push('project');
      return getProject();
    },
    'GET /api/projects/:id/users': () => {
      requested.push('users');
      return listProjectUsers();
    },
    'GET /api/projects/:id/dashboard/google-analytics': () => getGa(),
    'GET /api/projects/:id/dashboard/adsense': () => getAdsense(),
    'GET /api/projects/:id/ai-connections': () => {
      requested.push('ai-connections');
      return listAiConnections();
    },
  });
});

const renderPage = async () => render(await ProjectDashboardPage({ params: Promise.resolve({ id: '7' }) }));

describe('プロジェクトダッシュボード page.tsx(issue #1500: 環境設定ウィジェット)', () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    getProject = () =>
      jsonResponse({ ...OWN_PROJECT, testSite: site(1, 'test-key'), productionSite: site(2, 'prod-key') });
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errorSpy.mockRestore());

  it('「環境設定」ウィジェットにlocal/test/productionの3環境を表示専用で描く', async () => {
    await renderPage();

    expect(screen.getByRole('heading', { name: '案件 — ダッシュボード' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '環境設定' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'ローカル環境' })).toBeInTheDocument();
    expect(screen.getByText('test-key')).toBeInTheDocument();
    expect(screen.getByText('prod-key')).toBeInTheDocument();
    expect(screen.getByText('未設定')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });

  it('環境設定ウィジェットはサイト一覧を取得しない(紐付けの候補が不要になったため)', async () => {
    await renderPage();

    expect(global.fetch).not.toHaveBeenCalledWith(expect.stringMatching(/\/api\/sites$/), expect.anything());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('GA/AdSenseの取得が失敗しても環境設定ウィジェットは表示される', async () => {
    getGa = () => textResponse('ga down', 503);
    getAdsense = () => textResponse('adsense down', 503);

    await renderPage();

    expect(screen.getByText('prod-key')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '環境設定' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Google Analyticsを設定' })).toHaveAttribute(
      'href',
      '/projects/7/settings/google-analytics',
    );
    expect(screen.getByRole('link', { name: 'Google AdSenseを設定' })).toHaveAttribute('href', '/projects/7/settings/adsense');
  });

  it('プロジェクト取得に失敗したら記録してnotFoundになる', async () => {
    getProject = () => textResponse('not found', 404);

    await expect(renderPage()).rejects.toThrow('NEXT_NOT_FOUND');
    expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('プロジェクト情報'), expect.any(Error));
  });

  it('管理者でなければ / へ戻す', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(renderPage()).rejects.toThrow('NEXT_REDIRECT:/');
  });
});

describe('プロジェクトダッシュボード page.tsx(issue #1502: メンバーウィジェット)', () => {
  let errorSpy: jest.SpyInstance;
  beforeEach(() => {
    listProjectUsers = () => jsonResponse([{ userId: 1, email: null, displayName: '山田太郎', wpRole: 'administrator' }]);
    errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errorSpy.mockRestore());

  it('プロジェクトのメンバーを取得してウィジェットに描く', async () => {
    await renderPage();

    expect(requested).toContain('users');
    expect(screen.getByRole('heading', { name: 'メンバー' })).toBeInTheDocument();
    expect(screen.getByText('山田太郎')).toBeInTheDocument();
  });

  it('メンバーが0人なら「0人」と表示する', async () => {
    listProjectUsers = () => jsonResponse([]);

    await renderPage();

    expect(screen.getByText('0人')).toBeInTheDocument();
  });

  it('メンバー取得に失敗したら「0人」ではなく取得失敗を示し、ページ自体は表示される', async () => {
    listProjectUsers = () => textResponse('users down', 503);

    await renderPage();

    expect(screen.getByRole('alert')).toHaveTextContent('メンバーを取得できませんでした');
    expect(screen.queryByText('0人')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'メンバー' })).toBeInTheDocument();
    expect(errorSpy).toHaveBeenCalled();
  });
});

describe('プロジェクトダッシュボード page.tsx(issue #1501: AI接続状況ウィジェット)', () => {
  beforeEach(() => {
    getGa = () => jsonResponse({ eligible: true, sessions: 1234, activeUsers: 5, pageViews: 9, periodLabel: '過去28日間', errorMessage: null, dailyDataPoints: [], channelBreakdown: [] });
    getAdsense = () => jsonResponse({ eligible: true, estimatedEarnings: '12.34', clicks: 1, impressions: 2, periodLabel: '過去30日間', errorMessage: null, dailyDataPoints: [], platformBreakdown: [] });
  });

  it('AI接続状況が終わらなくても、GA/AdSense/環境設定ウィジェットは表示され、AI接続状況は読み込み中になる', async () => {
    listAiConnections = () => new Promise<Response>(() => undefined);

    await renderPage();

    expect(screen.getByRole('heading', { name: '環境設定' })).toBeInTheDocument();
    expect(screen.getByText('1,234')).toBeInTheDocument();
    expect(screen.getByText('12.34')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'AI接続状況' })).toBeInTheDocument();
    expect(screen.getByText('読み込み中…')).toBeInTheDocument();
  });
});
