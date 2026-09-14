/**
 * @jest-environment node
 */

/**
 * issue #1242: メンバー個別の「ユーザー情報を同期」を扱うServer Actionの検証。
 *
 * `../actions`は他のコンポーネントテスト(ProjectUserManager.test.tsx等)から常にモックされて
 * おり実体が実行されないため、`reviewStepSettingsActions.test.ts`と同じ方針
 * (server-only/next/cacheのモック + `@/lib/session`のrequireAdminSessionモック)で
 * `syncProjectUserAction`を直接importして検証する。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const syncProjectUser = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  syncProjectUser: (...args: unknown[]) => syncProjectUser(...args),
}));

import { syncProjectUserAction } from '../actions';

describe('メンバーのユーザー情報同期のServer Action(issue #1242)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('管理者セッションを要求し、成功時は各サイトの結果をそのまま返す', async () => {
    const results = [
      { siteId: 10, siteKey: 'local-key', siteName: 'ローカル', success: true, errorMessage: null },
    ];
    syncProjectUser.mockResolvedValue(results);

    const result = await syncProjectUserAction(7, 42);

    expect(requireAdminSession).toHaveBeenCalled();
    expect(syncProjectUser).toHaveBeenCalledWith(7, 42);
    expect(result).toEqual({ results });
  });

  it('失敗(Error)するとerrorメッセージを返す', async () => {
    syncProjectUser.mockRejectedValue(new Error('同期に失敗しました'));

    const result = await syncProjectUserAction(7, 42);

    expect(result).toEqual({ error: '同期に失敗しました' });
  });

  it('Error以外で失敗しても文字列化したerrorを返す', async () => {
    // err instanceof Error===falseの分岐を固定するため、意図的にError以外の値で拒否する。
    syncProjectUser.mockRejectedValue('同期に失敗しました(非Error)');

    const result = await syncProjectUserAction(7, 42);

    expect(result).toEqual({ error: '同期に失敗しました(非Error)' });
  });
});
