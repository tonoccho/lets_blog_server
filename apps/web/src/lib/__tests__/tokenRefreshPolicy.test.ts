/**
 * issue #1053: アクセストークンの更新猶予(スキュー)の不変条件と、jwtコールバック相当の
 * 更新判定ロジックの検証。
 *
 * 元の不具合: jwtコールバックが「既に失効していなければ更新しない」(猶予ゼロ)という条件を
 * 持っており、`SessionProvider.tsx` の再取得間隔(240秒)とKeycloakのトークン寿命(300秒)の
 * 関係と噛み合わず、失効後〜次の再取得までの約180秒間、失効済みトークンがCookieに残り続けて
 * いた。3つの値の整合はコメントだけが主張しており、それがずれても誰も気付けなかった
 * (まさに本不具合の原因)。ここではその関係を不変条件としてテストで固定する。
 */
import fs from 'node:fs'
import path from 'node:path'
import type { JWT } from 'next-auth/jwt'
import {
  ACCESS_TOKEN_LIFESPAN_SECONDS,
  ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS,
  ACCESS_TOKEN_SKEW_SECONDS,
  resolveAccessToken,
  shouldRefreshAccessToken,
} from '@/lib/tokenRefreshPolicy'

describe('アクセストークンの更新猶予の不変条件(issue #1053)', () => {
  it('再取得間隔 + 更新猶予 >= トークン寿命 が成立する(破れると失効済みトークンが使われる窓が生まれる)', () => {
    expect(ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS + ACCESS_TOKEN_SKEW_SECONDS).toBeGreaterThanOrEqual(
      ACCESS_TOKEN_LIFESPAN_SECONDS
    )
  })

  it('ACCESS_TOKEN_LIFESPAN_SECONDS はKeycloakのrealm設定(accessTokenLifespan)と一致する', () => {
    const realmExportPath = path.resolve(__dirname, '../../../../../infra/keycloak/realm-export.json')
    const realm = JSON.parse(fs.readFileSync(realmExportPath, 'utf-8')) as { accessTokenLifespan: number }
    expect(ACCESS_TOKEN_LIFESPAN_SECONDS).toBe(realm.accessTokenLifespan)
  })
})

describe('shouldRefreshAccessToken / resolveAccessToken', () => {
  const NOW = 1_800_000_000_000

  it('(a) 失効まで十分ある場合は更新しない', async () => {
    const accessTokenExpires = NOW + (ACCESS_TOKEN_SKEW_SECONDS + 60) * 1000
    expect(shouldRefreshAccessToken(accessTokenExpires, NOW)).toBe(false)

    const token = { accessTokenExpires } as JWT
    const refresh = jest.fn()
    const result = await resolveAccessToken(token, NOW, refresh)

    expect(refresh).not.toHaveBeenCalled()
    expect(result).toBe(token)
  })

  it('(b) 失効が近い(猶予内)場合は更新する ― これが存在しないことが本不具合だった', async () => {
    const accessTokenExpires = NOW + (ACCESS_TOKEN_SKEW_SECONDS - 10) * 1000
    expect(shouldRefreshAccessToken(accessTokenExpires, NOW)).toBe(true)

    const token = { accessTokenExpires } as JWT
    const refreshed = { accessToken: 'new-access-token' } as JWT
    const refresh = jest.fn().mockResolvedValue(refreshed)
    const result = await resolveAccessToken(token, NOW, refresh)

    expect(refresh).toHaveBeenCalledWith(token)
    expect(result).toBe(refreshed)
  })

  it('(c) 既に失効している場合は更新する', async () => {
    const accessTokenExpires = NOW - 1000
    expect(shouldRefreshAccessToken(accessTokenExpires, NOW)).toBe(true)

    const token = { accessTokenExpires } as JWT
    const refreshed = { accessToken: 'new-access-token' } as JWT
    const refresh = jest.fn().mockResolvedValue(refreshed)
    const result = await resolveAccessToken(token, NOW, refresh)

    expect(refresh).toHaveBeenCalledWith(token)
    expect(result).toBe(refreshed)
  })

  it('(d) リフレッシュに失敗した場合はRefreshAccessTokenErrorが立つ', async () => {
    const accessTokenExpires = NOW - 1000
    const token = { accessTokenExpires } as JWT
    const refresh = jest.fn().mockResolvedValue({ ...token, error: 'RefreshAccessTokenError' as const })

    const result = await resolveAccessToken(token, NOW, refresh)

    expect(result.error).toBe('RefreshAccessTokenError')
  })

  it('accessTokenExpiresが無ければ無条件に更新扱いとする', () => {
    expect(shouldRefreshAccessToken(undefined, NOW)).toBe(true)
  })
})
