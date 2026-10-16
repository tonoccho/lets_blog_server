/**
 * @jest-environment node
 *
 * 初期管理者を作る Server Action。意図的に未認証(まだ誰もアカウントを持たない状態で実行する)。
 * 自前の @/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1832)。
 */
jest.mock('server-only', () => ({}));
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);

import { setupAction } from '../actions';
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime';

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

const SETUP = 'POST /api/auth/setup';

describe('setupAction', () => {
  beforeEach(() => {
    resetServerRuntime(null);
  });

  it.each([
    ['メールアドレスが空', { email: '', password: 'password1234' }],
    ['パスワードが空', { email: 'admin@example.com', password: '' }],
    ['空白だけ', { email: '   ', password: '   ' }],
    ['項目が無い', {}],
  ])('%s なら入力を求め、サーバーへは送らない', async (_label, fields) => {
    const fetchMock = installFetch({ [SETUP]: () => jsonResponse({}) });

    await expect(setupAction({}, form(fields))).resolves.toEqual({
      error: 'メールアドレスとパスワードを入力してください。',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('パスワードが8文字未満なら、サーバーへは送らずに弾く', async () => {
    const fetchMock = installFetch({ [SETUP]: () => jsonResponse({}) });

    await expect(setupAction({}, form({ email: 'admin@example.com', password: '1234567' }))).resolves.toEqual({
      error: 'パスワードは8文字以上である必要があります。',
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('前後の空白を除いて、認証なしでサーバーへ送る。成功なら空の状態を返す', async () => {
    const fetchMock = installFetch({ [SETUP]: () => jsonResponse({ id: 1 }, 201) });

    await expect(setupAction({}, form({ email: ' admin@example.com ', password: ' password1234 ' }))).resolves.toEqual({});

    const [url, init] = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/api/auth/setup'))!;
    expect(url).toBe('https://localhost/api/auth/setup');
    expect(JSON.parse(init.body)).toEqual({ email: 'admin@example.com', password: 'password1234' });
    expect(init.headers.Authorization).toBeUndefined();
  });

  it('サーバーが拒否したら、その理由を返す', async () => {
    installFetch({ [SETUP]: () => textResponse('既にセットアップ済みです', 409) });

    const state = await setupAction({}, form({ email: 'admin@example.com', password: 'password1234' }));

    expect(state.error).toContain('409');
    expect(state.error).toContain('既にセットアップ済みです');
  });

  it('Error 以外が投げられても、文字列にして返す', async () => {
    installFetch({
      [SETUP]: () => {
        throw 'network down';
      },
    });

    await expect(setupAction({}, form({ email: 'admin@example.com', password: 'password1234' }))).resolves.toEqual({
      error: 'network down',
    });
  });
});
