/**
 * @jest-environment node
 */
import { GET } from '../route';
import { downloadProjectCustomTagCssBundle } from '@/lib/apiClient';
import { getSession } from '@/lib/session';

jest.mock('@/lib/apiClient', () => ({
  downloadProjectCustomTagCssBundle: jest.fn(),
}));
jest.mock('@/lib/session', () => ({
  getSession: jest.fn(),
}));

const mockDownload = downloadProjectCustomTagCssBundle as jest.MockedFunction<
  typeof downloadProjectCustomTagCssBundle
>;
const mockGetSession = getSession as jest.MockedFunction<typeof getSession>;

function callGet(id: string): ReturnType<typeof GET> {
  return GET(new Request(`http://localhost/projects/${id}/custom-tags/css-bundle`), {
    params: Promise.resolve({ id }),
  });
}

/** issue #1263: ログイン必須のCSSバンドル配信はCache-Controlを明示し、ブラウザに保存させない。 */
describe('GET /projects/[id]/custom-tags/css-bundle', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('ログイン済みなら200を返し、Cache-Controlはno-store', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockResolvedValue('body{}' as never);

    const res = await callGet('7');

    expect(res.status).toBe(200);
    expect(mockDownload).toHaveBeenCalledWith(7);
    expect(res.headers.get('Cache-Control') ?? '').toContain('no-store');
  });

  it('未ログインなら401を返し、取得しない', async () => {
    mockGetSession.mockResolvedValue(null);
    const res = await callGet('7');
    expect(res.status).toBe(401);
    expect(mockDownload).not.toHaveBeenCalled();
  });

  it('取得に失敗したら502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockRejectedValue(new Error('boom'));
    const res = await callGet('7');
    expect(res.status).toBe(502);
  });

  it('Error以外がthrowされても502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockRejectedValue('x');
    const res = await callGet('7');
    expect(res.status).toBe(502);
  });
});
