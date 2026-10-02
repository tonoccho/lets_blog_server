/**
 * @jest-environment node
 */
import { GET } from '../route';
import { downloadVscodeExtension } from '@/lib/apiClient';
import { getSession } from '@/lib/session';

jest.mock('@/lib/apiClient', () => ({
  downloadVscodeExtension: jest.fn(),
}));
jest.mock('@/lib/session', () => ({
  getSession: jest.fn(),
}));

const mockDownload = downloadVscodeExtension as jest.MockedFunction<typeof downloadVscodeExtension>;
const mockGetSession = getSession as jest.MockedFunction<typeof getSession>;

/** issue #1263: ログイン必須のvsix配信はCache-Controlを明示し、ブラウザに保存させない。 */
describe('GET /downloads/vscode-extension', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('ログイン済みなら200を返し、Cache-Controlはno-store', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockResolvedValue({ body: new ArrayBuffer(4), filename: 'ext.vsix' } as never);

    const res = await GET();

    expect(res.status).toBe(200);
    expect(res.headers.get('Cache-Control') ?? '').toContain('no-store');
  });

  it('未ログインなら401を返し、取得しない', async () => {
    mockGetSession.mockResolvedValue(null);
    const res = await GET();
    expect(res.status).toBe(401);
    expect(mockDownload).not.toHaveBeenCalled();
  });

  it('取得に失敗したら502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockRejectedValue(new Error('boom'));
    const res = await GET();
    expect(res.status).toBe(502);
  });

  it('Error以外がthrowされても502を返す', async () => {
    mockGetSession.mockResolvedValue({ user: {} } as never);
    mockDownload.mockRejectedValue('x');
    const res = await GET();
    expect(res.status).toBe(502);
  });
});
