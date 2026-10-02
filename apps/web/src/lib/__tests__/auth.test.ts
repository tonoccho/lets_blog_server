/**
 * issue #1053: auth.ts の jwt/session コールバック、リフレッシュ実行、ロール判定、
 * サインアウト時のKeycloakセッション終了を検証する。
 *
 * `auth.ts` は `server-only` をimportしているため(issue #969)、Jestが実行できるよう
 * `server-only` だけをスタブ化する(`__tests__/session.test.ts` / `apiClient.test.ts` と
 * 同じ手法)。更新判定そのものの4ケースは `tokenRefreshPolicy.test.ts` が固定しているため、
 * ここでは auth.ts 自身の配線(resolveAccessTokenへの委譲・リフレッシュの実行・
 * ロール判定・サインアウト時のKeycloakセッション終了)を検証する。
 */
jest.mock('server-only', () => ({}))

// 'jose' はESM専用ビルドを配布しており、next/jestの既定transformIgnorePatternsでは
// node_modules内のESMをトランスパイルしないため、そのままではJestが構文エラーで落ちる
// (auth.tsが'jose'をimportしているため間接的に踏む。#969の壁がserver-onlyだけではない
// ことがこれで分かった)。deriveRole()は署名検証をしない表示用ロールフラグの読み取りに
// しか使わないため、テストでは同じ入出力のスタブへ差し替える。
jest.mock('jose', () => ({
  decodeJwt: (token: string) => {
    if (token === 'malformed-token') {
      throw new Error('invalid token')
    }
    return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf-8'))
  },
}))

import { encodeJwtForTest } from './testUtils/fakeJwt'
import { authOptions } from '@/lib/auth'
import type { Account } from 'next-auth'
import type { JWT } from 'next-auth/jwt'

const jwtCallback = authOptions.callbacks!.jwt as unknown as (args: { token: JWT; user?: unknown; account?: Account | null }) => Promise<JWT>
const sessionCallback = authOptions.callbacks!.session as unknown as (args: { session: { user?: { id?: string; role?: string }; error?: string }; token: JWT }) => Promise<{ user?: { id?: string; role?: string }; error?: string }>
const signOutEvent = authOptions.events!.signOut as unknown as (message: { token?: JWT }) => Promise<void>

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response
}

let fetchMock: jest.Mock
let consoleErrorSpy: jest.SpyInstance

beforeEach(() => {
  jest.clearAllMocks()
  fetchMock = jest.fn()
  global.fetch = fetchMock as unknown as typeof fetch
  // リフレッシュ失敗は必ずconsole.errorへ残す仕様(auth.ts参照)なので、失敗系のテストでは
  // 意図したログとして毎回出る。テスト出力を静かにしつつ、呼ばれたことの確認にも使う。
  consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  consoleErrorSpy.mockRestore()
})

describe('jwtコールバック(issue #1053)', () => {
  it('初回サインイン(account+user)はaccessTokenExpiresをexpires_inから算出する', async () => {
    const adminToken = encodeJwtForTest({ realm_access: { roles: ['admin'] } })
    const account: Partial<Account> & { expires_in: number } = {
      access_token: adminToken,
      refresh_token: 'r-1',
      id_token: 'id-1',
      expires_in: 300,
    }
    const before = Date.now()

    const result = await jwtCallback({
      token: {} as JWT,
      user: { id: 'user-1' },
      account: account as Account,
    })

    expect(result.id).toBe('user-1')
    expect(result.accessToken).toBe(adminToken)
    expect(result.role).toBe('admin')
    expect(result.error).toBeUndefined()
    expect(result.accessTokenExpires).toBeGreaterThanOrEqual(before + 300 * 1000)
  })

  it('account.access_tokenが無ければroleはuser', async () => {
    const account: Partial<Account> & { expires_in: number } = { expires_in: 300 }
    const result = await jwtCallback({ token: {} as JWT, user: { id: 'user-2' }, account: account as Account })
    expect(result.role).toBe('user')
  })

  it('アクセストークンのデコードに失敗してもroleはuserにフォールバックする', async () => {
    const account: Partial<Account> & { expires_in: number } = {
      access_token: 'malformed-token',
      expires_in: 300,
    }
    const result = await jwtCallback({ token: {} as JWT, user: { id: 'user-3' }, account: account as Account })
    expect(result.role).toBe('user')
  })

  it('2回目以降(account無し)は更新猶予込みの判定(tokenRefreshPolicy)に委譲する ― 失効まで十分あれば更新しない', async () => {
    const token: JWT = {
      accessToken: 'still-valid',
      refreshToken: 'r-1',
      accessTokenExpires: Date.now() + 10 * 60 * 1000,
    } as JWT

    const result = await jwtCallback({ token })

    expect(result).toBe(token)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('猶予内・失効済みのいずれでも更新する(実際にrefreshAccessToken経由でKeycloakを呼ぶ)', async () => {
    const userToken = encodeJwtForTest({ realm_access: { roles: [] } })
    fetchMock.mockResolvedValue(
      jsonResponse({ access_token: userToken, refresh_token: 'r-2', expires_in: 300 })
    )
    const token: JWT = {
      accessToken: 'stale',
      refreshToken: 'r-1',
      accessTokenExpires: Date.now() - 1000,
    } as JWT

    const result = await jwtCallback({ token })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(result.accessToken).toBe(userToken)
    expect(result.refreshToken).toBe('r-2')
    expect(result.role).toBe('user')
    expect(result.error).toBeUndefined()
  })
})

describe('refreshAccessToken(jwtコールバック経由、issue #1053)', () => {
  it('refreshTokenが無ければ更新失敗としてRefreshAccessTokenErrorを立てる', async () => {
    const token: JWT = { accessTokenExpires: Date.now() - 1000 } as JWT

    const result = await jwtCallback({ token })

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.error).toBe('RefreshAccessTokenError')
  })

  it('Keycloakが失敗応答を返したらRefreshAccessTokenErrorを立てる', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ error: 'invalid_grant' }, 400))
    const token: JWT = { refreshToken: 'r-1', accessTokenExpires: Date.now() - 1000 } as JWT

    const result = await jwtCallback({ token })

    expect(result.error).toBe('RefreshAccessTokenError')
    expect(result.accessToken).toBeUndefined()
  })

  it('レスポンスにrefresh_tokenが含まれなければ既存のrefreshTokenを維持する', async () => {
    const userToken = encodeJwtForTest({ realm_access: { roles: [] } })
    fetchMock.mockResolvedValue(jsonResponse({ access_token: userToken, expires_in: 300 }))
    const token: JWT = { refreshToken: 'r-original', accessTokenExpires: Date.now() - 1000 } as JWT

    const result = await jwtCallback({ token })

    expect(result.refreshToken).toBe('r-original')
  })

  it('fetch自体が例外を投げてもRefreshAccessTokenErrorを立てる(ネットワーク不達)', async () => {
    fetchMock.mockRejectedValue(new Error('ECONNREFUSED'))
    const token: JWT = { refreshToken: 'r-1', accessTokenExpires: Date.now() - 1000 } as JWT

    const result = await jwtCallback({ token })

    expect(result.error).toBe('RefreshAccessTokenError')
    expect(consoleErrorSpy).toHaveBeenCalled()
  })
})

describe('sessionコールバック(issue #1053)', () => {
  it('session.userへid/roleを積み、token.errorがあればsession.errorへ伝える', async () => {
    const session = await sessionCallback({
      session: { user: { id: '', role: '' } },
      token: { id: 'user-1', role: 'admin', error: 'RefreshAccessTokenError' } as JWT,
    })

    expect(session.user).toEqual({ id: 'user-1', role: 'admin' })
    expect(session.error).toBe('RefreshAccessTokenError')
  })

  it('token.errorが無ければsession.errorはセットされない', async () => {
    const session = await sessionCallback({
      session: { user: { id: '', role: '' } },
      token: { id: 'user-1', role: 'user' } as JWT,
    })

    expect(session.error).toBeUndefined()
  })

  it('session.userが無ければid/roleは積まない', async () => {
    const session = await sessionCallback({ session: {}, token: { id: 'user-1', role: 'admin' } as JWT })
    expect(session.user).toBeUndefined()
  })
})

describe('events.signOut(issue #1053のスコープ外だが変更ファイルのため既存ロジックを固定)', () => {
  it('refreshTokenがあればKeycloakのログアウトエンドポイントを呼ぶ', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}))
    await signOutEvent({ token: { refreshToken: 'r-1' } as JWT })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('refreshTokenが無ければ何もしない', async () => {
    await signOutEvent({ token: {} as JWT })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('tokenが無ければ何もしない', async () => {
    await signOutEvent({})
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('Keycloakへの通信が失敗してもログアウト操作自体は例外を投げない', async () => {
    fetchMock.mockRejectedValue(new Error('ECONNREFUSED'))
    await expect(signOutEvent({ token: { refreshToken: 'r-1' } as JWT })).resolves.toBeUndefined()
  })
})

describe('Keycloakプロバイダのprofile()マッピング(issue #1053のスコープ外だが変更ファイルのため既存ロジックを固定)', () => {
  const profile = (
    authOptions.providers[0] as unknown as {
      profile: (p: { sub: string; email?: string; name?: string; preferred_username?: string; picture?: string }) => unknown
    }
  ).profile

  it('nameがあればそれを使う', () => {
    expect(
      profile({ sub: 'u1', name: '表示名', preferred_username: 'preferred', email: 'a@example.com', picture: 'p.png' })
    ).toEqual({ id: 'u1', name: '表示名', email: 'a@example.com', image: 'p.png' })
  })

  it('nameが無ければpreferred_usernameへフォールバックする', () => {
    expect(profile({ sub: 'u2', preferred_username: 'preferred' })).toEqual({
      id: 'u2',
      name: 'preferred',
      email: undefined,
      image: undefined,
    })
  })
})

describe('pages(issue #1392)', () => {
  it('signInは/login、errorは公開パス配下の/login/errorを指す(NextAuth既定のエラーページで行き止まりにしない)', () => {
    expect(authOptions.pages).toEqual({ signIn: '/login', error: '/login/error' })
  })
})
