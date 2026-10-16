/**
 * @jest-environment node
 */

/**
 * issue #1702: 全環境インストール(slug / zip)のServer Actionは、完了を待たずジョブとして受理した時点で返る。
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
import { applyToAllEnvironmentsAction, runBulkOperationUploadAction } from '../actions';

const SLUG_JOBS = 'POST /api/projects/7/bulk-management/apply-all/jobs';
const ZIP_JOBS = 'POST /api/projects/7/bulk-management/upload/jobs';

function slugForm(operationType: string, value: string): FormData {
  const data = new FormData();
  data.set('operationType', operationType);
  data.set('value', value);
  return data;
}

function zipForm(operationType: string, file: File | null): FormData {
  const data = new FormData();
  data.set('operationType', operationType);
  if (file) data.set('file', file);
  return data;
}

const ZIP = new File(['zip'], 'plugin.zip', { type: 'application/zip' });

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({
    [SLUG_JOBS]: () => jsonResponse({ id: 51, status: 'running' }),
    [ZIP_JOBS]: () => jsonResponse({ id: 52, status: 'running' }),
  });
});

describe('applyToAllEnvironmentsAction (#1702)', () => {
  it('管理者セッションを要求し、ジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    await expect(applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', ' akismet '))).resolves.toEqual({
      success: true,
      jobId: 51,
    });

    expect(bodies(backend, SLUG_JOBS)).toEqual([{ operationType: 'PLUGIN_INSTALL', value: 'akismet' }]);
  });

  it('受理口だけを呼び(同期APIは呼ばない)、完了していないのでプロジェクトのページを再検証しない', async () => {
    await applyToAllEnvironmentsAction(7, {}, slugForm('THEME_INSTALL', 'twentytwentyfive'));
    expect(requestCount(backend)).toBe(1);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('待ち行列が満杯(ジョブが failed で返る)なら、その旨のエラーを返す', async () => {
    installBackend({ [SLUG_JOBS]: () => jsonResponse({ id: 51, status: 'failed' }) });

    const result = await applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', 'akismet'));

    expect(result.success).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('インストール以外の操作は受理口を呼ばずにエラーにする', async () => {
    const result = await applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_ACTIVATE', 'akismet'));
    expect(result.error).toContain('インストールのみ');
    expect(requestCount(backend)).toBe(0);
  });

  it('操作種別の欄そのものが無いときもインストール以外として扱う', async () => {
    const result = await applyToAllEnvironmentsAction(7, {}, new FormData());
    expect(result.error).toContain('インストールのみ');
    expect(requestCount(backend)).toBe(0);
  });

  it('slugが空なら受理口を呼ばずにエラーにする', async () => {
    const result = await applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', '  '));
    expect(result.error).toContain('slug');
    expect(requestCount(backend)).toBe(0);
  });

  it('値が送られていない(valueが無い)ときもslug未入力として扱う', async () => {
    const data = new FormData();
    data.set('operationType', 'PLUGIN_INSTALL');
    const result = await applyToAllEnvironmentsAction(7, {}, data);
    expect(result.error).toContain('slug');
    expect(requestCount(backend)).toBe(0);
  });

  it('受理口のエラーはメッセージを返す(Errorでなくても)', async () => {
    installBackend({ [SLUG_JOBS]: failWith(400, 'bad') });
    await expect(applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', 'a'))).resolves.toEqual({
      error: 'APIエラー (400): bad',
    });
    failFetchWith('boom');
    await expect(applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', 'a'))).resolves.toEqual({ error: 'boom' });
  });
});

describe('runBulkOperationUploadAction (#1702)', () => {
  it('管理者セッションを要求し、zipをジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    await expect(runBulkOperationUploadAction(7, {}, zipForm('THEME_INSTALL', ZIP))).resolves.toEqual({
      success: true,
      jobId: 52,
    });

    const [sent] = bodies(backend, ZIP_JOBS) as FormData[];
    expect(sent.get('operationType')).toBe('THEME_INSTALL');
    expect((sent.get('file') as File).name).toBe('plugin.zip');
  });

  it('受理口だけを呼び(同期APIは呼ばない)、プロジェクトのページを再検証しない', async () => {
    await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP));
    expect(requestCount(backend)).toBe(1);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('待ち行列が満杯(ジョブが failed で返る)なら、その旨のエラーを返す', async () => {
    installBackend({ [ZIP_JOBS]: () => jsonResponse({ id: 52, status: 'failed' }) });

    const result = await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP));

    expect(result.success).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('インストール以外の操作は受理口を呼ばずにエラーにする', async () => {
    const result = await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_DELETE', ZIP));
    expect(result.error).toContain('インストールのみ');
    expect(requestCount(backend)).toBe(0);
  });

  it('操作種別の欄そのものが無いときもインストール以外として扱う', async () => {
    const result = await runBulkOperationUploadAction(7, {}, new FormData());
    expect(result.error).toContain('インストールのみ');
    expect(requestCount(backend)).toBe(0);
  });

  it('ファイルが無い・空なら受理口を呼ばずにエラーにする', async () => {
    expect((await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', null))).error).toContain('zip');
    expect(
      (await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', new File([], 'e.zip')))).error
    ).toContain('zip');
    expect(requestCount(backend)).toBe(0);
  });

  it('受理口のエラーはメッセージを返す(Errorでなくても)', async () => {
    installBackend({ [ZIP_JOBS]: failWith(413, 'too large') });
    await expect(runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP))).resolves.toEqual({
      error: 'APIエラー (413): too large',
    });
    failFetchWith('boom');
    await expect(runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP))).resolves.toEqual({ error: 'boom' });
  });
});
