import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';
import {
  AUTHORIZATION_MATRIX_PATH,
  readAuthorizationMatrix,
  type AuthorizationMatrixRow,
} from '../support/authorizationMatrix';
import {
  NON_GATEWAY_ROUTED_PATHS,
  isUploadBucketPath,
  scanPublicEndpoints,
  toComparablePath,
  toSamplePath,
  type ControllerEndpoint,
} from '../support/endpoints';
import {
  fetchThroughGateway,
  floodGateway,
  gatewayApiGlobalLimit,
  gatewayUploadEndpointLimit,
  nextProbeClientIp,
  probeThroughGateway,
  sendThroughGateway,
  waitForContainerLog,
  type GatewayBodyResponse,
  type GatewayProbeResult,
  type GatewayResponse,
} from '../support/gateway';
import {
  AUTH_GATED_PATHS,
  DOMAIN_SERVICES,
  requestServiceDirectly,
  type DomainService,
} from '../support/services';

/**
 * 横断的品質(認可・ルーティング・レート制限・相関ID・縮退)のステップ定義
 * (issue #943 / AT-17)。
 */

// --------------------------------------------------------------- 認可マトリクス

/**
 * 一斉走査から外すパス。理由は2つあり、どちらも「叩くこと自体が有害」である。
 *
 * - {@link NON_GATEWAY_ROUTED_PATHS}: そもそも gateway に経路が無い(公開エンドポイントではない)
 * - {@link isUploadBucketPath}: gateway の upload-endpoint バケット(プロセス全体で10req/時)。
 *   8本のために枠を使い切り、同じ1時間に走る画像系シナリオを巻き添えで429にする
 */
function isProbeable(requestPath: string): boolean {
  return !NON_GATEWAY_ROUTED_PATHS.includes(requestPath) && !isUploadBucketPath(requestPath);
}

/** 表の「未認証」列が 401 と言い切っている行だけを対象にする(公開エンドポイントは対象外)。 */
function rowsRequiringAuthentication(rows: AuthorizationMatrixRow[]): AuthorizationMatrixRow[] {
  return rows.filter((row) => row.unauthenticated === '401' && isProbeable(row.path));
}

/** 表の「権限不足」列が 403 と言い切っている行。 */
function rowsRequiringAuthorization(rows: AuthorizationMatrixRow[]): AuthorizationMatrixRow[] {
  return rows.filter((row) => row.insufficient === '403' && isProbeable(row.path));
}

function describe(result: GatewayProbeResult): string {
  return `${result.method} ${result.path} -> ${result.status}`;
}

When('認可マトリクスで未認証401とされている全エンドポイントへ認証なしで要求する', async ({ ctx }) => {
  const rows = rowsRequiringAuthentication(readAuthorizationMatrix());
  expect(rows.length, `${AUTHORIZATION_MATRIX_PATH} に未認証401の行がありません`).toBeGreaterThan(50);
  ctx.matrixRows = rows;
  ctx.probeResults = probeThroughGateway(
    rows.map((row) => ({ method: row.method, path: toSamplePath(row.path) }))
  );
});

Then('すべて401で拒否される', async ({ ctx }) => {
  const results = ctx.probeResults as GatewayProbeResult[];
  const rejected = results.filter((result) => result.status !== 401);
  expect(
    rejected.map(describe),
    '認可マトリクスが「未認証なら401」としているのに、そうならないエンドポイントがある'
  ).toEqual([]);
});

When(
  '認可マトリクスで権限不足403とされている全エンドポイントへ一般ユーザーとして要求する',
  async ({ ctx, request }) => {
    const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
    const rows = rowsRequiringAuthorization(readAuthorizationMatrix());
    expect(rows.length, `${AUTHORIZATION_MATRIX_PATH} に権限不足403の行がありません`).toBeGreaterThan(50);
    ctx.matrixRows = rows;
    ctx.probeResults = probeThroughGateway(
      rows.map((row) => ({ method: row.method, path: toSamplePath(row.path), token }))
    );
  }
);

/**
 * 必須の入力(`@RequestBody` / 必須の `@RequestParam`)を宣言しているハンドラは、
 * 入力を組み立てずに叩くと Spring が400で返し、**認可チェックの手前で終わる**。
 * それらに403を要求するのは「本文の作り方」の検証であって認可の検証ではない。
 *
 * そこで2段構えにする。
 *   1. すべての行について「成功しないこと」— 守りたい #830 の退行そのもの
 *   2. 入力を要さない行について「403であること」— 認可が実際に効いていること
 *
 * どちらの条件も**応答を見てから決めない**。実装(`scanPublicEndpoints`)から先に決まる。
 */
function requiresInputLookup(): Set<string> {
  return new Set(
    scanPublicEndpoints()
      .filter((endpoint) => endpoint.requiresInput)
      .map((endpoint) => `${endpoint.method} ${toComparablePath(endpoint.path)}`)
  );
}

/**
 * 対象リソースの存在確認が認可チェックより先に走るエンドポイント(issue #1130、Option A)。
 *
 * `DiagramController#findAuthorized` / `GeneratedImageController#findAuthorized` は、
 * 所属プロジェクトで認可判定するために対象を一度読む必要があり、その読み出し
 * (`findOrThrow`)が認可チェックより先に走る。対象が存在しなければ、認可チェックへ
 * 届く前に404が返る。これは「認可の手前で対象の存在有無を未認可の利用者に漏らさない」
 * という標準的なセキュリティ慣行であり、正しい実装として受理する(`docs/AUTHORIZATION_MATRIX.md`
 * の該当行の備考に同じ理由を明記)。
 *
 * `toSamplePath` が `{id}` を固定の `1` に置き換えるため、この一斉走査は
 * 「id=1が環境にたまたま存在するか」に結果が左右されてはならない
 * (docs/ACCEPTANCE_TESTING.md §10)。そこでここに列挙したエンドポイントに限り、
 * 403と404のどちらも「拒否された」証跡として受理する。
 *
 * 表の同じ理由の行のうち PUT /api/diagrams/{id} と PUT /api/generated-images/{id}/tags は
 * `@RequestBody` が必須で、既に {@link requiresInputLookup} 側の理由(本文を組み立てずに
 * 叩くと400で終わる)で対象外になっているため、ここへ重ねて列挙する必要はない。
 */
function existenceCheckedBeforeAuthorizationLookup(): Set<string> {
  return new Set([
    'GET /api/diagrams/*',
    'GET /api/diagrams/*/svg',
    'DELETE /api/diagrams/*',
    'GET /api/generated-images/*',
    'GET /api/generated-images/*/file',
    'DELETE /api/generated-images/*',
  ]);
}

Then('どれも成功せず、本文を伴わない要求は403または404で拒否される', async ({ ctx }) => {
  const results = ctx.probeResults as GatewayProbeResult[];
  const rows = ctx.matrixRows as AuthorizationMatrixRow[];
  const succeeded = results.filter((result) => result.status >= 200 && result.status < 300);
  expect(
    succeeded.map(describe),
    '権限の無い利用者の要求が成功している(#830 の退行)'
  ).toEqual([]);

  const requiresInput = requiresInputLookup();
  const existenceCheckedFirst = existenceCheckedBeforeAuthorizationLookup();
  const notForbidden = results.filter((result, index) => {
    const key = `${result.method} ${toComparablePath(rows[index].path)}`;
    if (requiresInput.has(key)) {
      return false;
    }
    if (existenceCheckedFirst.has(key)) {
      return result.status !== 403 && result.status !== 404;
    }
    return result.status !== 403;
  });
  expect(
    notForbidden.map(describe),
    '認可マトリクスが「権限不足なら403」としているのに、そうならず(存在確認が先立つ行は404も'
      + '許容してなお)拒否もされていないエンドポイントがある'
  ).toEqual([]);
});

When('実装から公開エンドポイントを抽出する', async ({ ctx }) => {
  ctx.publicEndpoints = scanPublicEndpoints().filter(
    (endpoint) => !NON_GATEWAY_ROUTED_PATHS.includes(endpoint.path)
  );
});

Then('認可マトリクスに載っていない公開エンドポイントは無い', async ({ ctx }) => {
  const endpoints = ctx.publicEndpoints as ControllerEndpoint[];
  const documented = new Set(readAuthorizationMatrix().map((row) => toComparablePath(row.path)));
  const undocumented = endpoints
    .filter((endpoint) => !documented.has(toComparablePath(endpoint.path)))
    .map((endpoint) => `${endpoint.service}: ${endpoint.method} ${endpoint.path} (${endpoint.controller})`);

  expect(
    undocumented,
    `${AUTHORIZATION_MATRIX_PATH} に載っていない公開エンドポイントがある。`
      + '表へ行を追加するか、そのエンドポイントを廃止すること(#731 の陳腐化の再発防止)'
  ).toEqual([]);
});

// --------------------------------------------------- 投稿の公開・削除(#830 の退行検知)

/** このシナリオが作るサイトのフィクスチャ。後片付けのため ID を持ち回る。 */
interface SiteFixture {
  id: number;
  siteKey: string;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/**
 * 投稿先サイトを1件登録する。publishing-service の公開・削除は
 * 「サイトが属するプロジェクトのメンバー(または admin)」に限定されている(#830)。
 * サイトが存在しないと、その認可判定に届く前に「未登録のサイト」として404になるため、
 * 実在するサイトが要る。プロジェクトへは紐付けない(未紐付けサイトは admin のみ)。
 */
async function registerSiteFixture(request: APIRequestContext): Promise<SiteFixture> {
  const siteKey = `at17-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const response = await request.post('/api/sites', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: {
      name: `AT-17 authorization fixture ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'at17-fixture',
      },
    },
  });
  expect(
    response.ok(),
    `サイトのフィクスチャ登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return { id: ((await response.json()) as { id: number }).id, siteKey };
}

Given('管理者が投稿先サイトを1件登録している', async ({ ctx, request }) => {
  ctx.siteFixture = await registerSiteFixture(request);
});

When('一般ユーザーとして記事の公開と削除を要求する', async ({ ctx, request }) => {
  const site = ctx.siteFixture as SiteFixture;
  const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  ctx.publishResponse = await request.post('/api/posts/publish', {
    headers: { Authorization: `Bearer ${token}` },
    multipart: {
      site: site.siteKey,
      title: 'AT-17 unauthorized publish',
      markdown: '# 権限の無い利用者による公開',
    },
  });
  ctx.deleteResponse = await request.delete(`/api/posts/${site.siteKey}/1`, {
    headers: { Authorization: `Bearer ${token}` },
  });
});

Then('どちらも権限不足として拒否される', async ({ ctx }) => {
  const publishResponse = ctx.publishResponse as Awaited<ReturnType<APIRequestContext['post']>>;
  const deleteResponse = ctx.deleteResponse as Awaited<ReturnType<APIRequestContext['delete']>>;
  expect(publishResponse.status(), '権限の無い利用者が記事を公開できてしまう(#830)').toBe(403);
  expect(deleteResponse.status(), '権限の無い利用者が記事を削除できてしまう(#830)').toBe(403);
});

After({ tags: '@cross-cutting' }, async ({ ctx, request }) => {
  const site = ctx.siteFixture as SiteFixture | undefined;
  if (!site) {
    return;
  }
  await request.delete(`/api/sites/${site.id}`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
});

// --------------------------------------------------------------- ルーティング

When('実装から抽出した全公開エンドポイントへgateway経由で認証なしに要求する', async ({ ctx }) => {
  const endpoints = scanPublicEndpoints().filter((endpoint) => isProbeable(endpoint.path));
  expect(endpoints.length, '公開エンドポイントを抽出できていません').toBeGreaterThan(100);
  ctx.publicEndpoints = endpoints;
  ctx.probeResults = probeThroughGateway(
    endpoints.map((endpoint) => ({ method: endpoint.method, path: toSamplePath(endpoint.path) }))
  );
});

/**
 * gateway は認証ゲートを担わない(ADR-0008)。したがって **401 は下流サービスしか返せない**。
 * 認証なしで叩いて401が返ったなら、その要求は gateway のルート表で解決され、担当サービスの
 * 認証ゲートまで届いている。逆に経路が無ければ **gateway 自身が本文の無い404** を返す(#583)。
 * 認証なしで叩くのは、到達性の確認のために実データを書き換えないためでもある。
 *
 * 公開エンドポイント(認可マトリクスが「該当なし(公開エンドポイント)」としている行)は
 * 認証ゲートを持たないので401にはならない。これらには「gateway の経路なし404で**ない**」ことだけを
 * 求める。どちらの判定になるかは応答ではなく認可マトリクスで**先に**決まる。
 *
 * <b>どのサービスへ届いたか</b>までは見ない。それは
 * `services/gateway/src/test/java/com/letsblog/gateway/config/RouteControllerContractTest.java`
 * が静的に検証しており、そちらが正である(docs/ACCEPTANCE_TESTING.md §11)。
 */
function isDocumentedPublicEndpoint(): (endpointPath: string) => boolean {
  const publicPaths = new Set(
    readAuthorizationMatrix()
      .filter((row) => row.unauthenticated.includes('公開エンドポイント'))
      .map((row) => toComparablePath(row.path))
  );
  return (endpointPath: string) => publicPaths.has(toComparablePath(endpointPath));
}

/** gateway 自身の「経路が無い」応答。本文を持たない404はこれしかない(ProxyHandler#handle)。 */
function isRoutelessNotFound(result: GatewayProbeResult): boolean {
  return result.status === 404 && result.size === 0;
}

Then('どれもgatewayの経路なし404にはならず、担当サービスの認証ゲートが応答する', async ({ ctx }) => {
  const results = ctx.probeResults as GatewayProbeResult[];
  const endpoints = ctx.publicEndpoints as ControllerEndpoint[];
  const isPublic = isDocumentedPublicEndpoint();

  const unrouted = results.filter(isRoutelessNotFound);
  expect(
    unrouted.map(describe),
    'gateway のルート表に経路が無いエンドポイントがある(#861 / #771 / #913)'
  ).toEqual([]);

  const gateNotAnswering = results.filter(
    (result, index) => !isPublic(endpoints[index].path) && result.status !== 401
  );
  expect(
    gateNotAnswering.map(describe),
    'gateway 経由の要求に対して、担当サービスの認証ゲートが応答していない'
  ).toEqual([]);
});

/**
 * クエリ中継の検証で使うクライアントIP(issue #1002)。一斉走査(`probeThroughGateway`)や
 * レート制限のシナリオと枠を分けるため、`support/gateway.ts` が使う TEST-NET-3 の
 * 連番(198.51.100.1〜250)とは重ならない値を固定で使う。
 */
const CONTENT_CACHE_PROBE_CLIENT_IP = '198.51.100.251';

/** `/api/content-cache` の応答(必要な項目だけ)。 */
interface ContentCardResponse {
  url: string;
  data?: Record<string, string>;
}

When('gateway経由で {string} のカード情報を要求する', async ({ ctx, request }, url: string) => {
  const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  ctx.contentCacheRequestedUrl = url;
  // 拡張(apps/extension/src/apiClient.ts の resolveContentCache)と同じく、
  // encodeURIComponent で**1回だけ**エンコードして送る。
  ctx.contentCacheResponse = fetchThroughGateway({
    path: `/api/content-cache?url=${encodeURIComponent(url)}`,
    token,
    clientIp: CONTENT_CACHE_PROBE_CLIENT_IP,
  });
});

Then('カード情報が取得でき、要求したURLがそのまま返る', async ({ ctx }) => {
  const requestedUrl = ctx.contentCacheRequestedUrl as string;
  const response = ctx.contentCacheResponse as GatewayBodyResponse;

  expect(
    response.status,
    `gateway経由の GET /api/content-cache が失敗した(#1002): ${response.body}`
  ).toBe(200);

  const card = JSON.parse(response.body) as ContentCardResponse;
  expect(
    card.url,
    'content-service が受け取ったURLが、要求したURLと一致しない(gatewayが値を再エンコードしている。#1002)'
  ).toBe(requestedUrl);
  expect(
    card.data?.title ?? '',
    'カード情報(OGPのタイトル)が取得できていない'
  ).not.toBe('');
});

/**
 * gateway が下流へ付けるヘッダー。gateway を迂回した相手がこれを騙っても、
 * 認証ゲートは JWT だけを見る(ADR-0008 / #566 でヘッダーベースの信頼は撤去済み)。
 * AT-3 の「JWTが無ければ401」とは別に、**偽装したヘッダーで抜けられないこと**を見る。
 */
const FORGED_GATEWAY_HEADERS = {
  'X-Correlation-Id': 'at17-forged-correlation-id',
  'X-Forwarded-For': '203.0.113.7',
  'X-Forwarded-Proto': 'https',
};

When('全ドメインサービスへgateway由来のヘッダを偽装しJWT無しで直接アクセスする', async ({ ctx }) => {
  const statuses = {} as Record<DomainService, number>;
  for (const service of DOMAIN_SERVICES) {
    statuses[service] = requestServiceDirectly(service, AUTH_GATED_PATHS[service], {
      headers: FORGED_GATEWAY_HEADERS,
    }).status;
  }
  ctx.directStatuses = statuses;
});

Then('すべてのサービスが401で拒否される', async ({ ctx }) => {
  const statuses = ctx.directStatuses as Record<DomainService, number>;
  const passed = Object.entries(statuses).filter(([, status]) => status !== 401);
  expect(
    passed.map(([service, status]) => `${service} -> ${status}`),
    'gateway を迂回した未認証アクセスが認証ゲートを通っている(ADR-0008 の前提が崩れている)'
  ).toEqual([]);
});

// --------------------------------------------------------------- レート制限

/**
 * レート制限の検証に使うエンドポイント。
 *
 * - 未認証で到達できる(公開パス)ので、トークンの有効期限に左右されない
 * - `RateLimitWebFilter` が明示的に api-global バケットへ入れている(#781)ため、
 *   ログイン試行用の auth-endpoint(プロセス全体で5req/分)を消費しない
 * - 何も書き換えない
 */
const RATE_LIMITED_PATH = '/api/auth/setup-status';

/**
 * api-global の上限を確実に超える回数。
 *
 * 上限そのものを決め打ちしない(issue #1132)。`docker-compose.e2e-stubs.yml` は
 * 受け入れテスト実行時に `API_RATE_LIMIT_REQUESTS` を本番既定値(100)から引き上げるため、
 * ここで100を決め打ちすると引き上げ後の環境では上限に達せず前提が壊れる。
 * {@link gatewayApiGlobalLimit} が実際にコンテナへ設定されている値を読むので、
 * どちらの環境でも(引き上げていても、いなくても)確実に上限を超える。
 */
function overLimitRequestCount(): number {
  return gatewayApiGlobalLimit() + 5;
}

/**
 * 合成クライアントIPの採番は `nextProbeClientIp`(`../support/gateway`)へ一本化した(issue #1420)。
 *
 * ここには以前 `process.pid % 250` を起点にする独自の連番があった(#1132)。しかしそれは
 * ワーカー間の衝突を防げていない —— 実測では **Playwrightのワーカーのpidは6しか離れておらず**、
 * 6回呼べば隣のワーカーの開始位置に到達する。pid差がちょうど250の倍数なら1回目から衝突する
 * (#1420 の note_9566)。
 *
 * `nextProbeClientIp` は #995 で**まったく同じ失敗**(異なるWorkerが同じIPを選び、
 * 無関係なシナリオが429で落ちる。#943のQAで2回観測)を解決するために作られたもので、
 * 状態ファイルとロックでプロセスをまたいで連番を直列化する。ワーカー数の上限が無く、
 * `process.ppid` で1回の `playwright test` 実行にスコープされるため実行間でも分離される。
 * **同じ問題に2つの仕組みを置かない。**
 */

function floodUntilLimited(ctx: Record<string, unknown>): number[] {
  const clientIp = nextProbeClientIp();
  ctx.rateLimitClientIp = clientIp;
  const statuses = floodGateway(RATE_LIMITED_PATH, clientIp, overLimitRequestCount());
  ctx.rateLimitStatuses = statuses;
  return statuses;
}

When('あるクライアントがgatewayへ上限を超える要求を送る', async ({ ctx }) => {
  floodUntilLimited(ctx);
});

Given('あるクライアントがgatewayの上限に達している', async ({ ctx }) => {
  const statuses = floodUntilLimited(ctx);
  expect(statuses, '上限に達していません(前提が成立していない)').toContain(429);
});

Then('上限を超えた要求は429で拒否され、再試行までの時間が示される', async ({ ctx }) => {
  const statuses = ctx.rateLimitStatuses as number[];
  expect(statuses.filter((status) => status === 200).length, '制限前の要求が受理されていない')
    .toBeGreaterThan(0);
  expect(statuses, '上限を超えても429にならない(制限が効いていない)').toContain(429);

  // 429 のときは「いつ再試行してよいか」が分からなければ、クライアントは総当たりするしかない。
  const limited = sendThroughGateway({
    path: RATE_LIMITED_PATH,
    clientIp: ctx.rateLimitClientIp as string,
  });
  expect(limited.status).toBe(429);
  expect(limited.headers['retry-after'], '429 に再試行までの時間が付いていない').toBeTruthy();
});

When('別のクライアントが同じエンドポイントへ要求する', async ({ ctx }) => {
  // #1420 以降、`nextProbeClientIp` はプロセスをまたいでも同じ値を返さないので
  // このループは回らない。採番の不変条件が崩れたときの保険として残す。
  let otherIp = nextProbeClientIp();
  while (otherIp === ctx.rateLimitClientIp) {
    otherIp = nextProbeClientIp();
  }
  ctx.otherClientResponse = sendThroughGateway({ path: RATE_LIMITED_PATH, clientIp: otherIp });
});

Then('その要求は受理される', async ({ ctx }) => {
  const response = ctx.otherClientResponse as GatewayResponse;
  expect(
    response.status,
    '別のクライアントが巻き添えで制限されている(枠がクライアント単位に分かれていない。#749)'
  ).toBe(200);
});

/**
 * resilience4j の RateLimiter は `limit-refresh-period`(既定60秒)ごとに枠を戻す。
 * 待ち時間は最大でその1周期。固定の sleep ではなく、受理されるまで問い合わせて確かめる。
 */
When('制限の時間枠が明けるまで待つ', async ({ ctx }) => {
  const clientIp = ctx.rateLimitClientIp as string;
  const deadline = Date.now() + 90_000;
  let response = sendThroughGateway({ path: RATE_LIMITED_PATH, clientIp });
  while (response.status === 429 && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 3_000));
    response = sendThroughGateway({ path: RATE_LIMITED_PATH, clientIp });
  }
  ctx.afterWindowResponse = response;
});

Then('同じクライアントの要求が再び受理される', async ({ ctx }) => {
  const response = ctx.afterWindowResponse as GatewayResponse;
  expect(response.status, '時間枠が明けても制限が解除されない').toBe(200);
});

// ------------------------------------------ 画像生成設定のレート制限(issue #999)

/**
 * `GET /api/projects/{id}/image-settings` が upload-endpoint バケット
 * (プロセス全体で10req/時)を消費しないことの検証用フィクスチャ(issue #999)。
 *
 * ProjectController 側のフィクスチャ({@link registerSiteFixture}相当)はサイトを作るが、
 * ここではプロジェクトそのものが要る(image-settings はプロジェクト単位のAPIのため)。
 */
interface ImageSettingsRateLimitProjectFixture {
  id: number;
}

async function registerImageSettingsRateLimitProjectFixture(
  request: APIRequestContext
): Promise<ImageSettingsRateLimitProjectFixture> {
  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const response = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { name: `AT-17 image-settings rate-limit fixture ${suffix}`, slug: `at17-imgset-${suffix}` },
  });
  expect(
    response.ok(),
    `画像生成設定レート制限検証用プロジェクトの作成に失敗しました `
      + `(status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return { id: ((await response.json()) as { id: number }).id };
}

Given('管理者がプロジェクトを1件登録している', async ({ ctx, request }) => {
  ctx.imageSettingsRateLimitProject = await registerImageSettingsRateLimitProjectFixture(request);
});

/** upload-endpoint の既定上限(10req/時)を確実に上回る回数。 */
const IMAGE_SETTINGS_REQUEST_COUNT = 11;

When('管理者としてそのプロジェクトの画像生成設定を11回連続で取得する', async ({ ctx, request }) => {
  const project = ctx.imageSettingsRateLimitProject as ImageSettingsRateLimitProjectFixture;
  const token = await adminToken(request);
  const statuses: number[] = [];
  for (let i = 0; i < IMAGE_SETTINGS_REQUEST_COUNT; i += 1) {
    const response = sendThroughGateway({ path: `/api/projects/${project.id}/image-settings`, token });
    statuses.push(response.status);
  }
  ctx.imageSettingsStatuses = statuses;
});

Then('全て200で返り、429は一度も返らない', async ({ ctx }) => {
  const statuses = ctx.imageSettingsStatuses as number[];
  expect(
    statuses,
    '画像生成設定の取得がupload-endpointの枠(プロセス全体で10req/時)を消費し、'
      + '11回連続で呼べていない(#999)'
  ).toEqual(Array(IMAGE_SETTINGS_REQUEST_COUNT).fill(200));
});

// ------------------------------------- upload-endpoint 枠の受け入れテスト用余裕(issue #1286)

/**
 * `upload-endpoint` バケットが、受け入れテストの実消費をまかなえているかを検査する
 * (issue #1286)。
 *
 * 実際にHTTPを叩いて枠を消費させはしない — このバケットは受け入れテスト全体で共有される
 * 希少資源であり、検証のために消費してしまうと無関係な画像生成シナリオを巻き添えにする
 * (この Issue が固定しようとしている問題そのもの)。代わりに、gateway に実際に設定されて
 * いる上限を {@link gatewayUploadEndpointLimit} で読み、あらかじめ数えてある消費量から
 * 計算した必要最小値と比較するだけにする。
 *
 * 消費量の内訳(通常実行10 + `@slow`分2 = 12)は
 * `apps/web/e2e/features/media/image-generation.feature` 冒頭のコメントが唯一の値の
 * 出どころ(二重管理をしない)。1時間以内の再実行(AC2)も429無しでまかなうには、
 * 同じ1時間の枠の中で少なくとも2回分を吸収できる必要がある。
 */
const FULL_RUN_UPLOAD_ENDPOINT_CONSUMPTION = 12; // apps/web/e2e/features/media/image-generation.feature 冒頭コメント参照
const REQUIRED_UPLOAD_ENDPOINT_MINIMUM = FULL_RUN_UPLOAD_ENDPOINT_CONSUMPTION * 2; // 全件実行 + 1時間以内の再実行(issue #1286 AC1・AC2)

Then('upload-endpointの枠が全件実行の消費を再実行込みでまかなえている', () => {
  expect(
    gatewayUploadEndpointLimit(),
    `upload-endpointの枠(UPLOAD_RATE_LIMIT_REQUESTS)が、全件実行の消費(${FULL_RUN_UPLOAD_ENDPOINT_CONSUMPTION})`
      + `の1時間以内の再実行込みで少なくとも${REQUIRED_UPLOAD_ENDPOINT_MINIMUM}必要(issue #1286)`
  ).toBeGreaterThanOrEqual(REQUIRED_UPLOAD_ENDPOINT_MINIMUM);
});

After({ tags: '@cross-cutting' }, async ({ ctx, request }) => {
  const project = ctx.imageSettingsRateLimitProject as ImageSettingsRateLimitProjectFixture | undefined;
  if (!project) {
    return;
  }
  await request.delete(`/api/projects/${project.id}`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
});

// --------------------------------------------------------------- 相関ID

/**
 * 下流サービスに**ログ行を書かせる**ための要求(issue #943 / AT-17)。
 *
 * 各サービスはリクエストごとのアクセスログを持たない。成功した要求は下流に1行も残らないため、
 * 「相関IDが下流のログまで届いた」ことをそのままでは観測できない。
 * content-service の `/api/posts` は GET だけを受けるので、認証済みの POST は
 * Spring の `DefaultHandlerExceptionResolver` が 405 として解決し、その WARN が
 * MDC(`%X{correlationId}`)付きで1行残る。何も書き換えないまま、ログ出力だけを起こせる。
 */
const DOWNSTREAM_LOG_TRIGGER = { method: 'POST', path: '/api/posts' };
const DOWNSTREAM_CONTAINER = 'lbs-content';
const GATEWAY_CONTAINER_NAME = 'lbs-gateway';

When('相関IDを指定してgateway経由で要求する', async ({ ctx, request }) => {
  const correlationId = `at17-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  ctx.correlationId = correlationId;
  ctx.correlationResponse = sendThroughGateway({
    ...DOWNSTREAM_LOG_TRIGGER,
    token: await adminToken(request),
    headers: { 'X-Correlation-Id': correlationId },
  });
});

Then('下流サービスのログにその相関IDが記録される', async ({ ctx }) => {
  const correlationId = ctx.correlationId as string;
  expect(
    waitForContainerLog(DOWNSTREAM_CONTAINER, correlationId),
    `クライアントが指定した相関ID(${correlationId})が下流サービスのログに現れない。`
      + 'gateway が下流へ転送していないか、下流がMDCへ載せていない(#582)'
  ).toBe(true);
});

Then('gatewayと下流サービスの双方のログを同じ相関IDで串刺しできる', async ({ ctx }) => {
  const correlationId = ctx.correlationId as string;
  expect(
    waitForContainerLog(GATEWAY_CONTAINER_NAME, correlationId),
    `gateway のログに相関ID(${correlationId})が無い`
  ).toBe(true);
  expect(
    waitForContainerLog(DOWNSTREAM_CONTAINER, correlationId),
    `下流サービスのログに相関ID(${correlationId})が無い`
  ).toBe(true);
});

/**
 * project-service / publishing-service個別の相関ID伝播を検証する(issue #992)。
 * 汎用の DOWNSTREAM_CONTAINER 版と異なり、サービスごとに405を起こすメソッド/パスの組が違うため
 * シナリオアウトラインの例から渡す。
 */
When(/^相関IDを指定して「([^」]+)」「([^」]+)」をgateway経由で要求する$/, async (
  { ctx, request }, method: string, path: string
) => {
  const correlationId = `at17-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  ctx.correlationId = correlationId;
  ctx.correlationResponse = sendThroughGateway({
    method,
    path,
    token: await adminToken(request),
    headers: { 'X-Correlation-Id': correlationId },
  });
});

Then(/^gatewayと「([^」]+)」の双方のログを同じ相関IDで串刺しできる$/, async ({ ctx }, container: string) => {
  const correlationId = ctx.correlationId as string;
  expect(
    waitForContainerLog(GATEWAY_CONTAINER_NAME, correlationId),
    `gateway のログに相関ID(${correlationId})が無い`
  ).toBe(true);
  expect(
    waitForContainerLog(container, correlationId),
    `${container} のログに相関ID(${correlationId})が無い`
  ).toBe(true);
});

When('相関IDを指定せずにgateway経由で要求する', async ({ ctx }) => {
  ctx.correlationResponse = sendThroughGateway({ path: RATE_LIMITED_PATH });
});

/** gateway が採番する相関IDはUUID(`CorrelationIdWebFilter`)。 */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Then('応答ヘッダに採番された相関IDが付く', async ({ ctx }) => {
  const response = ctx.correlationResponse as GatewayResponse;
  const correlationId = response.headers['x-correlation-id'];
  expect(correlationId, '応答に相関IDが付いていない').toBeTruthy();
  expect(correlationId, `採番された相関IDがUUIDではない: ${correlationId}`).toMatch(UUID_PATTERN);
});
