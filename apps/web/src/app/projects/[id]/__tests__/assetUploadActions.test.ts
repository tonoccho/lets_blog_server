/**
 * @jest-environment node
 */

/**
 * issue #1764: アセット画像の全環境アップロードのServer Actionは、完了を待たずジョブとして受理した時点で返る。
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
import { bodies, failFetchWith, failWith, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { uploadProjectAssetImageAction } from '../actions';

const JOBS = 'POST /api/projects/7/asset-images/11/upload/jobs';

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [JOBS]: () => jsonResponse({ id: 61, status: 'running' }) });
});

describe('uploadProjectAssetImageAction (#1764)', () => {
  it('管理者セッションを要求し、ジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    await expect(uploadProjectAssetImageAction(7, 11)).resolves.toEqual({ jobId: 61 });

    expect(bodies(backend, JOBS)).toHaveLength(1);
  });

  it('受理口だけを呼び(同期APIは呼ばない)、完了していないのでプロジェクトのページを再検証しない', async () => {
    await uploadProjectAssetImageAction(7, 11);
    expect(requestCount(backend)).toBe(1);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    await expect(uploadProjectAssetImageAction(7, 11)).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });

  it('待ち行列が満杯(ジョブが failed で返る)なら、その旨のエラーを返す', async () => {
    installBackend({ [JOBS]: () => jsonResponse({ id: 61, status: 'failed' }) });

    const result = await uploadProjectAssetImageAction(7, 11);

    expect(result.jobId).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('受理口のエラーはメッセージを返す(Errorでなくても)', async () => {
    installBackend({ [JOBS]: failWith(404, 'not found') });
    await expect(uploadProjectAssetImageAction(7, 11)).resolves.toEqual({ error: 'APIエラー (404): not found' });
    failFetchWith('boom');
    await expect(uploadProjectAssetImageAction(7, 11)).resolves.toEqual({ error: 'boom' });
  });
});
