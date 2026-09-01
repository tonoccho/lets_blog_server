/**
 * デバイス認可(RFC 8628)の「利用者がブラウザで承認する」部分だけを HTTP で再現する
 * テスト用ヘルパー(issue #942 / AT-16)。
 *
 * 拡張自身はこの経路を持たない——拡張は user_code を表示してブラウザを開くだけで、
 * 承認するのは利用者である。したがってここだけは拡張のコードを通らない。逆に言えば、
 * 拡張が呼ぶ側(deviceAuth.ts の requestDeviceAuthorization / pollForToken)は
 * すべて拡張自身の実装を使う。
 *
 * 自己署名証明書のローカルスタックへ接続するため rejectUnauthorized=false を使う。
 * これは「利用者のブラウザが証明書の警告を許可した」状態に相当する。拡張側の TLS 検証は
 * letsBlog.allowInsecureTls の検証シナリオ(features/auth/tls.feature)で別途確認する。
 */

import * as https from 'https';
import { URL } from 'url';

interface PageResponse {
  status: number;
  body: string;
  location?: string;
}

/** Cookie を保持したまま1リクエストだけ送る。 */
function request(
  url: string,
  cookies: Map<string, string>,
  body?: string
): Promise<PageResponse> {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const headers: Record<string, string> = {};
    if (cookies.size > 0) {
      headers.Cookie = [...cookies.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
    }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
      headers['Content-Length'] = String(Buffer.byteLength(body));
    }
    const req = https.request(
      {
        protocol: parsed.protocol,
        hostname: parsed.hostname,
        port: parsed.port || undefined,
        path: `${parsed.pathname}${parsed.search}`,
        method: body === undefined ? 'GET' : 'POST',
        headers,
        rejectUnauthorized: false,
      },
      (res) => {
        for (const raw of res.headers['set-cookie'] ?? []) {
          const [pair] = raw.split(';');
          const index = pair.indexOf('=');
          if (index > 0) cookies.set(pair.slice(0, index).trim(), pair.slice(index + 1).trim());
        }
        const chunks: Buffer[] = [];
        res.on('data', (chunk: Buffer) => chunks.push(chunk));
        res.on('end', () =>
          resolve({
            status: res.statusCode ?? 0,
            body: Buffer.concat(chunks).toString('utf-8'),
            location: res.headers.location,
          })
        );
        res.on('error', reject);
      }
    );
    req.on('error', reject);
    if (body !== undefined) req.write(body);
    req.end();
  });
}

/** リダイレクトを追いながら取得する。 */
async function follow(url: string, cookies: Map<string, string>, body?: string): Promise<PageResponse> {
  let current = await request(url, cookies, body);
  let target = url;
  for (let hop = 0; hop < 10 && current.status >= 300 && current.status < 400 && current.location; hop += 1) {
    target = new URL(current.location, target).toString();
    current = await request(target, cookies);
  }
  return current;
}

function decodeEntities(value: string): string {
  return value.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
}

function formAction(html: string, pageUrl: string): string {
  const match = /action="([^"]+)"/.exec(html);
  if (!match) throw new Error('承認画面にフォームが見つかりません');
  return new URL(decodeEntities(match[1]), pageUrl).toString();
}

function hiddenValue(html: string, name: string): string | undefined {
  const pattern = new RegExp(`<input[^>]*name="${name}"[^>]*>`, 'i');
  const input = pattern.exec(html);
  if (!input) return undefined;
  const value = /value="([^"]*)"/.exec(input[0]);
  return value ? decodeEntities(value[1]) : undefined;
}

/**
 * verification_uri_complete を開き、ログインと承認を済ませる。
 * 完了後、拡張側の pollForToken がトークンを取得できる状態になる。
 */
export async function approveDeviceAuthorization(
  verificationUriComplete: string,
  email: string,
  password: string
): Promise<void> {
  const cookies = new Map<string, string>();
  const loginPage = await follow(verificationUriComplete, cookies);
  if (loginPage.status !== 200) {
    throw new Error(`デバイス承認画面を開けませんでした (HTTP ${loginPage.status})`);
  }

  const loginAction = formAction(loginPage.body, verificationUriComplete);
  const credentials = new URLSearchParams({ username: email, password, credentialId: '' }).toString();
  const consentPage = await follow(loginAction, cookies, credentials);
  if (consentPage.body.includes('name="password"')) {
    throw new Error('デバイス承認のログインに失敗しました(資格情報を確認してください)');
  }

  // Keycloak のデバイス認可は、ログイン後に必ず許可/拒否の確認画面を挟む。
  const consentAction = formAction(consentPage.body, loginAction);
  const code = hiddenValue(consentPage.body, 'code');
  if (!code) throw new Error('承認画面に code が含まれていません');
  const approved = await follow(
    consentAction,
    cookies,
    new URLSearchParams({ code, accept: 'Yes' }).toString()
  );
  if (approved.status !== 200) {
    throw new Error(`デバイス承認に失敗しました (HTTP ${approved.status})`);
  }
}
