/**
 * @jest-environment node
 */

/**
 * issue #1302: ロール変更のServer Actionは、失敗を例外ではなく`{ error }`として返す
 * (`addProjectUserAction`と同じ形)。`syncProjectUserActions.test.ts`と同じモック方針。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const updateProjectUserRole = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  updateProjectUserRole: (...args: unknown[]) => updateProjectUserRole(...args),
}));

import { updateProjectUserRoleAction } from '../actions';

describe('ロール変更のServer Action(issue #1302)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
  });

  it('成功すると管理者セッションを要求してAPIを呼び、画面を再検証してerrorを含まない結果を返す', async () => {
    updateProjectUserRole.mockResolvedValue(undefined);

    const result = await updateProjectUserRoleAction(7, 42, 'editor');

    expect(requireAdminSession).toHaveBeenCalled();
    expect(updateProjectUserRole).toHaveBeenCalledWith(7, 42, 'editor');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
    expect(result.error).toBeUndefined();
  });

  it('失敗(Error)すると例外を投げずerrorメッセージを返し、再検証しない', async () => {
    updateProjectUserRole.mockRejectedValue(new Error('テスト環境: 失敗しました'));

    const result = await updateProjectUserRoleAction(7, 42, 'editor');

    expect(result).toEqual({ error: 'テスト環境: 失敗しました' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('Error以外で失敗しても文字列化したerrorを返す', async () => {
    updateProjectUserRole.mockRejectedValue('非Errorの失敗');

    const result = await updateProjectUserRoleAction(7, 42, 'editor');

    expect(result).toEqual({ error: '非Errorの失敗' });
  });
});
