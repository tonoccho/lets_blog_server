/**
 * issue #784: 自ユーザーの解決が、Keycloakの`sub`(UUID)を数値ユーザーIDへ変換する経路に
 * 依存していないことの検証。
 *
 * 変換前は `Number(session.user.id)` が必ず `NaN` になり、`GET /api/users/NaN` が
 * 24時間で203件発生していた(うち151件が400、52件が429)。しかも `catch {}` で握り潰されて
 * いたため画面には何も出ず、長く気付けなかった。ここでは
 * (a) identity-service の自ユーザーAPIを使うこと、(b) 失敗を握り潰さずログに残すこと、
 * を固定する。
 */
jest.mock('server-only', () => ({}));
// 自前の @/lib/auth・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤
// (next-auth の getServerSession、next/headers、next/server、next-auth/jwt)と、
// 外部との通信(fetch)だけ(#1832)。
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule);
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule);
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule);
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule);

import { getViewerProfile, getViewerTimeZone } from '@/lib/session';
import {
  getServerSessionMock as getServerSession,
  installFetch,
  jsonResponse,
  resetServerRuntime,
} from '@/lib/__tests__/testUtils/serverRuntime';

const SESSION = { user: { id: 'a3f1c9e2-0000-4444-8888-1b2c3d4e5f60', role: 'user' } };

function identityMe(handler: () => Response | Promise<Response>) {
  return installFetch({ 'GET /api/identity/me': handler });
}

describe('getViewerProfile / getViewerTimeZone (issue #784)', () => {
  beforeEach(() => {
    resetServerRuntime(null);
  });

  it('自ユーザーAPIから取得する(数値ユーザーIDを引数に取らない)', async () => {
    getServerSession.mockResolvedValue(SESSION);
    const fetchMock = identityMe(() => jsonResponse({ id: 7, timezone: 'America/New_York' }));

    const profile = await getViewerProfile();

    expect(profile).toEqual({ id: 7, timezone: 'America/New_York' });
    // identity-service の自ユーザーAPIを1回だけ呼ぶ(操作ログの記録は別)。
    const profileCalls = fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/api/identity/me'));
    expect(profileCalls).toHaveLength(1);
    // Keycloakのsub(UUID)がURLに載っていないこと(`/api/users/<sub>` や `/api/users/NaN` ではない)。
    expect(String(profileCalls[0][0])).not.toContain(SESSION.user.id);
    expect(String(profileCalls[0][0])).not.toContain('NaN');
  });

  it('未ログインならAPIを呼ばずnullを返す', async () => {
    getServerSession.mockResolvedValue(null);
    const fetchMock = identityMe(() => jsonResponse({}));

    expect(await getViewerProfile()).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('取得に失敗したらnullを返すが、握り潰さずログに残す', async () => {
    getServerSession.mockResolvedValue(SESSION);
    identityMe(() => {
      throw new Error('boom');
    });
    const spy = jest.spyOn(console, 'error').mockImplementation(() => {});

    expect(await getViewerProfile()).toBeNull();
    expect(spy).toHaveBeenCalled();

    spy.mockRestore();
  });

  it('タイムゾーンはプロフィールの値を返す', async () => {
    getServerSession.mockResolvedValue(SESSION);
    identityMe(() => jsonResponse({ id: 7, timezone: 'Europe/Berlin' }));

    expect(await getViewerTimeZone()).toBe('Europe/Berlin');
  });

  it('タイムゾーン未設定ならnull(呼び出し側がブラウザのローカルTZへフォールバックする)', async () => {
    getServerSession.mockResolvedValue(SESSION);
    identityMe(() => jsonResponse({ id: 7, timezone: null }));

    expect(await getViewerTimeZone()).toBeNull();
  });
});
