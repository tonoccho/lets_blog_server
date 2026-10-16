/**
 * @jest-environment node
 *
 * 初回セットアップ画面。ユーザーが 1 人もいないときだけ表示し、それ以外は /login へ送る。
 * 自前の @/lib/apiClient・SetupForm・./actions は差し替えない。差し替えるのは Next.js の実行基盤
 * (next/navigation の redirect など)と外部との通信(fetch)だけ(#1832)。
 */
import { renderToStaticMarkup } from 'react-dom/server';

jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
const mockRedirect = jest.fn((path: string) => {
  throw new Error(`NEXT_REDIRECT:${path}`);
});
jest.mock('next/navigation', () => ({ redirect: (p: string) => mockRedirect(p) }));

import SetupPage from '../page';
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';

const STATUS = 'GET /api/auth/setup-status';

describe('SetupPage', () => {
  beforeEach(() => {
    resetServerRuntime(null);
    mockRedirect.mockClear();
  });

  it('セットアップが必要なら、認証なしで状態を確認して、フォームを表示する', async () => {
    const fetchMock = installFetch({ [STATUS]: () => jsonResponse({ needsSetup: true }) });

    const html = renderToStaticMarkup(await SetupPage());

    expect(html).toContain('初回セットアップ');
    expect(html).toContain('管理者アカウントを作成');
    expect(mockRedirect).not.toHaveBeenCalled();
    const [, init] = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/api/auth/setup-status'))!;
    expect(init.headers.Authorization).toBeUndefined();
  });

  it('既にセットアップ済みなら /login へ送る', async () => {
    installFetch({ [STATUS]: () => jsonResponse({ needsSetup: false }) });

    await expect(SetupPage()).rejects.toThrow('NEXT_REDIRECT:/login');
  });

  it('状態を確認できなければ(gateway 障害)、セットアップ済みとみなして /login へ送る', async () => {
    installFetch({ [STATUS]: () => textResponse('down', 503) });

    await expect(SetupPage()).rejects.toThrow('NEXT_REDIRECT:/login');
  });
});
