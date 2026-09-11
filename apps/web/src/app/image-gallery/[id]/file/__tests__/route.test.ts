/**
 * @jest-environment node
 */
import { GET } from '../route';
import { downloadGeneratedImageFile } from '@/lib/apiClient';
import { getSession } from '@/lib/session';

jest.mock('@/lib/apiClient', () => ({
  downloadGeneratedImageFile: jest.fn(),
}));
jest.mock('@/lib/session', () => ({
  getSession: jest.fn(),
}));

const mockDownloadGeneratedImageFile = downloadGeneratedImageFile as jest.MockedFunction<
  typeof downloadGeneratedImageFile
>;
const mockGetSession = getSession as jest.MockedFunction<typeof getSession>;

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
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('ログイン済みなら画像を200で返し、Cache-Controlはキャッシュを許可しない', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownloadGeneratedImageFile.mockResolvedValue({
      body: new ArrayBuffer(4),
      mimeType: 'image/png',
    });

    const res = await callGet('42');

    expect(res.status).toBe(200);
    expect(mockDownloadGeneratedImageFile).toHaveBeenCalledWith(42);
    const cacheControl = res.headers.get('Cache-Control') ?? '';
    expect(cacheControl).not.toMatch(/(^|[,\s])public(\s|,|$)/);
    expect(cacheControl).toContain('no-store');
  });

  it('未ログインなら401を返し、画像は取得しない', async () => {
    mockGetSession.mockResolvedValue(null);

    const res = await callGet('42');

    expect(res.status).toBe(401);
    expect(mockDownloadGeneratedImageFile).not.toHaveBeenCalled();
  });

  it('画像の取得に失敗したら502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownloadGeneratedImageFile.mockRejectedValue(new Error('APIエラー (404): {"error":"id: 42"}'));

    const res = await callGet('42');

    expect(res.status).toBe(502);
  });

  it('Error以外の値がthrowされても502を返す(C1/C2網羅。本Issueの変更対象ではない既存分岐)', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownloadGeneratedImageFile.mockRejectedValue('unexpected non-error rejection');

    const res = await callGet('42');

    expect(res.status).toBe(502);
    const body = (await res.json()) as { error: string };
    expect(body.error).toBe('unexpected non-error rejection');
  });
});
