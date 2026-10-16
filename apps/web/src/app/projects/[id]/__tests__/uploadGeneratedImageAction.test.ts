/**
 * @jest-environment node
 */

/**
 * issue #1599: アセット画像パネルの「画像をアップロード」Server Actionの検証。
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
import { bodies, calledRequests, failFetchWith, failWith, installBackend, jsonResponse, requestCount } from './projectCoreRuntime';
import { uploadGeneratedImageAction } from '../actions';

const UPLOAD = 'POST /api/generated-images/upload';

function formWith(file?: File): FormData {
  const formData = new FormData();
  if (file) formData.append('file', file);
  return formData;
}

let backend: jest.Mock;
beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
  backend = installBackend({ [UPLOAD]: () => jsonResponse({ id: 31, provider: 'UPLOAD' }) });
});

describe('uploadGeneratedImageAction(issue #1599)', () => {
  it('管理者を確認し、ファイルを projectId 付きで登録して画像IDを返し、ギャラリーを再検証する', async () => {
    const file = new File([new Uint8Array([1, 2])], 'a.png', { type: 'image/png' });

    await expect(uploadGeneratedImageAction(7, formWith(file))).resolves.toEqual({ imageId: 31 });

    const [sent] = bodies(backend, UPLOAD) as FormData[];
    expect((sent.get('file') as File).name).toBe('a.png');
    expect(calledRequests(backend)).toEqual(['POST /api/generated-images/upload?projectId=7']);
    expect(revalidatePath).toHaveBeenCalledWith('/image-gallery');
  });

  it('ファイルが無い・空なら API を呼ばずエラーを返す', async () => {
    await expect(uploadGeneratedImageAction(7, formWith())).resolves.toEqual({
      error: '画像ファイルを選択してください。',
    });
    await expect(uploadGeneratedImageAction(7, formWith(new File([], 'empty.png')))).resolves.toEqual({
      error: '画像ファイルを選択してください。',
    });
    expect(requestCount(backend)).toBe(0);
  });

  it('API が拒否したら、その理由を error として返し、再検証しない(Error 以外も文字列にする)', async () => {
    installBackend({ [UPLOAD]: failWith(400, '対応していない画像形式です') });
    const file = new File([new Uint8Array([1])], 'a.gif', { type: 'image/gif' });
    await expect(uploadGeneratedImageAction(7, formWith(file))).resolves.toEqual({
      error: 'APIエラー (400): 対応していない画像形式です',
    });

    failFetchWith('plain');
    await expect(uploadGeneratedImageAction(7, formWith(file))).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('管理者でなければ API を呼ばない', async () => {
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });
    const file = new File([new Uint8Array([1])], 'a.png', { type: 'image/png' });
    await expect(uploadGeneratedImageAction(7, formWith(file))).rejects.toThrow('NEXT_REDIRECT:/');
    expect(requestCount(backend)).toBe(0);
  });
});
