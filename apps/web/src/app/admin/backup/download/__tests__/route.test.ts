/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・Server Action・コンポーネントは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => ({
  ...jest.requireActual('next/server'),
  ...require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule,
}));
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { GET } from '../route';
import { installFetch, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION, USER_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';

const BACKUP = 'GET /api/backup/download';

/** バックアップのアーカイブを返す backup-service の応答。 */
function archive(headers: Record<string, string> = {}): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: { get: (name: string) => headers[name.toLowerCase()] ?? null },
    arrayBuffer: async () => new TextEncoder().encode('zip-bytes').buffer,
  } as unknown as Response;
}

/** issue #1263: 管理者必須のバックアップ配信はCache-Controlを明示し、ブラウザに保存させない。 */
describe('GET /admin/backup/download', () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
  });

  it('200を返し、サーバーが示したファイル名で配信し、Cache-Controlはno-store', async () => {
    installFetch({ [BACKUP]: () => archive({ 'content-disposition': 'attachment; filename="backup-2026.zip"' }) });

    const res = await GET();

    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Disposition')).toBe('attachment; filename="backup-2026.zip"');
    expect(res.headers.get('Content-Type')).toBe('application/octet-stream');
    expect(res.headers.get('Cache-Control') ?? '').toContain('no-store');
    expect(new TextDecoder().decode(await res.arrayBuffer())).toBe('zip-bytes');
  });

  it('サーバーがファイル名を示さなければ既定のファイル名にする', async () => {
    installFetch({ [BACKUP]: () => archive() });

    const res = await GET();

    expect(res.headers.get('Content-Disposition')).toContain('lets-blog-backup.zip');
  });

  it('取得に失敗したら502を返し、理由を載せる', async () => {
    installFetch({ [BACKUP]: () => textResponse('boom', 500) });

    const res = await GET();

    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: 'APIエラー (500): boom' });
  });

  it('通信そのものが失敗してError以外が投げられても502を返す', async () => {
    installFetch({
      [BACKUP]: () => {
        throw 'x';
      },
    });

    const res = await GET();

    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: 'x' });
  });

  it('管理者でなければトップへ送り、バックアップを取得しない', async () => {
    resetServerRuntime(USER_SESSION);
    const fetchMock = installFetch({ [BACKUP]: () => archive() });

    await expect(GET()).rejects.toThrow('NEXT_REDIRECT:/');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
