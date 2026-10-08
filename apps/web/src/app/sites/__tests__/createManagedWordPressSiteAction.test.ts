/**
 * サイト自動構築の Server Action(issue #1696)。同期API(`createManagedWordPressSite`)の完了を待たず、
 * ジョブとして受理する `startManagedWordPressSiteJob` を呼んで、受理された時点で返る。
 */
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('@/lib/session', () => ({ requireAdminSession: jest.fn(), requireSession: jest.fn() }));

const startManagedWordPressSiteJob = jest.fn();
const createManagedWordPressSite = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  startManagedWordPressSiteJob: (...a: unknown[]) => startManagedWordPressSiteJob(...a),
  createManagedWordPressSite: (...a: unknown[]) => createManagedWordPressSite(...a),
}));

import { createManagedWordPressSiteAction } from '../actions';

const FIELDS: Record<string, string> = {
  managedName: ' My Blog ',
  managedSiteKey: 'main',
  managedTitle: 'My Blog',
  managedAdminUser: 'admin',
  managedAdminEmail: 'a@example.com',
  managedAdminPassword: 'secret-pass',
  managedLocale: 'en_US',
  managedTemplateSiteId: '5',
};

function form(overrides: Record<string, string | null> = {}): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries({ ...FIELDS, ...overrides })) {
    if (v !== null) data.set(k, v);
  }
  return data;
}

beforeEach(() => jest.clearAllMocks());

describe('createManagedWordPressSiteAction (#1696)', () => {
  it('ジョブとして受理し、完了を待たずにジョブIDを返す', async () => {
    startManagedWordPressSiteJob.mockResolvedValue({ id: 31, status: 'running' });

    await expect(createManagedWordPressSiteAction({}, form())).resolves.toEqual({ success: true, jobId: 31 });

    expect(startManagedWordPressSiteJob).toHaveBeenCalledWith({
      name: 'My Blog',
      siteKey: 'main',
      title: 'My Blog',
      adminUser: 'admin',
      adminEmail: 'a@example.com',
      adminPassword: 'secret-pass',
      locale: 'en_US',
      templateSiteId: 5,
    });
  });

  it('同期APIは呼ばない', async () => {
    startManagedWordPressSiteJob.mockResolvedValue({ id: 31, status: 'running' });
    await createManagedWordPressSiteAction({}, form());
    expect(createManagedWordPressSite).not.toHaveBeenCalled();
  });

  it('言語とテンプレートが省略されたら、言語は ja・テンプレートは未指定で送る', async () => {
    startManagedWordPressSiteJob.mockResolvedValue({ id: 1, status: 'running' });
    await createManagedWordPressSiteAction({}, form({ managedLocale: null, managedTemplateSiteId: null }));
    expect(startManagedWordPressSiteJob).toHaveBeenCalledWith(
      expect.objectContaining({ locale: 'ja', templateSiteId: undefined }),
    );
  });

  it.each(['managedName', 'managedSiteKey', 'managedTitle', 'managedAdminUser', 'managedAdminEmail', 'managedAdminPassword'])(
    '%s が空ならジョブを作らずエラーを返す',
    async (field) => {
      await expect(createManagedWordPressSiteAction({}, form({ [field]: '  ' }))).resolves.toEqual({
        error: 'すべての項目を入力してください。',
      });
      expect(startManagedWordPressSiteJob).not.toHaveBeenCalled();
    },
  );

  it('待ち行列が満杯でジョブが failed で返ったら、受理ではなくエラーとして返す', async () => {
    startManagedWordPressSiteJob.mockResolvedValue({ id: 31, status: 'failed' });

    const result = await createManagedWordPressSiteAction({}, form());

    expect(result.success).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('API が失敗したらそのメッセージを返す', async () => {
    startManagedWordPressSiteJob.mockRejectedValue(new Error('boom'));
    await expect(createManagedWordPressSiteAction({}, form())).resolves.toEqual({ error: 'boom' });
  });

  it('Error でない例外も文字列にして返す', async () => {
    startManagedWordPressSiteJob.mockRejectedValue('plain');
    await expect(createManagedWordPressSiteAction({}, form())).resolves.toEqual({ error: 'plain' });
  });
});
