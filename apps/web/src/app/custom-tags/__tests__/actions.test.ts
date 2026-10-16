/**
 * @jest-environment node
 */

/**
 * issue #1409: カスタムタグのAI生成を非同期ジョブとして要求する Server Action。生成と同時に保存せず、
 * 受理されたジョブ(ID・状態)だけを返す。結果は処理キューの「結果を見る」で確認し「保存」で登録する。
 *
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch)だけ(#1834)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { generateCustomTagAction, validateCustomTagAction } from '../actions';
import { ADMIN_SESSION, USER_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime';
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';

const GENERATE = 'POST /api/custom-tags/generate/jobs';
const VALIDATE = 'POST /api/custom-tags/validate';
let fetchMock: jest.Mock;
const calls = (path: string) =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith(path) && init?.method === 'POST')
    .map(([, init]) => JSON.parse(String(init.body)));

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
});

describe('generateCustomTagAction(issue #1409)', () => {
  it('管理者を確認し、非同期の入口へ要求を渡して、ジョブIDと状態を返す', async () => {
    fetchMock = installFetch({ [GENERATE]: () => jsonResponse({ id: 21, type: 'custom_tag_generation', status: 'running' }) });
    const input = { prompt: 'p', tagName: 't', projectId: 7 };

    await expect(generateCustomTagAction(input)).resolves.toEqual({ jobId: 21, status: 'running' });

    expect(calls('/api/custom-tags/generate/jobs')).toEqual([input]);
  });

  it('管理者でなければバックエンドを呼ばずログイン後の遷移を促す', async () => {
    resetServerRuntime(USER_SESSION);
    fetchMock = installFetch({ [GENERATE]: () => jsonResponse({ id: 21, status: 'running' }) });

    await expect(generateCustomTagAction({ prompt: 'p', tagName: 't' })).rejects.toThrow('NEXT_REDIRECT:/');
    expect(calls('/api/custom-tags/generate/jobs')).toEqual([]);
  });

  it('受理を拒否されたら理由を error として返す', async () => {
    fetchMock = installFetch({ [GENERATE]: () => textResponse('権限がありません', 403) });
    await expect(generateCustomTagAction({ prompt: 'p', tagName: 't' })).resolves.toEqual({
      error: 'APIエラー (403): 権限がありません',
    });
  });

  it('通信そのものが Error 以外の値で失敗しても、文字列にして error として返す', async () => {
    fetchMock = installFetch({
      [GENERATE]: () => {
        throw 'boom';
      },
    });
    await expect(generateCustomTagAction({ prompt: 'p', tagName: 't' })).resolves.toEqual({ error: 'boom' });
  });
});

describe('validateCustomTagAction(既存の挙動)', () => {
  it('検証結果を data で返す', async () => {
    fetchMock = installFetch({ [VALIDATE]: () => jsonResponse({ isValid: true, errors: [], warnings: [] }) });
    await expect(validateCustomTagAction({ htmlTemplate: '<b/>' })).resolves.toEqual({
      data: { isValid: true, errors: [], warnings: [] },
    });
    expect(calls('/api/custom-tags/validate')).toEqual([{ htmlTemplate: '<b/>' }]);
  });

  it('失敗を error で返し、Error 以外の失敗も文字列にする', async () => {
    fetchMock = installFetch({ [VALIDATE]: () => textResponse('x', 500) });
    await expect(validateCustomTagAction({ htmlTemplate: '<b/>' })).resolves.toEqual({ error: 'APIエラー (500): x' });

    fetchMock = installFetch({
      [VALIDATE]: () => {
        throw 'boom';
      },
    });
    await expect(validateCustomTagAction({ htmlTemplate: '<b/>' })).resolves.toEqual({ error: 'boom' });
  });

  it('管理者でなければバックエンドを呼ばない', async () => {
    resetServerRuntime(null);
    fetchMock = installFetch({ [VALIDATE]: () => jsonResponse({}) });
    await expect(validateCustomTagAction({ htmlTemplate: '<b/>' })).rejects.toThrow('NEXT_REDIRECT:/login');
    expect(calls('/api/custom-tags/validate')).toEqual([]);
  });
});
