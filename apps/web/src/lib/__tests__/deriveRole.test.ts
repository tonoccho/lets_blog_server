/**
 * issue #969: deriveRole()(Keycloakのrealmロールをadmin/userの2値ロールへ変換するロジック)
 * を直接検証する。
 *
 * 元々は `auth.ts` 内に定義されていたが、`auth.ts` は `server-only` をimportしているため
 * Jestから直接importできず(`__tests__/auth.test.ts` のように `server-only` をスタブ化すれば
 * 間接的には検証できるものの、`realm_access` が丸ごと欠落しているケースは #1053 時点では
 * 未検証だった)、deriveRole()単体を`src/lib/deriveRole.ts`へ切り出し、このテストから直接
 * importして検証する。
 *
 * 'jose' はESM専用ビルドを配布しており、next/jestの既定transformIgnorePatternsでは
 * node_modules内のESMをトランスパイルしないため、そのままではJestが構文エラーで落ちる
 * (`__tests__/auth.test.ts` と同じ理由・同じ回避策)。deriveRole()は署名検証をしない
 * 表示用ロールフラグの読み取りにしか使わないため、テストでは同じ入出力のスタブへ差し替える。
 */
jest.mock('jose', () => ({
  decodeJwt: (token: string) => {
    if (token === 'malformed-token') {
      throw new Error('invalid token')
    }
    return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf-8'))
  },
}))

import { encodeJwtForTest } from './testUtils/fakeJwt'
import { deriveRole } from '@/lib/deriveRole'

describe('deriveRole(issue #969)', () => {
  it('realm_access.rolesにadminが含まれていればadmin', () => {
    const token = encodeJwtForTest({ realm_access: { roles: ['editor', 'admin'] } })
    expect(deriveRole(token)).toBe('admin')
  })

  it('realm_access.rolesにadminが含まれていなければuser', () => {
    const token = encodeJwtForTest({ realm_access: { roles: ['editor', 'viewer'] } })
    expect(deriveRole(token)).toBe('user')
  })

  it('realm_accountが丸ごと欠落していればuser', () => {
    const token = encodeJwtForTest({ sub: 'user-1' })
    expect(deriveRole(token)).toBe('user')
  })

  it('realm_access.rolesが欠落していればuser', () => {
    const token = encodeJwtForTest({ realm_access: {} })
    expect(deriveRole(token)).toBe('user')
  })

  it('デコードできない(壊れた)アクセストークンはuserにフォールバックする', () => {
    expect(deriveRole('malformed-token')).toBe('user')
  })
})
