/**
 * extensions のツールへ送るトークン(issue #1804)の検証。スタブは受けた Authorization を記録するだけで検証しないので、
 * 受け入れテストのステップがここで JWT の署名(Keycloak の JWKS)と `iss` / `azp` / `aud` を確かめる。
 *
 * トークンの値はログにもエラーメッセージにも出さない(署名の検証に失敗したときも、値は載せない)。
 */
import { createPublicKey, createVerify } from 'node:crypto';

export interface Jwk {
  kty: string;
  kid?: string;
  alg?: string;
  use?: string;
  n?: string;
  e?: string;
}

export interface Jwks {
  keys: Jwk[];
}

export interface ExpectedClaims {
  issuer: string;
  azp: string;
  audience: string;
}

export interface VerifiedToken {
  header: { alg?: string; kid?: string };
  payload: Record<string, unknown>;
}

const BEARER = /^Bearer ([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)$/;

function decodeJson(part: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(part, 'base64url').toString('utf8')) as Record<string, unknown>;
}

/** `Authorization` の値(`Bearer <JWT>`)を検証し、中身を返す。失敗したら理由だけを載せた Error を投げる。 */
export function verifyBearer(authorization: string, jwks: Jwks, expected: ExpectedClaims): VerifiedToken {
  const match = BEARER.exec(authorization);
  if (!match) {
    throw new Error('Authorization が「Bearer <JWT>」の形ではない');
  }
  const [, headerPart, payloadPart, signaturePart] = match;
  const header = decodeJson(headerPart) as { alg?: string; kid?: string };
  if (header.alg !== 'RS256') {
    throw new Error(`署名のアルゴリズムが RS256 ではない(alg=${String(header.alg)})`);
  }
  const jwk = jwks.keys.find((key) => key.kid === header.kid && key.kty === 'RSA');
  if (!jwk) {
    throw new Error(`JWKS に kid=${String(header.kid)} の RSA 鍵が無い`);
  }
  const verifier = createVerify('RSA-SHA256');
  verifier.update(`${headerPart}.${payloadPart}`);
  const publicKey = createPublicKey({ key: jwk as never, format: 'jwk' });
  if (!verifier.verify(publicKey, Buffer.from(signaturePart, 'base64url'))) {
    throw new Error('署名が JWKS の鍵で検証できない');
  }
  const payload = decodeJson(payloadPart);
  if (payload.iss !== expected.issuer) {
    throw new Error(`iss が期待と違う(期待 ${expected.issuer}、実際 ${String(payload.iss)})`);
  }
  if (payload.azp !== expected.azp) {
    throw new Error(`azp が期待と違う(期待 ${expected.azp}、実際 ${String(payload.azp)})`);
  }
  const audience = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!audience.includes(expected.audience)) {
    throw new Error(`aud に ${expected.audience} が含まれない(実際 ${JSON.stringify(payload.aud)})`);
  }
  return { header, payload };
}
