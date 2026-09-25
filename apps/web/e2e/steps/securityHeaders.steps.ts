import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * ブラウザ向け応答のセキュリティヘッダのステップ定義(issue #984)。
 *
 * ここが担保するのは2つある。
 *   1. 管理画面(Next.js)がブラウザへ返す応答に基本のセキュリティヘッダが付いていること。
 *   2. SECURITY.md の「Security Headers」節の記述が、その実装と食い違わないこと。
 *
 * 2 のために節の内容を**実行時に読み込む**。節にヘッダ名と値を書き写すだけでは、
 * 実装を消しても文書が残り続ける(#984 で実際にそうなっていた)。節が宣言した項目を
 * 実際の応答へ突き合わせることで、文書と実装のずれがテストの失敗として現れる。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const SECURITY_MD_PATH = 'SECURITY.md';
const SECURITY_HEADERS_HEADING = '## Security Headers';

/** 認証ゲートに当たる管理画面のトップ。未認証だと /login へ 307 で送られる。 */
const ADMIN_TOP_PATH = '/';

/** ログイン画面。未認証でも 200 が返る唯一の管理画面ページ。 */
const LOGIN_PATH = '/login';

/** VSCode拡張が webview の iframe に読み込む draw.io の URL(#979)。 */
const DRAWIO_EMBED_PATH = '/drawio/?embed=1&ui=min&spin=1&proto=json&configure=1&noSaveBtn=1&noExitBtn=1';

/**
 * 最低限そろえるヘッダと、その値(issue #984 の要件2)。
 *
 * HSTS の max-age が短いのは意図的である。この環境の証明書は自己署名
 * (scripts/generate-certs.sh)で、長い max-age を焼き付けると証明書を作り直した後に
 * ブラウザが警告を回避できなくなり、開発者が締め出される。
 */
const REQUIRED_HEADERS: Record<string, string> = {
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'SAMEORIGIN',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'strict-transport-security': 'max-age=300',
};

/**
 * 実装にCORSの明示設定があるかを調べるときに探す印。
 * Spring 側(サービス/ゲートウェイ)と、Next.js 側の手書きヘッダの双方を見る。
 */
const CORS_MARKERS = [
  'CorsConfiguration',
  'addCorsMappings',
  'CorsWebFilter',
  '@CrossOrigin',
  'Access-Control-Allow-Origin',
];

/** CORSの設定を探す対象。node_modules やビルド生成物は含めない。 */
const CORS_SEARCH_ROOTS = ['services', 'apps/web/src', 'packages', 'infra'];

interface SeenResponse {
  status: number;
  headers: Record<string, string>;
  location: string;
  url: string;
}

function seen(ctx: Record<string, unknown>): SeenResponse {
  const response = ctx.headerResponse as SeenResponse | undefined;
  if (!response) {
    throw new Error('先に応答を取得するステップを実行すること');
  }
  return response;
}

/** SECURITY.md の「Security Headers」節だけを取り出す(次の `## ` 見出しの手前まで)。 */
function readSecurityHeadersSection(): string {
  const markdown = fs.readFileSync(path.join(REPO_ROOT, SECURITY_MD_PATH), 'utf8');
  const start = markdown.indexOf(SECURITY_HEADERS_HEADING);
  expect(start, `${SECURITY_MD_PATH} に「${SECURITY_HEADERS_HEADING}」節が無い`).toBeGreaterThanOrEqual(0);
  const rest = markdown.slice(start + SECURITY_HEADERS_HEADING.length);
  const end = rest.indexOf('\n## ');
  return end >= 0 ? rest.slice(0, end) : rest;
}

/**
 * 節が「実際に付けているヘッダ」として宣言している項目を読み取る。
 *
 * 宣言の書式は「バッククォートで囲んだ `名前: 値`」に限る。値を伴わないヘッダ名
 * (例えば「なぜ Content-Security-Policy を入れないか」の説明中に出てくる名前)は
 * 宣言と見なさない。この区別があるため、採用しなかった対策も節の中で説明できる。
 */
function declaredHeaders(section: string): Record<string, string> {
  const declared: Record<string, string> = {};
  for (const match of section.matchAll(/`([A-Za-z][A-Za-z-]+):[ \t]*([^`]+)`/g)) {
    declared[match[1].toLowerCase()] = match[2].trim();
  }
  return declared;
}

/** 管理画面の応答ヘッダを取る。リダイレクトは追わない(ゲートの応答そのものを見るため)。 */
async function fetchHeaders(
  request: { get: (url: string, options?: { maxRedirects?: number }) => Promise<{ status: () => number; headers: () => Record<string, string> }> },
  url: string,
): Promise<SeenResponse> {
  const response = await request.get(url, { maxRedirects: 0 });
  const headers = response.headers();
  return {
    status: response.status(),
    headers,
    location: headers['location'] ?? '',
    url,
  };
}

/** リポジトリ内にCORSの明示設定があるか。git grep で実ファイルだけを対象にする。 */
function hasExplicitCorsConfiguration(): boolean {
  for (const marker of CORS_MARKERS) {
    try {
      const output = execFileSync(
        'git',
        ['grep', '-l', '-F', marker, '--', ...CORS_SEARCH_ROOTS],
        { cwd: REPO_ROOT, encoding: 'utf8' },
      );
      if (output.trim().length > 0) {
        return true;
      }
    } catch {
      // git grep は一致0件で終了コード1を返す。次の印へ進む。
    }
  }
  return false;
}

When('管理画面のログイン画面をリダイレクトを追わずに開く', async ({ ctx, request }) => {
  ctx.headerResponse = await fetchHeaders(request, LOGIN_PATH);
});

When('未認証で管理画面のトップをリダイレクトを追わずに開く', async ({ ctx, request }) => {
  ctx.headerResponse = await fetchHeaders(request, ADMIN_TOP_PATH);
});

Then('その応答は認証ゲートのリダイレクトである', async ({ ctx }) => {
  const { status, location, url } = seen(ctx);
  expect([301, 302, 307, 308], `${url} が認証ゲートのリダイレクトを返していない (status=${status})`)
    .toContain(status);
  expect(location, `${url} のリダイレクト先がログイン画面ではない`).toContain(LOGIN_PATH);
});

Then('その応答に基本のセキュリティヘッダが付いている', async ({ ctx }) => {
  const { headers, status, url } = seen(ctx);
  for (const [name, value] of Object.entries(REQUIRED_HEADERS)) {
    expect(headers[name], `${url} の応答に ${name} が無い (status=${status})`).toBeDefined();
    expect(headers[name], `${url} の ${name} の値が想定と異なる`).toBe(value);
  }
});

When('SECURITY.md の「Security Headers」節が宣言するヘッダを読み込む', async ({ ctx }) => {
  const section = readSecurityHeadersSection();
  ctx.securitySection = section;
  ctx.declaredHeaders = declaredHeaders(section);
});

Then('宣言されたヘッダは基本のセキュリティヘッダを網羅している', async ({ ctx }) => {
  const declared = ctx.declaredHeaders as Record<string, string>;
  for (const [name, value] of Object.entries(REQUIRED_HEADERS)) {
    expect(declared[name], `${SECURITY_MD_PATH} が ${name} を宣言していない`).toBeDefined();
    expect(declared[name], `${SECURITY_MD_PATH} の ${name} の値が実装の想定と異なる`).toBe(value);
  }
});

Then('宣言されたヘッダはすべて管理画面の応答に同じ値で付いている', async ({ ctx, request }) => {
  const declared = ctx.declaredHeaders as Record<string, string>;
  const response = await fetchHeaders(request, LOGIN_PATH);
  for (const [name, value] of Object.entries(declared)) {
    expect(response.headers[name], `${SECURITY_MD_PATH} が宣言する ${name} が応答に無い`).toBeDefined();
    expect(response.headers[name], `${SECURITY_MD_PATH} が宣言する ${name} の値と応答が異なる`).toBe(value);
  }
});

When('実装にCORS設定があるかを調べる', async ({ ctx }) => {
  ctx.hasCors = hasExplicitCorsConfiguration();
  ctx.securitySection = readSecurityHeadersSection();
});

Then('「Security Headers」節のCORSの記述はその結果と一致する', async ({ ctx }) => {
  const section = ctx.securitySection as string;
  const hasCors = ctx.hasCors as boolean;
  const corsLines = section.split('\n').filter((line) => line.includes('CORS'));
  expect(corsLines.length, `${SECURITY_MD_PATH} がCORSについて何も述べていない`).toBeGreaterThan(0);

  if (hasCors) {
    expect(section, `CORS設定が実在するのに ${SECURITY_MD_PATH} は無いと書いている`)
      .not.toMatch(/no explicit CORS configuration/i);
    return;
  }
  expect(section, `CORS設定が無いのに ${SECURITY_MD_PATH} はCORSでAPIを制限していると書いている`)
    .not.toMatch(/CORS polic(y|ies) to restrict/i);
  expect(section, `${SECURITY_MD_PATH} はCORS設定が無い事実(no explicit CORS configuration)を明記していない`)
    .toMatch(/no explicit CORS configuration/i);
  expect(section, `${SECURITY_MD_PATH} が同一オリジン前提であることを述べていない`)
    .toMatch(/same-origin/i);
});

When('拡張が iframe で読む draw.io の応答ヘッダを調べる', async ({ ctx, request }) => {
  ctx.headerResponse = await fetchHeaders(request, DRAWIO_EMBED_PATH);
});

Then('その応答に埋め込みを禁じるヘッダは付いていない', async ({ ctx }) => {
  const { headers, url } = seen(ctx);
  // draw.io は lbs-net の外(VSCodeのwebview)から iframe で読まれる。管理画面向けの
  // X-Frame-Options を reverse-proxy 側で全経路に付けると、この埋め込みが壊れる。
  expect(headers['x-frame-options'], `${url} に X-Frame-Options が付き、拡張の埋め込みが壊れる`)
    .toBeUndefined();
  expect(headers['content-security-policy'] ?? '', `${url} の CSP が frame-ancestors で埋め込みを塞いでいる`)
    .not.toContain('frame-ancestors');
});
