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
 * スクリプトを実行しないWebview(記事プレビュー)向けのCSP。
 * 記事プレビューは投稿先サイトのCSSと画像を読み込むため、それらのみ許可する。
 */
export function buildStaticCsp(): string {
  return ["default-src 'none'", 'img-src data: https: http:', "style-src 'unsafe-inline'"].join('; ');
}
