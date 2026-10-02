/**
 * @jest-environment node
 */
import { GET } from '../route';
import { downloadBackupFile } from '@/lib/apiClient';
import { requireAdminSession } from '@/lib/session';

jest.mock('@/lib/apiClient', () => ({
  downloadBackupFile: jest.fn(),
}));
jest.mock('@/lib/session', () => ({
  requireAdminSession: jest.fn(),
}));

const mockDownload = downloadBackupFile as jest.MockedFunction<typeof downloadBackupFile>;
const mockRequireAdmin = requireAdminSession as jest.MockedFunction<typeof requireAdminSession>;

/** issue #1263: 管理者必須のバックアップ配信はCache-Controlを明示し、ブラウザに保存させない。 */
describe('GET /admin/backup/download', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockRequireAdmin.mockResolvedValue(undefined as never);
  });

  it('200を返し、Cache-Controlはno-store', async () => {
    mockDownload.mockResolvedValue({ body: new ArrayBuffer(4), filename: 'backup.zip' } as never);

    const res = await GET();

    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Disposition')).toContain('backup.zip');
    expect(res.headers.get('Cache-Control') ?? '').toContain('no-store');
  });

  it('取得に失敗したら502を返す', async () => {
    mockDownload.mockRejectedValue(new Error('boom'));
    const res = await GET();
    expect(res.status).toBe(502);
  });

  it('Error以外がthrowされても502を返す', async () => {
    mockDownload.mockRejectedValue('x');
    const res = await GET();
    expect(res.status).toBe(502);
  });
});
