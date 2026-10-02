/**
 * @jest-environment node
 */
import { GET } from '../route';
import { downloadPenpotPlugin } from '@/lib/apiClient';
import { getSession } from '@/lib/session';

jest.mock('@/lib/apiClient', () => ({
  downloadPenpotPlugin: jest.fn(),
}));
jest.mock('@/lib/session', () => ({
  getSession: jest.fn(),
}));

const mockDownload = downloadPenpotPlugin as jest.MockedFunction<typeof downloadPenpotPlugin>;
const mockGetSession = getSession as jest.MockedFunction<typeof getSession>;

/** issue #1491: Penpotプラグイン のZip配布。ログイン必須でno-store、ファイル名をContent-Dispositionで示す。 */
describe('GET /downloads/penpot-plugin', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('ログイン済みなら200でZipを返し、Content-Dispositionにファイル名、Cache-Controlはno-store', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockResolvedValue({ body: new ArrayBuffer(4), filename: 'letsblog-penpot-plugin.zip' } as never);

    const res = await GET();

    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Disposition')).toBe('attachment; filename="letsblog-penpot-plugin.zip"');
    expect(res.headers.get('Cache-Control') ?? '').toContain('no-store');
  });

  it('未ログインなら401を返し、取得しない', async () => {
    mockGetSession.mockResolvedValue(null);
    const res = await GET();
    expect(res.status).toBe(401);
    expect(mockDownload).not.toHaveBeenCalled();
  });

  it('取得に失敗したら502とエラーメッセージを返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockRejectedValue(new Error('boom'));
    const res = await GET();
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: 'boom' });
  });

  it('Error以外がthrowされても502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockRejectedValue('x');
    const res = await GET();
    expect(res.status).toBe(502);
  });
});
