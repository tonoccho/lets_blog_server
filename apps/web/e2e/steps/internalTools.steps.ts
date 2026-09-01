import { execFileSync } from 'node:child_process';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * 同梱ツール(ComfyUI / PlantUML / draw.io)の公開面のステップ定義(issue #979)。
 *
 * reverse-proxy が中継する経路のうち、これらはアプリの認証ゲート(ADR-0008)を通らない。
 * ComfyUI は任意のワークフローを無認証で実行できる完全なUI、PlantUML は任意のソースを
 * サーバー側で描画するため、外部へ出す理由が無い限り中継しない。draw.io だけは
 * VSCode拡張(apps/extension/src/diagramEditorPanel.ts)が webview の iframe から
 * 直接読むため、lbs-net の外から到達できる必要がある。
 */

/** ComfyUI の SPA を開いていた経路。 */
const COMFYUI_PATH = '/comfyui/';

/** PlantUML サーバーのトップ。 */
const PLANTUML_TOP = '/plantuml/';

/**
 * PlantUML の描画エンドポイント。パス中の文字列は PlantUML 独自方式で符号化した
 * `@startuml Alice -> Bob @enduml` 相当。「無認証で任意のソースを描画させられる」という
 * 露出そのものを、実際に描画を要求して確かめる。
 */
const PLANTUML_RENDER_PATH = '/plantuml/png/SyfFKj2rKt3CoKnELR1Io4ZDoSa70000';

/** VSCode拡張が webview の iframe に読み込む URL(diagramEditorPanel.ts と同じクエリ)。 */
const DRAWIO_EMBED_PATH = '/drawio/?embed=1&ui=min&spin=1&proto=json&configure=1&noSaveBtn=1&noExitBtn=1';

/** draw.io のエディタ本体を起動する静的アセット。HTML から相対パスで読まれる。 */
const DRAWIO_ASSET_PATH = '/drawio/js/main.js';

/**
 * ComfyUI が応答したときにだけ現れる印。ComfyUI の index.html は自身のタイトルと
 * フロントエンドのエントリスクリプトを必ず含む。
 */
const COMFYUI_MARKERS = ['<title>ComfyUI', 'scripts/app.js'];

/**
 * Next.js(web)が応答したことの印。`/_next/static/` はビルド生成物の配信パスで、
 * Next.js 以外はこの形のURLを出さない。
 *
 * この印が要る理由: default.conf の末尾には `location /` があるため、
 * `location /comfyui/` を消しても経路が「拒否」されるのではなく web へ落ちる。
 * つまり「ComfyUI の印が無い」だけでは不十分で(コンテナ停止中の 502 でも印は出ない)、
 * 「専用の中継先が無くなり web が応答している」ことまで確かめる必要がある。
 */
const WEB_APP_MARKER = '/_next/static/';

/** nginx が上流へ中継しようとして失敗したときの印。中継先がまだ存在することを意味する。 */
const PROXY_FAILURE_MARKERS = ['502 Bad Gateway', '504 Gateway Time-out'];

/**
 * draw.io のエディタが返ったことの印。`geEditor` はエディタ枠の class、
 * `js/main.js` はエディタを起動するスクリプトで、どちらも編集UIを配信したときのみ出る。
 */
const DRAWIO_MARKERS = ['geEditor', 'js/main.js'];

/** PNG のファイル署名。バイト列の先頭に必ず現れる。 */
const PNG_SIGNATURE = '\x89PNG';

/** 内部ネットワーク上のホスト名(docker-compose のサービス名)。 */
const INTERNAL_PLANTUML_ORIGIN = 'http://plantuml:8080';
const INTERNAL_COMFYUI_ORIGIN = 'http://comfyui:8188';

interface ProbedResponse {
  status: number;
  body: string;
  contentType: string;
  location: string;
  url: string;
}

function seen(ctx: Record<string, unknown>): ProbedResponse {
  const response = ctx.toolResponse as ProbedResponse | undefined;
  if (!response) {
    throw new Error('先にツールの経路へアクセスするステップを実行すること');
  }
  return response;
}

/** lbs-net の中から curl を実行する。ホストからは各サービスへ到達できないため。 */
function execInContainer(container: string, args: string[]): string {
  return execFileSync('docker', ['exec', container, ...args], {
    encoding: 'utf8',
    timeout: 60_000,
  });
}

When('未認証で ComfyUI のパスを開く', async ({ ctx, request }) => {
  const response = await request.get(COMFYUI_PATH);
  ctx.toolResponse = {
    status: response.status(),
    body: await response.text(),
    contentType: response.headers()['content-type'] ?? '',
    location: response.headers()['location'] ?? '',
    url: COMFYUI_PATH,
  } satisfies ProbedResponse;
});

When('未認証で PlantUML の図描画パスを開く', async ({ ctx, request }) => {
  const response = await request.get(PLANTUML_RENDER_PATH);
  ctx.toolResponse = {
    status: response.status(),
    // 画像が返る場合に備えて latin1 で読む(PNG 署名をバイト単位で判定するため)。
    body: (await response.body()).toString('latin1'),
    contentType: response.headers()['content-type'] ?? '',
    location: response.headers()['location'] ?? '',
    url: PLANTUML_RENDER_PATH,
  } satisfies ProbedResponse;
});

When('未認証で PlantUML のトップをリダイレクトを追わずに開く', async ({ ctx, request }) => {
  // PlantUML サーバーはトップを開くと自身のサンプル図(/uml/<符号化済みソース>)へ
  // リダイレクトする。追ってしまうとこの指紋が消えるため、追わずに見る。
  const response = await request.get(PLANTUML_TOP, { maxRedirects: 0 });
  ctx.toolResponse = {
    status: response.status(),
    body: await response.text(),
    contentType: response.headers()['content-type'] ?? '',
    location: response.headers()['location'] ?? '',
    url: PLANTUML_TOP,
  } satisfies ProbedResponse;
});

Then('その応答は ComfyUI のものではない', async ({ ctx }) => {
  const { body, url } = seen(ctx);
  for (const marker of COMFYUI_MARKERS) {
    expect(body, `${url} が無認証で ComfyUI のUIを返している(${marker})`).not.toContain(marker);
  }
});

Then('その応答は PlantUML が描画した画像ではない', async ({ ctx }) => {
  const { body, contentType, url } = seen(ctx);
  expect(contentType, `${url} が無認証で画像を描画している`).not.toContain('image/');
  expect(body.startsWith(PNG_SIGNATURE), `${url} が無認証でPNGを返している`).toBe(false);
});

Then('その応答は Web アプリが返している', async ({ ctx }) => {
  const { status, body, url } = seen(ctx);
  for (const marker of PROXY_FAILURE_MARKERS) {
    expect(body, `${url} の中継先がまだ nginx に残っている(${marker})`).not.toContain(marker);
  }
  expect(body, `${url} が Web アプリ(${WEB_APP_MARKER})以外から返っている (status=${status})`)
    .toContain(WEB_APP_MARKER);
});

Then('その応答は PlantUML サーバー自身のリダイレクトではない', async ({ ctx }) => {
  const { status, location, url } = seen(ctx);
  // `/uml/...` は PlantUML サーバーのトップだけが出すリダイレクト先。
  expect(location.startsWith('/uml/'), `${url} が PlantUML サーバーへ届いている (status=${status}, location=${location})`)
    .toBe(false);
});

When('拡張が使う URL で draw.io のエディタを開く', async ({ ctx, request }) => {
  const response = await request.get(DRAWIO_EMBED_PATH);
  ctx.toolResponse = {
    status: response.status(),
    body: await response.text(),
    contentType: response.headers()['content-type'] ?? '',
    location: response.headers()['location'] ?? '',
    url: DRAWIO_EMBED_PATH,
  } satisfies ProbedResponse;
});

Then('draw.io のエディタが返る', async ({ ctx }) => {
  const { status, body, url } = seen(ctx);
  expect(status, `${url} が開けない`).toBe(200);
  for (const marker of DRAWIO_MARKERS) {
    expect(body, `${url} が draw.io のエディタを返していない(${marker})`).toContain(marker);
  }
});

Then('draw.io の静的アセットも同じ経路で取得できる', async ({ request }) => {
  // HTML は `js/main.js` を相対パスで読むため、/drawio/ 配下でも解決できないと
  // 拡張の webview でエディタが起動しない。
  const response = await request.get(DRAWIO_ASSET_PATH);
  expect(response.status(), `${DRAWIO_ASSET_PATH} が取得できない`).toBe(200);
  expect(response.headers()['content-type'] ?? '', 'JavaScript として配信されていない')
    .toContain('javascript');
});

When('media-service から PlantUML サーバーへ図を描画する', async ({ ctx }) => {
  // reverse-proxy の中継を消しても、サービス間(lbs-net)の呼び出しは影響を受けない。
  // media-service の PlantUmlClient が使うのと同じ内部URLで実際に描画させて確かめる。
  const output = execInContainer('lbs-media', [
    'curl', '--silent', '--output', '/dev/null', '--max-time', '30',
    '--write-out', '%{http_code}:%{content_type}',
    `${INTERNAL_PLANTUML_ORIGIN}/png/SyfFKj2rKt3CoKnELR1Io4ZDoSa70000`,
  ]);
  ctx.internalRender = output.trim();
});

Then('図の描画に成功する', async ({ ctx }) => {
  expect(ctx.internalRender as string, '内部ネットワーク経由のPlantUML描画が失敗している')
    .toBe('200:image/png');
});

Then('media-service の PlantUML 参照先は内部ホスト名である', async () => {
  const configured = execInContainer('lbs-media', ['printenv', 'PLANTUML_BASE_URL']).trim();
  expect(configured, 'media-service が reverse-proxy 経由で PlantUML を呼んでいる')
    .toBe(INTERNAL_PLANTUML_ORIGIN);
});

Then('media-service の ComfyUI 参照先は内部ホスト名である', async () => {
  const configured = execInContainer('lbs-media', ['printenv', 'COMFYUI_BASE_URL']).trim();
  expect(configured, 'media-service が reverse-proxy 経由で ComfyUI を呼んでいる')
    .toBe(INTERNAL_COMFYUI_ORIGIN);
});
