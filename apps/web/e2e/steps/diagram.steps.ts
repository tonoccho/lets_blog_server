import type { APIRequestContext } from '@playwright/test';
import { After, Given, Step, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';
import { postJsonToServiceDirectly } from '../support/services';
import { PLANTUML_STUB_URL, requirePlantUmlStub } from '../support/stubs';

/**
 * ダイアグラムとレンダリングの受け入れシナリオを支えるステップ定義
 * (issue #937 / AT-11)。
 *
 * ## 全て `@api` である理由
 *
 * Web のダイアグラムギャラリーは #662 で削除済みで、図のUIは VSCode 拡張だけにある。
 * つまり**この Issue の受け入れ基準に対応する画面がそもそも存在しない**ため、
 * ブラウザ経路では確かめようがない。各 `.feature` の冒頭にも同じことを書いてある。
 *
 * ## 2つの経路を使い分ける
 *
 * - `/api/diagrams/**` は gateway のルート表に載っているので、公開URL(`request`)で呼ぶ。
 * - `/api/render/**` は**載っていない**(issue #830)。lbs-net の中からしか到達できないので
 *   `support/services.ts` の {@link postJsonToServiceDirectly}(踏み台コンテナ経由)で呼ぶ。
 *   media-service は `/api/render/**` にも認証を要求する(SecurityConfig、issue #772)ので
 *   トークンは必要である。
 *
 * ## 後片付け
 *
 * 作ったダイアグラム・プロジェクト・プロジェクトメンバーは `After({ tags: '@diagram' })` が
 * 戻す。ダイアグラムは media-service の
 * `diagrams` にあり project-service の `projects` への外部キーを持たない(ADR-0004)ため、
 * **プロジェクトを消しても図は残る。** 個別に消すこと。
 */

// ------------------------------------------------------------------ 共通

type ScenarioState = Record<string, unknown>;

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** 「一般利用者」= 非adminの合成アカウント。プロジェクト単位の仕切りの検証に使う。 */
async function memberToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

function trackedProjects(ctx: ScenarioState): number[] {
  ctx.diagramProjectIds ??= [];
  return ctx.diagramProjectIds as number[];
}

function trackedDiagrams(ctx: ScenarioState): number[] {
  ctx.diagramIds ??= [];
  return ctx.diagramIds as number[];
}

async function createProject(request: APIRequestContext, ctx: ScenarioState): Promise<number> {
  const suffix = uniqueSuffix();
  const response = await request.post('/api/projects', {
    headers: await adminHeaders(request),
    data: { name: `E2E 937 ${suffix}`, slug: `e2e-937-${suffix}` },
  });
  expect(
    response.ok(),
    `プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  trackedProjects(ctx).push(id);
  return id;
}

/**
 * draw.io が出力する形の XML と SVG。実機の draw.io は要らない —
 * サービスから見た「drawio 由来」は、この形の内容が入っていることに尽きる
 * (エディタ操作は #937 のスコープ外。`diagram-storage.feature` の冒頭を参照)。
 */
function drawioXml(label: string): string {
  return '<mxfile host="embed.diagrams.net" modified="2026-09-07T00:00:00.000Z" agent="letsblog-e2e">'
    + '<diagram id="at11" name="Page-1"><mxGraphModel dx="800" dy="600" grid="1">'
    + '<root><mxCell id="0"/><mxCell id="1" parent="0"/>'
    + `<mxCell id="2" value="${label}" style="rounded=0;" vertex="1" parent="1">`
    + '<mxGeometry x="40" y="40" width="160" height="60" as="geometry"/></mxCell>'
    + '</root></mxGraphModel></diagram></mxfile>';
}

function drawioSvg(label: string): string {
  return '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="140">'
    + `<rect x="40" y="40" width="160" height="60" fill="none" stroke="#000000"/><text x="48" y="76">${label}</text>`
    + '</svg>';
}

interface DiagramFixture {
  id: number;
  name: string;
  label: string;
}

async function saveDiagram(
  request: APIRequestContext,
  ctx: ScenarioState,
  projectId: number,
  name: string
): Promise<DiagramFixture> {
  const label = `初版 ${uniqueSuffix()}`;
  const response = await request.post('/api/diagrams', {
    headers: await adminHeaders(request),
    data: { projectId, name, xml: drawioXml(label), svg: drawioSvg(label) },
  });
  expect(
    response.ok(),
    `ダイアグラムの保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  trackedDiagrams(ctx).push(id);
  return { id, name, label };
}

function currentDiagram(ctx: ScenarioState): DiagramFixture {
  const diagram = ctx.diagramCurrent as DiagramFixture | undefined;
  if (!diagram) {
    throw new Error('先に図を保存するステップを実行すること');
  }
  return diagram;
}

// -------------------------------------------------- レンダリング結果の受け皿

interface RenderOutcome {
  status: number;
  contentType: string;
  body: Buffer;
}

function seenRender(ctx: ScenarioState): RenderOutcome {
  const outcome = ctx.diagramRender as RenderOutcome | undefined;
  if (!outcome) {
    throw new Error('先に描画を要求するステップを実行すること');
  }
  return outcome;
}

/** レンダリング要求の応答を JSON として読む。エラー本文の検証に使う。 */
function renderErrorMessage(outcome: RenderOutcome): string {
  const text = outcome.body.toString('utf8');
  let parsed: { error?: unknown };
  try {
    parsed = JSON.parse(text) as { error?: unknown };
  } catch {
    throw new Error(`エラー応答がJSONではありません (status=${outcome.status}): ${text.slice(0, 300)}`);
  }
  expect(typeof parsed.error, `エラー応答に説明(error)がありません: ${text.slice(0, 300)}`).toBe('string');
  return parsed.error as string;
}

/**
 * 「理由の分かるエラー」の判定。3つを同時に満たすことを求める。
 *
 * - 生の 500 ではない(#937 の受け入れ基準が名指ししている)
 * - 説明が本文に入っている(空でない `error`)
 * - Javaのスタックトレースを外へ出していない
 */
function expectUnderstandableError(outcome: RenderOutcome, what: string): string {
  // curl がそもそも応答を得られなかった場合(サービス停止等)はステータスが0になる。
  // 「エラーを返した」と取り違えないよう、まず応答が有ることを確かめる。
  expect(outcome.status, `${what}の要求に応答が返っていない`).toBeGreaterThan(0);
  expect(outcome.status, `${what}が生の500で落ちている`).not.toBe(500);
  expect(outcome.status, `${what}が成功扱いになっている`).toBeGreaterThanOrEqual(400);
  const message = renderErrorMessage(outcome);
  expect(message.length, `${what}のエラー説明が空である`).toBeGreaterThan(0);
  for (const marker of ['\tat com.letsblog', 'java.lang.', 'Caused by:']) {
    expect(message, `${what}のエラーがスタックトレースを外へ出している(${marker})`).not.toContain(marker);
  }
  return message;
}

// ------------------------------------------------------------ PNG の読み取り

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

interface PngChunk {
  type: string;
  data: Buffer;
}

function pngChunks(png: Buffer): PngChunk[] {
  const chunks: PngChunk[] = [];
  let offset = 8;
  while (offset + 8 <= png.length) {
    const length = png.readUInt32BE(offset);
    const type = png.subarray(offset + 4, offset + 8).toString('latin1');
    chunks.push({ type, data: png.subarray(offset + 8, offset + 8 + length) });
    offset += 12 + length;
    if (type === 'IEND') {
      break;
    }
  }
  return chunks;
}

function pngDimensions(png: Buffer): { width: number; height: number } {
  const header = pngChunks(png).find((chunk) => chunk.type === 'IHDR');
  if (!header) {
    throw new Error('PNGにIHDRチャンクがありません');
  }
  return { width: header.data.readUInt32BE(0), height: header.data.readUInt32BE(4) };
}

// ------------------------------------------------------ PlantUML レンダリング

/** `.feature` に書いた `\n` は文字どおりの2文字なので、改行へ戻してから送る。 */
function unescapeNewlines(source: string): string {
  return source.replace(/\\n/g, '\n');
}

async function requestPlantUmlRender(
  request: APIRequestContext,
  ctx: ScenarioState,
  source: string
): Promise<void> {
  ctx.diagramRender = postJsonToServiceDirectly(
    'media',
    '/api/render/plantuml',
    { source },
    { token: await adminToken(request) }
  );
}

When(/^PlantUMLソース「([^」]*)」の描画を要求する$/, async ({ ctx, request }, source: string) => {
  await requestPlantUmlRender(request, ctx, unescapeNewlines(source));
});

When(
  /^要素を「(\d+)」個含む巨大なPlantUMLソースの描画を要求する$/,
  async ({ ctx, request }, count: string) => {
    const participants = Array.from({ length: Number(count) }, (_, i) => `participant P${i}`).join('\n');
    await requestPlantUmlRender(request, ctx, `@startuml\n${participants}\n@enduml\n`);
  }
);

Then('PNG画像が返る', async ({ ctx }) => {
  const outcome = seenRender(ctx);
  expect(
    outcome.status,
    `PlantUMLの描画が成功していない (body=${outcome.body.toString('utf8').slice(0, 300)})`
  ).toBe(200);
  expect(outcome.contentType, 'PNGとして返っていない').toContain('image/png');
  expect(
    outcome.body.subarray(0, 8).equals(PNG_SIGNATURE),
    'PNGのシグネチャが無い(画像として壊れている)'
  ).toBe(true);
});

/**
 * PlantUML スタブ(`infra/e2e-stubs/plantuml/`、#1802)が受け取ったソース。
 *
 * 受け入れテストの PlantUML は固定の画像を返すスタブなので、PNG の中身からは描いたソースが分からない。
 * 代わりに、media-service がスタブへ送ったソース(符号化をスタブが復号したもの)を状態から読む。
 * 他のシナリオも同じスタブを呼ぶので、直近1件ではなく記録(最新100件)のどれかが両方を含むことを見る。
 */
Then(
  /^PlantUMLスタブが受け取ったソースに「([^」]*)」「([^」]*)」が含まれる$/,
  async ({}, first: string, second: string) => {
    await requirePlantUmlStub();
    const state = (await (await fetch(`${PLANTUML_STUB_URL}/__control/state`)).json()) as { sources: string[] };
    const found = state.sources.some((source) => source.includes(first) && source.includes(second));
    expect(found, `PlantUMLスタブが受け取ったソースに「${first}」「${second}」を含むものが無い (記録=${state.sources.length}件)`)
      .toBe(true);
  }
);

Then('返ったPNGは幅も高さも1px以上ある', async ({ ctx }) => {
  const { width, height } = pngDimensions(seenRender(ctx).body);
  expect(width, '図の幅が0である').toBeGreaterThan(0);
  expect(height, '図の高さが0である').toBeGreaterThan(0);
});

Then('描画は失敗し、理由の分かるエラーが返る', async ({ ctx }) => {
  ctx.diagramRenderError = expectUnderstandableError(seenRender(ctx), 'PlantUMLの描画');
});

Then('エラーの説明からサイズ上限に達したと分かる', async ({ ctx }) => {
  const message = ctx.diagramRenderError as string;
  expect(
    message,
    `サイズ上限に達したと分かる説明になっていない: ${message}`
  ).toMatch(/414|URI_TOO_LONG|大きすぎ|サイズ/);
});

Then(/^PlantUML が図を拒否した入力の誤りとして、ステータス「(\d+)」で返る$/, async ({ ctx }, status: string) => {
  expect(seenRender(ctx).status, 'PlantUMLの拒否が入力の誤りとして返っていない').toBe(Number(status));
});

Then(
  /^エラーの説明は「([^」]*)」で始まり、「([^」]*)」を含まない$/,
  async ({ ctx }, prefix: string, forbidden: string) => {
    const message = ctx.diagramRenderError as string;
    expect(message.startsWith(prefix), `エラーの説明が「${prefix}」で始まっていない: ${message}`).toBe(true);
    expect(message, `拒否なのに「${forbidden}」と案内している`).not.toContain(forbidden);
  }
);

// ---- content のプレビュー(issue #1776) ----

Given('PlantUMLのプレビューを確かめるプロジェクトがある', async ({ ctx, request }) => {
  ctx.diagramProjectId = await createProject(request, ctx);
});

When('構文の誤った PlantUML タグを含む本文のプレビューを要求する', async ({ ctx, request }) => {
  const response = await request.post(`/api/projects/${ctx.diagramProjectId}/preview/render`, {
    headers: await adminHeaders(request),
    data: { markdown: '[plantuml]\nこれはPlantUMLではありません\n???\n[/plantuml]' },
  });
  expect(
    response.ok(),
    `プレビューの描画に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.diagramPreviewHtml = ((await response.json()) as { html: string }).html;
});

Then(/^プレビューのHTMLに「([^」]*)」が含まれる$/, async ({ ctx }, text: string) => {
  expect(ctx.diagramPreviewHtml as string).toContain(text);
});

Then(/^プレビューのHTMLに「([^」]*)」は含まれない$/, async ({ ctx }, text: string) => {
  expect(ctx.diagramPreviewHtml as string).not.toContain(text);
});

Then('応答は画像ではない', async ({ ctx }) => {
  const outcome = seenRender(ctx);
  expect(outcome.contentType, '失敗したのに画像が返っている').not.toContain('image/');
  expect(outcome.body.subarray(0, 8).equals(PNG_SIGNATURE), '失敗したのにPNGが返っている').toBe(false);
});

// ------------------------------------------------------- recharts レンダリング

/** `RechartsChartConfig`(services/media)の全フィールドを埋めた既定の定義。 */
const RECHARTS_BASE_CONFIG = {
  type: 'bar',
  data: [
    { month: '1月', amount: 120 },
    { month: '2月', amount: 240 },
  ],
  xAxisKey: 'month',
  seriesKeys: ['amount'],
  colors: ['#336699'],
  stacked: false,
  width: 480,
  height: 320,
  textColor: '#333333',
  gridColor: '#cccccc',
  yAxisLabel: '円',
};

async function requestRechartsRender(
  request: APIRequestContext,
  ctx: ScenarioState,
  config: Record<string, unknown>
): Promise<void> {
  ctx.diagramRender = postJsonToServiceDirectly(
    'media',
    '/api/render/recharts',
    config,
    { token: await adminToken(request) }
  );
}

When('月別売上の棒グラフの描画を要求する', async ({ ctx, request }) => {
  await requestRechartsRender(request, ctx, { ...RECHARTS_BASE_CONFIG });
});

When('系列の指定を欠いたチャート定義の描画を要求する', async ({ ctx, request }) => {
  await requestRechartsRender(request, ctx, { ...RECHARTS_BASE_CONFIG, seriesKeys: null });
});

/** 描画されたチャートのHTML(SVGを含む)。`RechartsRenderResponse` の `html`。 */
function renderedChartHtml(ctx: ScenarioState): string {
  const cached = ctx.diagramChartHtml as string | undefined;
  if (cached !== undefined) {
    return cached;
  }
  const outcome = seenRender(ctx);
  const html = (JSON.parse(outcome.body.toString('utf8')) as { html: string }).html;
  ctx.diagramChartHtml = html;
  return html;
}

Then('SVGのチャートが返る', async ({ ctx }) => {
  const outcome = seenRender(ctx);
  expect(
    outcome.status,
    `チャートの描画が成功していない (body=${outcome.body.toString('utf8').slice(0, 300)})`
  ).toBe(200);
  const html = renderedChartHtml(ctx);
  expect(html, 'SVGが返っていない').toContain('<svg');
  expect(html, '棒グラフの要素が描かれていない').toContain('recharts-bar');
});

Then(/^チャートの目盛りに「([^」]*)」「([^」]*)」が現れる$/, async ({ ctx }, first: string, second: string) => {
  const html = renderedChartHtml(ctx);
  expect(html, '軸の目盛りが描かれていない').toContain('recharts-cartesian-axis-tick');
  for (const needle of [first, second]) {
    expect(html, `目盛りに「${needle}」が無い(元データが反映されていない)`).toContain(needle);
  }
});

Then(/^チャートに指定した系列色「([^」]*)」が使われている$/, async ({ ctx }, color: string) => {
  expect(renderedChartHtml(ctx), `指定した系列色 ${color} が使われていない`).toContain(color);
});

Then(/^チャートにY軸ラベル「([^」]*)」が現れる$/, async ({ ctx }, label: string) => {
  expect(renderedChartHtml(ctx), `Y軸ラベル「${label}」が無い`).toContain(label);
});

Then('チャートの描画は失敗し、理由の分かるエラーが返る', async ({ ctx }) => {
  expectUnderstandableError(seenRender(ctx), 'チャートの描画');
});

Then('応答にSVGは含まれない', async ({ ctx }) => {
  expect(seenRender(ctx).body.toString('utf8'), '失敗したのにSVGが返っている').not.toContain('<svg');
});

// ------------------------------------------------------------ Penpot デザインファイル作成API(削除済み)

When('カスタムタグ用のデザインファイル作成APIを要求する', async ({ ctx, request }) => {
  ctx.diagramRender = postJsonToServiceDirectly(
    'media',
    // 検索(git grep)に掛かる識別子を、削除した事実の確認のためにも書き下さない。
    `/api/render/${'penpot'}/${'design-file'}`,
    { fileName: `AT-1750 ${uniqueSuffix()}`, promptContext: 'AT-1750 の受け入れテスト' },
    { token: await adminToken(request) }
  );
});

Then('そのAPIは存在せず、404が返る', async ({ ctx }) => {
  const outcome = seenRender(ctx);
  expect(outcome.status, `削除したAPIが404になっていない (body=${outcome.body.toString('utf8').slice(0, 300)})`).toBe(404);
});

// ------------------------------------------------------------ ダイアグラムの保管

Given('ダイアグラムを置くプロジェクトがある', async ({ ctx, request }) => {
  ctx.diagramProjectId = await createProject(request, ctx);
});

// 「もし」でも「かつ(前提の続き)」でも使うため Step で定義する。
Step(/^drawio で描いた図「([^」]*)」をそのプロジェクトへ保存する$/, async ({ ctx, request }, name: string) => {
  ctx.diagramCurrent = await saveDiagram(request, ctx, ctx.diagramProjectId as number, name);
});

async function fetchDiagram(
  request: APIRequestContext,
  id: number
): Promise<{ name: string; xml: string; svg: string }> {
  const response = await request.get(`/api/diagrams/${id}`, {
    headers: await adminHeaders(request),
  });
  expect(
    response.ok(),
    `ダイアグラムの取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as { name: string; xml: string; svg: string };
}

Then('保存した図のXMLとSVGがそのまま取り出せる', async ({ ctx, request }) => {
  const diagram = currentDiagram(ctx);
  const stored = await fetchDiagram(request, diagram.id);
  expect(stored.name, '保存した名前が変わっている').toBe(diagram.name);
  expect(stored.xml, 'draw.io のXMLがそのまま取り出せない').toBe(drawioXml(diagram.label));
  expect(stored.svg, '描画済みSVGがそのまま取り出せない').toBe(drawioSvg(diagram.label));
});

Step('その図のSVGを一度取得している', async ({ ctx, request }) => {
  const response = await request.get(`/api/diagrams/${currentDiagram(ctx).id}/svg`, {
    headers: await adminHeaders(request),
  });
  expect(response.ok(), `SVGの取得に失敗しました (status=${response.status()})`).toBe(true);
  ctx.diagramFirstSvg = await response.text();
});

When('その図のXMLとSVGを編集後の内容で保存し直す', async ({ ctx, request }) => {
  const diagram = currentDiagram(ctx);
  const label = `改訂版 ${uniqueSuffix()}`;
  const response = await request.put(`/api/diagrams/${diagram.id}`, {
    headers: await adminHeaders(request),
    data: { name: diagram.name, xml: drawioXml(label), svg: drawioSvg(label) },
  });
  expect(
    response.ok(),
    `ダイアグラムの更新に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.diagramPreviousLabel = diagram.label;
  ctx.diagramCurrent = { ...diagram, label };
});

Then('取り出した図のXMLとSVGは編集後の内容になっている', async ({ ctx, request }) => {
  const diagram = currentDiagram(ctx);
  const stored = await fetchDiagram(request, diagram.id);
  expect(stored.xml, '更新後のXMLが取り出せない').toBe(drawioXml(diagram.label));
  expect(stored.svg, '更新後のSVGが取り出せない').toBe(drawioSvg(diagram.label));
});

Then(/^そのプロジェクトのダイアグラム一覧に「([^」]*)」が現れる$/, async ({ ctx, request }, name: string) => {
  const response = await request.get(`/api/diagrams?projectId=${ctx.diagramProjectId as number}`, {
    headers: await adminHeaders(request),
  });
  expect(response.ok(), `ダイアグラム一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  const names = ((await response.json()) as { name: string }[]).map((diagram) => diagram.name);
  expect(names, `一覧に「${name}」が無い`).toContain(name);
});

Then(/^そのプロジェクトのダイアグラム一覧に「([^」]*)」は現れない$/, async ({ ctx, request }, name: string) => {
  const response = await request.get(`/api/diagrams?projectId=${ctx.diagramProjectId as number}`, {
    headers: await adminHeaders(request),
  });
  expect(response.ok(), `ダイアグラム一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  const names = ((await response.json()) as { name: string }[]).map((diagram) => diagram.name);
  expect(names, `削除したはずの「${name}」が一覧に残っている`).not.toContain(name);
});

Then('その図のSVGを取得すると、保存したSVGが画像として返る', async ({ ctx, request }) => {
  const diagram = currentDiagram(ctx);
  const response = await request.get(`/api/diagrams/${diagram.id}/svg`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'SVGが取得できない').toBe(200);
  expect(response.headers()['content-type'] ?? '', 'SVG画像として返っていない')
    .toContain('image/svg+xml');
  expect(await response.text(), '保存した内容と違うSVGが返っている').toBe(drawioSvg(diagram.label));
});

Then('取り直したSVGは編集後の内容になっている', async ({ ctx, request }) => {
  const diagram = currentDiagram(ctx);
  const response = await request.get(`/api/diagrams/${diagram.id}/svg`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'SVGが取得できない').toBe(200);
  ctx.diagramSecondSvg = await response.text();
  expect(ctx.diagramSecondSvg as string, '更新後のSVGが返っていない').toBe(drawioSvg(diagram.label));
});

Then('取り直したSVGに編集前の内容は残っていない', async ({ ctx }) => {
  const previousLabel = ctx.diagramPreviousLabel as string;
  expect(
    ctx.diagramSecondSvg as string,
    `更新前のSVG(${previousLabel})が返り続けている(キャッシュが残っている)`
  ).not.toContain(previousLabel);
  expect(
    ctx.diagramFirstSvg as string,
    '前提として取得した更新前のSVGが取れていない'
  ).toContain(previousLabel);
});

When('その図を削除する', async ({ ctx, request }) => {
  const response = await request.delete(`/api/diagrams/${currentDiagram(ctx).id}`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'ダイアグラムの削除に失敗しました').toBe(204);
});

Then('その図のSVGの取得は404になる', async ({ ctx, request }) => {
  const response = await request.get(`/api/diagrams/${currentDiagram(ctx).id}/svg`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), '削除した図のSVGがまだ取得できる').toBe(404);
});

// ------------------------------------------------------------ 認可

/** 未認証・非メンバーの要求の結果。ステップ間で受け渡す。 */
interface DeniedOutcome {
  status: number;
  body: string;
  what: string;
}

function seenDenied(ctx: ScenarioState): DeniedOutcome {
  const outcome = ctx.diagramDenied as DeniedOutcome | undefined;
  if (!outcome) {
    throw new Error('先に要求を送るステップを実行すること');
  }
  return outcome;
}

When('認証なしでダイアグラムの一覧を要求する', async ({ ctx, request }) => {
  const response = await request.get('/api/diagrams');
  ctx.diagramDenied = {
    status: response.status(),
    body: await response.text(),
    what: 'ダイアグラムの一覧',
  } satisfies DeniedOutcome;
});

When('認証なしでダイアグラムの作成を要求する', async ({ ctx, request }) => {
  const response = await request.post('/api/diagrams', {
    data: { projectId: 1, name: 'AT-11 未認証', xml: drawioXml('未認証'), svg: drawioSvg('未認証') },
  });
  ctx.diagramDenied = {
    status: response.status(),
    body: await response.text(),
    what: 'ダイアグラムの作成',
  } satisfies DeniedOutcome;
});

When('認証なしでダイアグラムの削除を要求する', async ({ ctx, request }) => {
  const response = await request.delete('/api/diagrams/1');
  ctx.diagramDenied = {
    status: response.status(),
    body: await response.text(),
    what: 'ダイアグラムの削除',
  } satisfies DeniedOutcome;
});

Then('認証が必要だとして拒否される', async ({ ctx }) => {
  const outcome = seenDenied(ctx);
  expect(outcome.status, `${outcome.what}が未認証で通っている`).toBe(401);
});

Given('ダイアグラムを置くプロジェクトが2つあり、一般利用者は片方だけのメンバーである', async ({ ctx, request }) => {
  const memberProjectId = await createProject(request, ctx);
  const otherProjectId = await createProject(request, ctx);

  const me = await request.get('/api/identity/me', {
    headers: { Authorization: `Bearer ${await memberToken(request)}` },
  });
  expect(me.ok(), `一般利用者の情報を取得できませんでした (status=${me.status()})`).toBe(true);
  const memberUserId = ((await me.json()) as { id: number }).id;

  const added = await request.post(`/api/projects/${memberProjectId}/users`, {
    headers: await adminHeaders(request),
    data: { userId: memberUserId, wpRole: 'editor' },
  });
  expect(
    added.ok(),
    `プロジェクトメンバーの追加に失敗しました (status=${added.status()}): ${await added.text()}`
  ).toBe(true);

  ctx.diagramMemberProjectId = memberProjectId;
  ctx.diagramOtherProjectId = otherProjectId;
  ctx.diagramMemberUserId = memberUserId;
});

Given('それぞれのプロジェクトに図が1件ずつ保存されている', async ({ ctx, request }) => {
  ctx.diagramMemberDiagram = await saveDiagram(
    request, ctx, ctx.diagramMemberProjectId as number, 'AT-11 自分のプロジェクトの図'
  );
  ctx.diagramOtherDiagram = await saveDiagram(
    request, ctx, ctx.diagramOtherProjectId as number, 'AT-11 他プロジェクトの図'
  );
});

Then('一般利用者は自分のプロジェクトの図を取得できる', async ({ ctx, request }) => {
  const diagram = ctx.diagramMemberDiagram as DiagramFixture;
  const response = await request.get(`/api/diagrams/${diagram.id}`, {
    headers: { Authorization: `Bearer ${await memberToken(request)}` },
  });
  expect(
    response.status(),
    `メンバーなのに自分のプロジェクトの図を取得できない: ${await response.text()}`
  ).toBe(200);
});

When('一般利用者が他プロジェクトの図の取得を要求する', async ({ ctx, request }) => {
  const diagram = ctx.diagramOtherDiagram as DiagramFixture;
  const response = await request.get(`/api/diagrams/${diagram.id}`, {
    headers: { Authorization: `Bearer ${await memberToken(request)}` },
  });
  ctx.diagramDenied = {
    status: response.status(),
    body: await response.text(),
    what: '他プロジェクトの図の取得',
  } satisfies DeniedOutcome;
});

When('一般利用者が他プロジェクトの図の削除を要求する', async ({ ctx, request }) => {
  const diagram = ctx.diagramOtherDiagram as DiagramFixture;
  const response = await request.delete(`/api/diagrams/${diagram.id}`, {
    headers: { Authorization: `Bearer ${await memberToken(request)}` },
  });
  ctx.diagramDenied = {
    status: response.status(),
    body: await response.text(),
    what: '他プロジェクトの図の削除',
  } satisfies DeniedOutcome;
});

Then('プロジェクトメンバーではないとして拒否される', async ({ ctx }) => {
  const outcome = seenDenied(ctx);
  expect(outcome.status, `${outcome.what}が非メンバーに通っている`).toBe(403);
  expect(outcome.body, `${outcome.what}の拒否理由が示されていない`).toContain('プロジェクトメンバー');
});

Then('他プロジェクトの図は消えずに残っている', async ({ ctx, request }) => {
  const diagram = ctx.diagramOtherDiagram as DiagramFixture;
  const response = await request.get(`/api/diagrams/${diagram.id}`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), '非メンバーの要求で図が削除されている').toBe(200);
});

// ------------------------------------------------------------ 後片付け

After({ tags: '@diagram' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  // ダイアグラムを先に消す。diagrams は projects への外部キーを持たない(ADR-0004)ため、
  // プロジェクトを先に消すと図が孤児として残る。
  for (const id of trackedDiagrams(ctx)) {
    await request.delete(`/api/diagrams/${id}`, { headers });
  }
  const memberUserId = ctx.diagramMemberUserId as number | undefined;
  const memberProjectId = ctx.diagramMemberProjectId as number | undefined;
  if (memberUserId !== undefined && memberProjectId !== undefined) {
    await request.delete(`/api/projects/${memberProjectId}/users/${memberUserId}`, { headers });
  }
  for (const id of trackedProjects(ctx)) {
    await request.delete(`/api/projects/${id}`, { headers });
  }
});
