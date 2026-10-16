/**
 * @jest-environment node
 */
/**
 * プロジェクトダッシュボードの AI接続状況ウィジェットは、`<Suspense>` の内側で取得する async のサーバーコンポーネント。
 * 取得が終わった後の描画は、ブラウザ向けの描画(jsdom)では確かめられないので、`react-dom/static` の `prerender`
 * (Suspense の完了を待つ)で確かめる。自前のモジュールは差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch)だけ(#1835)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  notFound: () => {
    throw new Error('NEXT_NOT_FOUND');
  },
}));

import { prerender } from 'react-dom/static';
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import ProjectDashboardPage from '../page';

let listAiConnections: () => Response;

async function renderToHtml(): Promise<string> {
  const { prelude } = await prerender(await ProjectDashboardPage({ params: Promise.resolve({ id: '7' }) }));
  // 隣り合うテキストの間に React が入れる区切りのコメントは、文言の比較の邪魔なので除く。
  return (await new Response(prelude).text()).replace(/<!-- -->/g, '');
}

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  listAiConnections = () =>
    jsonResponse([
      { provider: 'OLLAMA', displayName: 'Ollama', targetUrl: 'http://ollama:11434', source: 'PROJECT', status: 'NORMAL', detail: null, configured: true },
      { provider: 'CLAUDE', displayName: 'Claude', targetUrl: null, source: 'PROJECT', status: 'ERROR', detail: null, configured: true },
    ]);
  installFetch({
    'GET /api/projects/:id': () => jsonResponse({ id: 7, name: '案件', localSite: null, testSite: null, productionSite: null }),
    'GET /api/projects/:id/users': () => jsonResponse([]),
    'GET /api/projects/:id/dashboard/google-analytics': () => jsonResponse({ eligible: false }),
    'GET /api/projects/:id/dashboard/adsense': () => jsonResponse({ eligible: false }),
    'GET /api/projects/:id/ai-connections': () => listAiConnections(),
  });
});

describe('プロジェクトダッシュボード page.tsx: AI接続状況の取得が終わったあと(issue #1501)', () => {
  it('取得した接続状況を、4行の利用可否つきで表示し、読み込み中の表示は残さない', async () => {
    const html = await renderToHtml();

    expect(html).toContain('http://ollama:11434');
    expect(html).toContain('利用可能');
    expect(html).toContain('利用不可');
    expect(html).toContain('/projects/7?tab=settings');
    expect(html).not.toContain('読み込み中…');
  });

  it('取得に失敗したら、行ではなく失敗の通知を出す', async () => {
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
    listAiConnections = () => textResponse('down', 503);

    const html = await renderToHtml();

    expect(html).toContain('AI接続状況を取得できませんでした');
    expect(html).not.toContain('読み込み中…');
    errorSpy.mockRestore();
  });
});
