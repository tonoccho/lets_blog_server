/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・@/lib/auth は差し替えない。差し替えるのは Next.js の実行基盤
// (next-auth・next/headers・next/server の after)と、外部との通信(fetch)だけ(#1832)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => ({
  ...jest.requireActual('next/server'),
  ...require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule,
}));
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

import {
  getServerSessionMock as mockGetSession,
  installFetch,
  jsonResponse,
  resetServerRuntime,
  textResponse,
} from '@/lib/__tests__/testUtils/serverRuntime';

import { GET } from '../route';

const VSIX = 'GET /api/system/vscode-extension';

/** issue #1263: ログイン必須のvsix配信はCache-Controlを明示し、ブラウザに保存させない。 */
describe('GET /downloads/vscode-extension', () => {
  beforeEach(() => {
    resetServerRuntime(null);
  });

  it('ログイン済みなら200を返し、Cache-Controlはno-store', async () => {
    mockGetSession.mockResolvedValue({ user: {} });
    installFetch({
      [VSIX]: () => jsonResponse({}, 200, { 'content-disposition': 'attachment; filename="ext.vsix"' }),
    });

    const res = await GET();

    expect(res.status).toBe(200);
    expect(res.headers.get('Cache-Control') ?? '').toContain('no-store');
    expect(res.headers.get('Content-Disposition')).toBe('attachment; filename="ext.vsix"');
  });

  it('未ログインなら401を返し、取得しない', async () => {
    mockGetSession.mockResolvedValue(null);
    const fetchMock = installFetch({ [VSIX]: () => jsonResponse({}) });
    const res = await GET();
    expect(res.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('取得に失敗したら502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} });
    installFetch({ [VSIX]: () => textResponse('boom', 500) });
    const res = await GET();
    expect(res.status).toBe(502);
    expect((await res.json()).error).toContain('boom');
  });

  it('Error以外がthrowされても502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} });
    installFetch({
      [VSIX]: () => {
        throw 'x';
      },
    });
    const res = await GET();
    expect(res.status).toBe(502);
    expect((await res.json()).error).toBe('x');
  });
});
