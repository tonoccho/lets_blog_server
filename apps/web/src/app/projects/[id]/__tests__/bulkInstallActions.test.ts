/**
 * @jest-environment node
 */

/**
 * issue #1702: 全環境インストール(slug / zip)のServer Actionは、完了を待たずジョブとして受理した時点で返る。
 * `syncEnvironmentAction.test.ts`(#1697)と同じ方針で `../actions` を直接importして検証する。
 */
jest.mock('server-only', () => ({}));
const revalidatePath = jest.fn();
jest.mock('next/cache', () => ({ revalidatePath: (...args: unknown[]) => revalidatePath(...args) }));

const requireAdminSession = jest.fn();
jest.mock('@/lib/session', () => ({
  requireAdminSession: (...args: unknown[]) => requireAdminSession(...args),
}));

const startBulkSlugInstallJob = jest.fn();
const startBulkZipInstallJob = jest.fn();
const applyToAllEnvironments = jest.fn();
const runBulkOperationUpload = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  ...jest.requireActual('@/lib/apiClient'),
  startBulkSlugInstallJob: (...args: unknown[]) => startBulkSlugInstallJob(...args),
  startBulkZipInstallJob: (...args: unknown[]) => startBulkZipInstallJob(...args),
  applyToAllEnvironments: (...args: unknown[]) => applyToAllEnvironments(...args),
  runBulkOperationUpload: (...args: unknown[]) => runBulkOperationUpload(...args),
}));

import { applyToAllEnvironmentsAction, runBulkOperationUploadAction } from '../actions';

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

beforeEach(() => {
  jest.clearAllMocks();
  requireAdminSession.mockResolvedValue({ user: { role: 'admin' } });
});

describe('applyToAllEnvironmentsAction (#1702)', () => {
  it('管理者セッションを要求し、ジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    startBulkSlugInstallJob.mockResolvedValue({ id: 51, status: 'running' });

    await expect(applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', ' akismet '))).resolves.toEqual({
      success: true,
      jobId: 51,
    });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startBulkSlugInstallJob).toHaveBeenCalledWith(7, { operationType: 'PLUGIN_INSTALL', value: 'akismet' });
  });

  it('同期APIは呼ばず、完了していないのでプロジェクトのページを再検証しない', async () => {
    startBulkSlugInstallJob.mockResolvedValue({ id: 51, status: 'running' });
    await applyToAllEnvironmentsAction(7, {}, slugForm('THEME_INSTALL', 'twentytwentyfive'));
    expect(applyToAllEnvironments).not.toHaveBeenCalled();
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('待ち行列が満杯(ジョブが failed で返る)なら、その旨のエラーを返す', async () => {
    startBulkSlugInstallJob.mockResolvedValue({ id: 51, status: 'failed' });

    const result = await applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', 'akismet'));

    expect(result.success).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('インストール以外の操作は受理口を呼ばずにエラーにする', async () => {
    const result = await applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_ACTIVATE', 'akismet'));
    expect(result.error).toContain('インストールのみ');
    expect(startBulkSlugInstallJob).not.toHaveBeenCalled();
  });

  it('slugが空なら受理口を呼ばずにエラーにする', async () => {
    const result = await applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', '  '));
    expect(result.error).toContain('slug');
    expect(startBulkSlugInstallJob).not.toHaveBeenCalled();
  });

  it('受理口のエラーはメッセージを返す(Errorでなくても)', async () => {
    startBulkSlugInstallJob.mockRejectedValueOnce(new Error('400 bad'));
    await expect(applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', 'a'))).resolves.toEqual({ error: '400 bad' });
    startBulkSlugInstallJob.mockRejectedValueOnce('boom');
    await expect(applyToAllEnvironmentsAction(7, {}, slugForm('PLUGIN_INSTALL', 'a'))).resolves.toEqual({ error: 'boom' });
  });
});

describe('runBulkOperationUploadAction (#1702)', () => {
  it('管理者セッションを要求し、zipをジョブとして受理して完了を待たずにジョブIDを返す', async () => {
    startBulkZipInstallJob.mockResolvedValue({ id: 52, status: 'running' });

    await expect(runBulkOperationUploadAction(7, {}, zipForm('THEME_INSTALL', ZIP))).resolves.toEqual({
      success: true,
      jobId: 52,
    });

    expect(requireAdminSession).toHaveBeenCalled();
    expect(startBulkZipInstallJob).toHaveBeenCalledWith(7, { operationType: 'THEME_INSTALL', file: expect.any(File) });
  });

  it('同期APIは呼ばず、プロジェクトのページを再検証しない', async () => {
    startBulkZipInstallJob.mockResolvedValue({ id: 52, status: 'running' });
    await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP));
    expect(runBulkOperationUpload).not.toHaveBeenCalled();
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('待ち行列が満杯(ジョブが failed で返る)なら、その旨のエラーを返す', async () => {
    startBulkZipInstallJob.mockResolvedValue({ id: 52, status: 'failed' });

    const result = await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP));

    expect(result.success).toBeUndefined();
    expect(result.error).toContain('待ち行列が満杯');
  });

  it('インストール以外の操作は受理口を呼ばずにエラーにする', async () => {
    const result = await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_DELETE', ZIP));
    expect(result.error).toContain('インストールのみ');
    expect(startBulkZipInstallJob).not.toHaveBeenCalled();
  });

  it('ファイルが無い・空なら受理口を呼ばずにエラーにする', async () => {
    expect((await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', null))).error).toContain('zip');
    expect(
      (await runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', new File([], 'e.zip')))).error
    ).toContain('zip');
    expect(startBulkZipInstallJob).not.toHaveBeenCalled();
  });

  it('受理口のエラーはメッセージを返す(Errorでなくても)', async () => {
    startBulkZipInstallJob.mockRejectedValueOnce(new Error('413 too large'));
    await expect(runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP))).resolves.toEqual({ error: '413 too large' });
    startBulkZipInstallJob.mockRejectedValueOnce('boom');
    await expect(runBulkOperationUploadAction(7, {}, zipForm('PLUGIN_INSTALL', ZIP))).resolves.toEqual({ error: 'boom' });
  });
});
