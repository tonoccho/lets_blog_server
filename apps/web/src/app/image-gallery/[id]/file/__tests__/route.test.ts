/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { GET } from '../route';
import { installFetch, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';

/** media-service が返す画像ファイルの代役。 */
const download = jest.fn();
function fileResponse(mimeType: string | null): Response {
  return {
    ok: true,
    status: 200,
    statusText: 'OK',
    headers: { get: (name: string) => (name.toLowerCase() === 'content-type' ? mimeType : null) },
    arrayBuffer: async () => new TextEncoder().encode('png-bytes').buffer,
  } as unknown as Response;
}
function setup(session: unknown) {
  jest.clearAllMocks();
  resetServerRuntime(session);
  installFetch({
    'GET /api/generated-images/:id/file': async (_u, _i, [id]) => {
      try {
        return fileResponse(await download(Number(id)));
      } catch (err) {
        return textResponse(err instanceof Error ? err.message.replace(/^APIエラー \(\d+\): /, '') : String(err), 404);
      }
    },
  });
}

function callGet(id: string): ReturnType<typeof GET> {
  return GET(new Request(`http://localhost/image-gallery/${id}/file`), {
    params: Promise.resolve({ id }),
  });
}

/**
 * issue #1064: ログイン必須の生成画像配信が `Cache-Control: public, max-age=3600` を返しており、
 * ログアウト後や共有キャッシュからでも画像を取得できてしまっていた。セッション確認(このハンドラ
 * 自身の先頭)を毎回必ず通すよう、キャッシュを許可しない値へ変える。
 */
describe('GET /image-gallery/[id]/file', () => {
  it('ログイン済みなら画像を200で返し、Cache-Controlはキャッシュを許可しない', async () => {
    setup({ user: {} });
    download.mockResolvedValue('image/png');

    const res = await callGet('42');

    expect(res.status).toBe(200);
    expect(download).toHaveBeenCalledWith(42);
    expect(res.headers.get('Content-Type')).toBe('image/png');
    expect(new TextDecoder().decode(await res.arrayBuffer())).toBe('png-bytes');
    const cacheControl = res.headers.get('Cache-Control') ?? '';
    expect(cacheControl).not.toMatch(/(^|[,\s])public(\s|,|$)/);
    expect(cacheControl).toContain('no-store');
  });

  it('メディアタイプが無い応答は image/png として返す', async () => {
    setup({ user: {} });
    download.mockResolvedValue(null);

    const res = await callGet('42');

    expect(res.headers.get('Content-Type')).toBe('image/png');
  });

  it('未ログインなら401を返し、画像は取得しない', async () => {
    setup(null);

    const res = await callGet('42');

    expect(res.status).toBe(401);
    expect(download).not.toHaveBeenCalled();
  });

  it('画像の取得に失敗したら502を返す', async () => {
    setup({ user: {} });
    download.mockRejectedValue(new Error('{"error":"id: 42"}'));

    const res = await callGet('42');

    expect(res.status).toBe(502);
    const body = (await res.json()) as { error: string };
    expect(body.error).toContain('APIエラー (404)');
  });

  it('Error以外の値がthrowされても502を返す(C1/C2網羅。本Issueの変更対象ではない既存分岐)', async () => {
    setup({ user: {} });
    // 通信の層(fetch)が Error 以外を投げる状態。
    (global.fetch as jest.Mock).mockRejectedValue('unexpected non-error rejection');

    const res = await callGet('42');

    expect(res.status).toBe(502);
    const body = (await res.json()) as { error: string };
    expect(body.error).toBe('unexpected non-error rejection');
  });
});
