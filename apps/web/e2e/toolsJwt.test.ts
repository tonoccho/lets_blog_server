/**
 * @jest-environment node
 *
 * `support/toolsJwt.ts`(issue #1804)の単体テスト。`apps/web/e2e/**` は通常 jest の対象外(#994)なので、
 * `helpers.test.ts` と同じく、対象を明示した jest 呼び出しで走らせる(`--testMatch` で本ファイルを指定し、
 * 対象除外のオプションは `/node_modules/` だけに上書きして、`--runTestsByPath e2e/toolsJwt.test.ts` を付ける)。
 */
import { generateKeyPairSync, createSign } from 'node:crypto';
import { verifyBearer, type Jwks } from './support/toolsJwt';

const ISSUER = 'https://localhost/auth/realms/letsblog';
const EXPECTED = { issuer: ISSUER, azp: 'letsblog-tools-client', audience: 'lbx-tools' };

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const other = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwks = (key = publicKey, kid = 'k1'): Jwks => ({
  keys: [{ ...(key.export({ format: 'jwk' }) as object), kid, alg: 'RS256', use: 'sig' } as never],
});

function sign(
  payload: Record<string, unknown>,
  { key = privateKey, alg = 'RS256', kid = 'k1' }: { key?: typeof privateKey; alg?: string; kid?: string } = {}
): string {
  const header = Buffer.from(JSON.stringify({ alg, kid, typ: 'JWT' })).toString('base64url');
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const signature = createSign('RSA-SHA256').update(`${header}.${body}`).sign(key).toString('base64url');
  return `Bearer ${header}.${body}.${signature}`;
}

const good = { iss: ISSUER, azp: 'letsblog-tools-client', aud: ['lbx-tools'] };

describe('verifyBearer', () => {
  it('署名・iss・azp・aud がそろっていれば中身を返す', () => {
    const verified = verifyBearer(sign(good), jwks(), EXPECTED);
    expect(verified.payload.azp).toBe('letsblog-tools-client');
    expect(verified.header.kid).toBe('k1');
  });

  it('aud が文字列でも受け入れる', () => {
    expect(() => verifyBearer(sign({ ...good, aud: 'lbx-tools' }), jwks(), EXPECTED)).not.toThrow();
  });

  it('Bearer の形でなければ拒否する', () => {
    expect(() => verifyBearer('Basic abc', jwks(), EXPECTED)).toThrow('Bearer <JWT>');
    expect(() => verifyBearer('Bearer a.b', jwks(), EXPECTED)).toThrow('Bearer <JWT>');
  });

  it('RS256 以外は拒否する(alg=none の差し替え対策)', () => {
    expect(() => verifyBearer(sign(good, { alg: 'none' }), jwks(), EXPECTED)).toThrow('RS256');
  });

  it('JWKS に無い kid は拒否する', () => {
    expect(() => verifyBearer(sign(good, { kid: 'other' }), jwks(), EXPECTED)).toThrow('kid=other');
  });

  it('別の鍵で署名されたものは拒否する', () => {
    expect(() => verifyBearer(sign(good, { key: other.privateKey }), jwks(), EXPECTED)).toThrow('署名');
  });

  it('iss・azp・aud が違えば、それぞれ理由つきで拒否する', () => {
    expect(() => verifyBearer(sign({ ...good, iss: 'https://evil' }), jwks(), EXPECTED)).toThrow('iss');
    expect(() => verifyBearer(sign({ ...good, azp: 'letsblog-services' }), jwks(), EXPECTED)).toThrow('azp');
    expect(() => verifyBearer(sign({ ...good, aud: ['other'] }), jwks(), EXPECTED)).toThrow('aud');
  });

  it('エラーメッセージにトークンの値を載せない', () => {
    const token = sign({ ...good, azp: 'letsblog-services' });
    try {
      verifyBearer(token, jwks(), EXPECTED);
      throw new Error('投げられなかった');
    } catch (e) {
      expect((e as Error).message).not.toContain(token.slice('Bearer '.length));
    }
  });
});
