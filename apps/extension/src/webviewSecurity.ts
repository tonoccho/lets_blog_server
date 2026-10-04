import * as crypto from 'crypto';

/**
 * WebviewのContent-Security-Policyで使う一度限りのnonceを生成する。
 * パネルを開くたびに新しい値を発行し、これを持つ<script>だけが実行される。
 */
export function createNonce(): string {
  return crypto.randomBytes(16).toString('base64');
}

/**
 * スクリプトを実行するWebview向けのCSP。
 *
 * default-src 'none' で既定をすべて禁止し、必要なものだけを許可する。
 * スクリプトはnonce付きのものに限定するため、サーバー応答やAI生成結果が
 * 万一HTMLとして混入しても、その中の<script>は実行されない。
 *
 * スタイルに'unsafe-inline'を許しているのは、各パネルがHTML中のstyle属性を
 * 使っており、nonceではstyle属性を許可できないため(スタイル注入の影響は
 * スクリプト実行に比べ限定的なため、この範囲で許容する)。
 */
export function buildScriptedCsp(nonce: string): string {
  return [
    "default-src 'none'",
    "img-src data: https:",
    "style-src 'unsafe-inline'",
    `script-src 'nonce-${nonce}'`,
  ].join('; ');
}

/**
 * 実サイトのプレビュー(署名付きURLをiframeで表示する、issue #1562)向けのCSP。
 *
 * `frame-src`にはプレビューURLの**オリジンだけ**を許可する(パス・クエリのトークンは含めない)。
 * 他のオリジンやワイルドカードは許可せず、リモートのスクリプト・スタイルも読み込ませない。
 * iframe内のページはそのサイト自身のCSPで動くため、このWebview側は枠を出すだけでよい。
 * http/https以外や解釈できないURLは、何も許可せず例外にする。
 */
export function buildRealSitePreviewCsp(nonce: string, previewUrl: string): string {
  const origin = httpOriginOf(previewUrl);
  return [
    "default-src 'none'",
    `frame-src ${origin}`,
    "style-src 'unsafe-inline'",
    `script-src 'nonce-${nonce}'`,
  ].join('; ');
}

/** http/httpsのURLのオリジン(scheme://host[:port])を返す。それ以外は例外。 */
function httpOriginOf(url: string): string {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new Error('プレビューURLを解釈できません');
  }
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Error('プレビューURLはhttp/httpsのみ表示できます');
  }
  return parsed.origin;
}
