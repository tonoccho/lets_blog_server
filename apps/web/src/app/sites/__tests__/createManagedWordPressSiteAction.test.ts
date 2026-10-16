/**
 * サイト自動構築の Server Action(issue #1696)。同期API(`createManagedWordPressSite`)の完了を待たず、
 * ジョブとして受理する `startManagedWordPressSiteJob` を呼んで、受理された時点で返る。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';

/** バックエンド(gateway の向こう)の代役。ジョブ受理 API と、残してある同期 API。 */
const startManagedWordPressSiteJob = jest.fn();
const createManagedWordPressSite = jest.fn();

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

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  startManagedWordPressSiteJob.mockReset();
  installFetch({
    'POST /api/sites/managed-wordpress/jobs': async (_url, init) => {
      try {
        return jsonResponse(await startManagedWordPressSiteJob(JSON.parse(String(init.body))));
      } catch (err) {
        return textResponse(err instanceof Error ? err.message : String(err), 500);
      }
    },
    'POST /api/sites/managed-wordpress': async () => jsonResponse(await createManagedWordPressSite()),
  });
});

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
    // 通信を通ると、未指定(undefined)の項目は本文から落ちる。
    const sent = startManagedWordPressSiteJob.mock.calls[0][0];
    expect(sent.locale).toBe('ja');
    expect(sent).not.toHaveProperty('templateSiteId');
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
    await expect(createManagedWordPressSiteAction({}, form())).resolves.toEqual({ error: 'APIエラー (500): boom' });
  });

  it('Error でない値で通信が失敗しても文字列にして返す', async () => {
    installFetch({
      'POST /api/sites/managed-wordpress/jobs': () => {
        throw 'plain';
      },
    });
    await expect(createManagedWordPressSiteAction({}, form())).resolves.toEqual({ error: 'plain' });
  });
});
