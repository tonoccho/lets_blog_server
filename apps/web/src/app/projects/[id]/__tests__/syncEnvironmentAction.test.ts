/**
 * @jest-environment node
 */

/**
 * issue #1697: 環境間同期のServer Actionは、同期の完了を待たずジョブとして受理した時点で返る。
 * `createManagedWordPressSiteAction.test.ts`(#1696)と同じ方針で `../actions` を直接importして検証する。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startProjectEnvironmentSyncJob = jest.fn();
const syncProjectEnvironment = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  startProjectEnvironmentSyncJob: (...args: unknown[]) => startProjectEnvironmentSyncJob(...args),
  syncProjectEnvironment: (...args: unknown[]) => syncProjectEnvironment(...args),
}));

import { syncEnvironmentAction } from '../actions';

function form(from: string, to: string, targets: string[]): FormData {
  const data = new FormData();
  data.set('from', from);
  data.set('to', to);
  targets.forEach((t) => data.append('targets', t));
  return data;
}

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('syncEnvironmentAction (#1697)', () => {
  it('管理者セッションを要求し、ジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    startProjectEnvironmentSyncJob.mockResolvedValue({ id: 41, status: 'running' });

    await expect(syncEnvironmentAction(7, {}, form('test', 'local', ['db', 'media']))).resolves.toEqual({
      success: true,
      jobId: 41,
    });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startProjectEnvironmentSyncJob).toHaveBeenCalledWith(7, { from: 'test', to: 'local', targets: ['db', 'media'] });
  });

  it('同期APIは呼ばない', async () => {
    startProjectEnvironmentSyncJob.mockResolvedValue({ id: 41, status: 'running' });
    await syncEnvironmentAction(7, {}, form('test', 'local', ['db']));
    expect(syncProjectEnvironment).not.toHaveBeenCalled();
  });

  it('同期は終わっていないので、プロジェクトのページを再検証しない', async () => {
    startProjectEnvironmentSyncJob.mockResolvedValue({ id: 41, status: 'running' });
    await syncEnvironmentAction(7, {}, form('test', 'local', ['db']));
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it.each([
    ['同期元が空', form('', 'local', ['db']), '同期元・同期先の環境を選択してください。'],
    ['同期先が空', form('test', '', ['db']), '同期元・同期先の環境を選択してください。'],
    ['同期元と同期先が同じ', form('test', 'test', ['db']), '同期元と同期先には異なる環境を指定してください。'],
    ['対象が無い', form('test', 'local', []), '同期する対象(テーマ/プラグイン/メディア/DB)を1つ以上選択してください。'],
  ])('%s ならジョブを作らずエラーを返す', async (_label, data, message) => {
    await expect(syncEnvironmentAction(7, {}, data)).resolves.toEqual({ error: message });
    expect(startProjectEnvironmentSyncJob).not.toHaveBeenCalled();
  });

  it('待ち行列が満杯でジョブが failed で返ったら、受理ではなくエラーとして返す', async () => {
    startProjectEnvironmentSyncJob.mockResolvedValue({ id: 41, status: 'failed' });

    const result = await syncEnvironmentAction(7, {}, form('test', 'local', ['db']));

    expect(result.success).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('API が失敗したらそのメッセージを返す', async () => {
    startProjectEnvironmentSyncJob.mockRejectedValue(new Error('boom'));
    await expect(syncEnvironmentAction(7, {}, form('test', 'local', ['db']))).resolves.toEqual({ error: 'boom' });
  });

  it('Error でない例外も文字列にして返す', async () => {
    startProjectEnvironmentSyncJob.mockRejectedValue('plain');
    await expect(syncEnvironmentAction(7, {}, form('test', 'local', ['db']))).resolves.toEqual({ error: 'plain' });
  });
});
