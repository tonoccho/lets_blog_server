const bindProjectEnvironment = jest.fn();
const unbindProjectEnvironment = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  bindProjectEnvironment: (...a: unknown[]) => bindProjectEnvironment(...a),
  unbindProjectEnvironment: (...a: unknown[]) => unbindProjectEnvironment(...a),
}));
jest.mock('@/lib/session', () => ({ requireAdminSession: jest.fn().mockResolvedValue(undefined) }));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));

import { bindEnvironmentAction, unbindEnvironmentAction } from '../actions';

function form(siteId: string): FormData {
  const f = new FormData();
  f.set('siteId', siteId);
  return f;
}

describe('環境の紐付け・切離しアクション(issue #1500: ダッシュボードも再検証する)', () => {
  beforeEach(() => {
    bindProjectEnvironment.mockReset().mockResolvedValue({});
    unbindProjectEnvironment.mockReset().mockResolvedValue({});
    revalidatePath.mockReset();
  });

  it('紐付けに成功すると詳細ページとダッシュボードを再検証する', async () => {
    const result = await bindEnvironmentAction(7, 'production', {}, form('3'));

    expect(result).toEqual({ success: true });
    expect(bindProjectEnvironment).toHaveBeenCalledWith(7, 'production', 3);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
  });

  it('サイト未選択ならエラーを返し、再検証しない', async () => {
    const result = await bindEnvironmentAction(7, 'test', {}, form(''));

    expect(result.error).toBe('サイトを選択してください。');
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('紐付けがErrorで失敗したらそのメッセージを返し、再検証しない', async () => {
    bindProjectEnvironment.mockRejectedValue(new Error('conflict'));

    expect(await bindEnvironmentAction(7, 'test', {}, form('3'))).toEqual({ error: 'conflict' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('紐付けがError以外で失敗しても文字列化して返す', async () => {
    bindProjectEnvironment.mockRejectedValue('boom');

    expect(await bindEnvironmentAction(7, 'test', {}, form('3'))).toEqual({ error: 'boom' });
  });

  it('切離しに成功すると詳細ページとダッシュボードを再検証する', async () => {
    await unbindEnvironmentAction(7, 'test');

    expect(unbindProjectEnvironment).toHaveBeenCalledWith(7, 'test');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7/dashboard');
  });
});
