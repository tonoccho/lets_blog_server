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

const getServerSession = jest.fn();
jest.mock('next-auth', () => ({
  getServerSession: (...args: unknown[]) => getServerSession(...args),
}));
jest.mock('@/lib/auth', () => ({ authOptions: {} }));

const getMyProfile = jest.fn();
jest.mock('@/lib/apiClient', () => ({
  getMyProfile: (...args: unknown[]) => getMyProfile(...args),
}));

import { getViewerProfile, getViewerTimeZone } from '@/lib/session';

const SESSION = { user: { id: 'a3f1c9e2-0000-4444-8888-1b2c3d4e5f60', role: 'user' } };

describe('getViewerProfile / getViewerTimeZone (issue #784)', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('自ユーザーAPIから取得する(数値ユーザーIDを引数に取らない)', async () => {
    getServerSession.mockResolvedValue(SESSION);
    getMyProfile.mockResolvedValue({ id: 7, timezone: 'America/New_York' });

    const profile = await getViewerProfile();

    expect(profile).toEqual({ id: 7, timezone: 'America/New_York' });
    expect(getMyProfile).toHaveBeenCalledTimes(1);
    // Keycloakのsub(UUID)が引数として渡っていないこと。
    expect(getMyProfile).toHaveBeenCalledWith();
  });

  it('未ログインならAPIを呼ばずnullを返す', async () => {
    getServerSession.mockResolvedValue(null);

    expect(await getViewerProfile()).toBeNull();
    expect(getMyProfile).not.toHaveBeenCalled();
  });

  it('取得に失敗したらnullを返すが、握り潰さずログに残す', async () => {
    getServerSession.mockResolvedValue(SESSION);
    getMyProfile.mockRejectedValue(new Error('boom'));
    const spy = jest.spyOn(console, 'error').mockImplementation(() => {});

    expect(await getViewerProfile()).toBeNull();
    expect(spy).toHaveBeenCalled();

    spy.mockRestore();
  });

  it('タイムゾーンはプロフィールの値を返す', async () => {
    getServerSession.mockResolvedValue(SESSION);
    getMyProfile.mockResolvedValue({ id: 7, timezone: 'Europe/Berlin' });

    expect(await getViewerTimeZone()).toBe('Europe/Berlin');
  });

  it('タイムゾーン未設定ならnull(呼び出し側がブラウザのローカルTZへフォールバックする)', async () => {
    getServerSession.mockResolvedValue(SESSION);
    getMyProfile.mockResolvedValue({ id: 7, timezone: null });

    expect(await getViewerTimeZone()).toBeNull();
  });
});
