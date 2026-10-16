/**
 * @jest-environment node
 */

/**
 * プロジェクト詳細の基本設定・APIキー・メンバー追加の Server Action の検証(#1836)。
 * `../actions` の実体を直接importし、`@/lib/apiClient`・`@/lib/session` も実物を通す。
 * 差し替えるのは Next.js の実行基盤と外部との通信(`fetch`)だけ。
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
import { bodies, failFetchWith, failWith, form, installBackend, noContent, requestCount } from './projectCoreRuntime';
import {
  addProjectUserAction,
  setProjectBraveSearchApiKeyAction,
  setProjectGithubTokenAction,
  updateMasterEnvironmentAction,
  updateProjectGithubRepositoryAction,
  updateProjectNameAction,
} from '../actions';

beforeEach(() => {
  jest.clearAllMocks();
  resetServerRuntime(ADMIN_SESSION);
});

describe.each([
  {
    name: 'updateProjectNameAction',
    route: 'PUT /api/projects/7',
    run: (data: FormData) => updateProjectNameAction(7, {}, data),
    valid: form({ name: ' 新名 ' }),
    body: { name: '新名' },
    missing: new FormData(),
    missingError: 'プロジェクト名を入力してください。',
    revalidated: ['/projects/7', '/projects'],
  },
  {
    name: 'updateMasterEnvironmentAction',
    route: 'PUT /api/projects/7/master-environment',
    run: (data: FormData) => updateMasterEnvironmentAction(7, {}, data),
    valid: form({ masterEnvironment: 'production' }),
    body: { masterEnvironment: 'production' },
    missing: form({ masterEnvironment: 'local' }),
    missingError: 'テスト環境または本番環境を選択してください。',
    revalidated: ['/projects/7'],
  },
  {
    name: 'setProjectGithubTokenAction',
    route: 'PUT /api/projects/7/api-keys/github-token',
    run: (data: FormData) => setProjectGithubTokenAction(7, {}, data),
    valid: form({ githubToken: ' ghp_x ' }),
    body: { githubToken: 'ghp_x' },
    missing: new FormData(),
    missingError: 'GitHubトークンを入力してください。',
    revalidated: ['/projects/7'],
  },
  {
    name: 'setProjectBraveSearchApiKeyAction',
    route: 'PUT /api/projects/7/api-keys/brave-search-api-key',
    run: (data: FormData) => setProjectBraveSearchApiKeyAction(7, {}, data),
    valid: form({ apiKey: ' BSA1 ' }),
    body: { apiKey: 'BSA1' },
    missing: new FormData(),
    missingError: 'APIキーを入力してください。',
    revalidated: ['/projects/7'],
  },
  {
    name: 'addProjectUserAction',
    route: 'POST /api/projects/7/users',
    run: (data: FormData) => addProjectUserAction(7, {}, data),
    valid: form({ userId: '3', wpRole: ' editor ' }),
    body: { userId: 3, wpRole: 'editor' },
    missing: new FormData(),
    missingError: 'ユーザーとロールを選択してください。',
    revalidated: ['/projects/7'],
  },
])('$name', ({ route, run, valid, body, missing, missingError, revalidated }) => {
  it('入力をトリムして送り、成功を返して画面を再検証する', async () => {
    const backend = installBackend({ [route]: noContent });

    await expect(run(valid)).resolves.toEqual({ success: true });

    expect(bodies(backend, route)).toEqual([body]);
    for (const path of revalidated) expect(revalidatePath).toHaveBeenCalledWith(path);
  });

  it('必要な入力が無い(欄そのものが無い場合を含む)ならバックエンドへ送らずエラーを返す', async () => {
    const backend = installBackend({ [route]: noContent });

    await expect(run(missing)).resolves.toEqual({ error: missingError });

    expect(requestCount(backend)).toBe(0);
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('バックエンドが拒否したら理由を返し、再検証しない(Error でない失敗も文字列にする)', async () => {
    installBackend({ [route]: failWith(409, 'conflict') });
    await expect(run(valid)).resolves.toEqual({ error: 'APIエラー (409): conflict' });

    failFetchWith('plain');
    await expect(run(valid)).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('管理者でなければ / へ戻し、バックエンドへ問い合わせない', async () => {
    const backend = installBackend({ [route]: noContent });
    resetServerRuntime({ user: { id: 'u-1', role: 'user' } });

    await expect(run(valid)).rejects.toThrow('NEXT_REDIRECT:/');

    expect(requestCount(backend)).toBe(0);
  });
});

describe('updateMasterEnvironmentAction(欄が無い場合)', () => {
  it('マスター環境の欄そのものが無いときも、バックエンドへ送らずエラーを返す', async () => {
    const backend = installBackend({});

    await expect(updateMasterEnvironmentAction(7, {}, new FormData())).resolves.toEqual({
      error: 'テスト環境または本番環境を選択してください。',
    });

    expect(requestCount(backend)).toBe(0);
  });
});

describe('updateProjectGithubRepositoryAction', () => {
  const REPO = 'PUT /api/projects/7/github-repository';

  it('リポジトリをトリムして送り、成功を返して画面を再検証する', async () => {
    const backend = installBackend({ [REPO]: noContent });

    await expect(updateProjectGithubRepositoryAction(7, {}, form({ githubRepository: ' o/r ' }))).resolves.toEqual({ success: true });

    expect(bodies(backend, REPO)).toEqual([{ githubRepository: 'o/r' }]);
    expect(revalidatePath).toHaveBeenCalledWith('/projects/7');
  });

  it('空(欄そのものが無い場合を含む)は、紐付けの解除として空文字を送る', async () => {
    const backend = installBackend({ [REPO]: noContent });

    await expect(updateProjectGithubRepositoryAction(7, {}, new FormData())).resolves.toEqual({ success: true });

    expect(bodies(backend, REPO)).toEqual([{ githubRepository: '' }]);
  });

  it('バックエンドが拒否したら理由を返し、再検証しない(Error でない失敗も文字列にする)', async () => {
    installBackend({ [REPO]: failWith(404, 'not found') });
    await expect(updateProjectGithubRepositoryAction(7, {}, form({ githubRepository: 'o/r' }))).resolves.toEqual({
      error: 'APIエラー (404): not found',
    });

    failFetchWith('plain');
    await expect(updateProjectGithubRepositoryAction(7, {}, form({ githubRepository: 'o/r' }))).resolves.toEqual({ error: 'plain' });
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
