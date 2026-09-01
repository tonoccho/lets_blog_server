import { decodeJwtPayload, extractEmail, extractPrimaryRoleName } from '../jwtClaims';

/** テスト用にJWTを組み立てる(署名部分はデコード対象外のため任意の文字列でよい)。 */
function makeJwt(payload: unknown): string {
  const base64url = (value: string): string =>
    Buffer.from(value, 'utf-8').toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const body = base64url(JSON.stringify(payload));
  return `${header}.${body}.signature`;
}

describe('decodeJwtPayload', () => {
  it('ペイロードをデコードできる', () => {
    const token = makeJwt({ email: 'writer@example.com' });
    expect(decodeJwtPayload(token)).toEqual({ email: 'writer@example.com' });
  });

  it('base64urlのパディングが無い場合もデコードできる', () => {
    // '=' パディングが付かない長さのペイロードでも壊れないことを確認する。
    const token = makeJwt({ a: 1 });
    expect(decodeJwtPayload(token)).toEqual({ a: 1 });
  });

  it('セグメントが2つ未満の場合はundefined', () => {
    expect(decodeJwtPayload('not-a-jwt')).toBeUndefined();
  });

  it('base64として不正な場合はundefined', () => {
    expect(decodeJwtPayload('header.%%%invalid%%%.sig')).toBeUndefined();
  });

  it('JSONとして配列など非オブジェクトの場合はundefined', () => {
    const base64url = (value: string): string =>
      Buffer.from(value, 'utf-8').toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    const token = `header.${base64url(JSON.stringify([1, 2, 3]))}.sig`;
    expect(decodeJwtPayload(token)).toBeUndefined();
  });
});

describe('extractEmail', () => {
  it('emailクレームを優先する', () => {
    expect(extractEmail({ email: 'a@example.com', preferred_username: 'other' })).toBe('a@example.com');
  });

  it('emailが無ければpreferred_usernameを使う', () => {
    expect(extractEmail({ preferred_username: 'writer' })).toBe('writer');
  });

  it('どちらも無ければ空文字', () => {
    expect(extractEmail({})).toBe('');
    expect(extractEmail(undefined)).toBe('');
  });
});

describe('extractPrimaryRoleName', () => {
  it('realm_access.rolesから既定ロールを除いた最初のロールをROLE_接頭辞+大文字で返す', () => {
    const claims = { realm_access: { roles: ['offline_access', 'default-roles-letsblog', 'editor'] } };
    expect(extractPrimaryRoleName(claims)).toBe('ROLE_EDITOR');
  });

  it('realm_accessが無ければundefined', () => {
    expect(extractPrimaryRoleName({})).toBeUndefined();
    expect(extractPrimaryRoleName(undefined)).toBeUndefined();
  });

  it('rolesが配列でなければundefined', () => {
    expect(extractPrimaryRoleName({ realm_access: {} })).toBeUndefined();
  });

  it('既定ロールしか無ければundefined', () => {
    const claims = { realm_access: { roles: ['offline_access', 'uma_authorization', 'default-roles-letsblog'] } };
    expect(extractPrimaryRoleName(claims)).toBeUndefined();
  });
});
