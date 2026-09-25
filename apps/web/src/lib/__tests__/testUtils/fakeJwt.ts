/**
 * 署名検証を伴わない、テスト専用の見た目だけのJWTを組み立てる(issue #1053)。
 *
 * `auth.ts` の `deriveRole()` は表示用ロールフラグとしてのみ `jose.decodeJwt()`(署名検証なし)
 * でペイロードを読むため、テストでは実際の署名は不要。ヘッダ・署名は固定のダミー値でよい。
 */
export function encodeJwtForTest(payload: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url')
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url')
  return `${header}.${body}.test-signature`
}
