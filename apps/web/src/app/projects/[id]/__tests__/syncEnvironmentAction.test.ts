/**
 * @jest-environment node
 */

/**
 * issue #1697: 環境間同期のServer Actionは、同期の完了を待たずジョブとして受理した時点で返る。
 * `../actions` の実体を直接importし、`@/lib/apiClient`・`@/lib/session` も実物を通す。
 * 差し替えるのは Next.js の実行基盤と外部との通信(`fetch`)だけ(#1836)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

import { resetServerRuntime } from '@/lib/__tests__/testUtils/serverRuntime';
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { bodies, failFetchWith, failWith, form as keyedForm, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { syncEnvironmentAction } from '../actions';

const JOBS = 'POST /api/projects/7/environments/sync/jobs';

function form(from: string, to: string, targets: string[]): FormData {
  const data = keyedForm({ from, to });
  targets.forEach((t) => data.append('targets', t));
  return data;
}

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [JOBS]: () => jsonResponse({ id: 41, status: 'running' }) });
});

describe('syncEnvironmentAction (#1697)', () => {
  it('管理者セッションを要求し、ジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    await expect(syncEnvironmentAction(7, {}, form('test', 'local', ['db', 'media']))).resolves.toEqual({
      success: true,
      jobId: 41,
    });

    expect(bodies(backend, JOBS)).toEqual([{ from: 'test', to: 'local', targets: ['db', 'media'] }]);
  });

  it('受理口だけを呼び(同期APIは呼ばない)、同期は終わっていないのでプロジェクトのページを再検証しない', async () => {
    await syncEnvironmentAction(7, {}, form('test', 'local', ['db']));
    expect(requestCount(backend)).toBe(1);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(syncEnvironmentAction(7, {}, form('test', 'local', ['db']))).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });

  it.each([
    ['同期元が空', form('', 'local', ['db']), '同期元・同期先の環境を選択してください。'],
    ['同期先が空', form('test', '', ['db']), '同期元・同期先の環境を選択してください。'],
    ['同期元と同期先が同じ', form('test', 'test', ['db']), '同期元と同期先には異なる環境を指定してください。'],
    ['対象が無い', form('test', 'local', []), '同期する対象(テーマ/プラグイン/メディア/DB)を1つ以上選択してください。'],
  ])('%s ならジョブを作らずエラーを返す', async (_label, data, message) => {
    await expect(syncEnvironmentAction(7, {}, data)).resolves.toEqual({ error: message });
    expect(requestCount(backend)).toBe(0);
  });

  it('同期元・同期先の欄そのものが無くてもエラーを返す', async () => {
    await expect(syncEnvironmentAction(7, {}, new FormData())).resolves.toEqual({
      error: '同期元・同期先の環境を選択してください。',
    });
    expect(requestCount(backend)).toBe(0);
  });

  it('待ち行列が満杯でジョブが failed で返ったら、受理ではなくエラーとして返す', async () => {
    installBackend({ [JOBS]: () => jsonResponse({ id: 41, status: 'failed' }) });

    const result = await syncEnvironmentAction(7, {}, form('test', 'local', ['db']));

    expect(result.success).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('API が失敗したらそのメッセージを返す', async () => {
    installBackend({ [JOBS]: failWith(500, 'boom') });
    await expect(syncEnvironmentAction(7, {}, form('test', 'local', ['db']))).resolves.toEqual({
      error: 'APIエラー (500): boom',
    });
  });

  it('Error でない例外も文字列にして返す', async () => {
    failFetchWith('plain');
    await expect(syncEnvironmentAction(7, {}, form('test', 'local', ['db']))).resolves.toEqual({ error: 'plain' });
  });
});
