import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * CPU構成のComfyUIでの画像生成(`media/image-generation-cpu.feature`、issue #1401)を支えるステップ。
 *
 * 通常の AT 経路は常にスタブ(`comfyui-stub`)へ向く。ここでは DB のシステム設定 `comfyui_base_url`
 * (環境変数より優先される。`AppSettingService`)を CPU 構成のコンテナへ書き換えて生成し、
 * 必ず元へ戻す。新しい compose overlay や Playwright プロジェクトは足さない(`at-destructive` の
 * `workers: 1` で他シナリオと同時実行されない)。
 *
 * 画像生成の共通ステップ(プロジェクト作成・生成画像の一覧確認)は `media.steps.ts` のものを使う。
 */

/** CPU構成のコンテナ名(`docker-compose.yml` の `comfyui-cpu` の `container_name`)。 */
const CPU_CONTAINER = 'lbs-comfyui-cpu';

/** media から見た CPU 構成の ComfyUI。`lbs-net` 上でコンテナ名が名前解決される。 */
const CPU_COMFYUI_BASE_URL = `http://${CPU_CONTAINER}:8188`;

const COMFYUI_BASE_URL_KEY = 'comfyui_base_url';

/**
 * 生成に使うチェックポイント: `stabilityai/sd-turbo`(1ステップで生成できる蒸留モデル)。
 * ライセンスは Stability AI Community License(研究・非商用・評価/テスト目的は無償。
 * 商用は年商 USD 1M 未満なら登録のうえ無償)。非ゲートで認証なしに取得できる。約 5.2GB。
 * ホストに偶然置かれたモデルには依存せず、無ければ導入する(`comfyui_models` は保全ボリューム)。
 */
const SD_TURBO_URL = 'https://huggingface.co/stabilityai/sd-turbo/resolve/main/sd_turbo.safetensors';
const SD_TURBO_FILE_NAME = 'sd_turbo.safetensors';

/** media のポーリング予算(batchSize=1)。`ComfyUiClient.MIN_POLL_ATTEMPTS`(120回 × 1秒)。 */
const POLL_BUDGET_MS = 120_000;

/** `PlatformServiceClient` のキャッシュ TTL(5秒)を確実に越えるための待ち。 */
const BASE_URL_CACHE_WAIT_MS = 6_000;

/** 初回のみ起きる 5.2GB のダウンロードを待つ上限。 */
const INSTALL_TIMEOUT_MS = 1_500_000;

/** ComfyUI(CPU)の起動直後に一覧が取れるようになるまで待つ上限。 */
const REACHABLE_TIMEOUT_MS = 180_000;

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

interface AppSettingStatus {
  key: string;
  value: string | null;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
}

async function fetchComfyUiBaseUrlSetting(request: APIRequestContext): Promise<AppSettingStatus> {
  const response = await request.get('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.ok(), `アプリ設定の取得に失敗しました (status=${response.status()})`).toBe(true);
  const setting = ((await response.json()) as AppSettingStatus[]).find((s) => s.key === COMFYUI_BASE_URL_KEY);
  expect(setting, `${COMFYUI_BASE_URL_KEY} の設定項目が見つかりません`).toBeDefined();
  return setting as AppSettingStatus;
}

async function putComfyUiBaseUrl(request: APIRequestContext, value: string): Promise<void> {
  const response = await request.put('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { [COMFYUI_BASE_URL_KEY]: value },
  });
  expect(
    response.ok(),
    `ComfyUI の向き先の更新に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  // media は生成のたびに platform から取るが、5秒キャッシュする。反映を待つ。
  await sleep(BASE_URL_CACHE_WAIT_MS);
}

Given('CPU構成のComfyUIが起動している', async () => {
  // 暗黙のスキップにしない: 起動していなければ、起動方法を添えて明示的に失敗させる。
  let running = '';
  try {
    running = execFileSync('docker', ['inspect', '-f', '{{.State.Running}}', CPU_CONTAINER], {
      stdio: 'pipe',
      timeout: 30_000,
    })
      .toString()
      .trim();
  } catch {
    running = 'absent';
  }
  expect(
    running,
    `CPU構成のComfyUI(コンテナ ${CPU_CONTAINER})が起動していません。` +
      '`docker compose --profile cpu up -d comfyui-cpu` で起動するか、' +
      '実機AIレーンを除外する場合は AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1 を設定してください'
  ).toBe('true');
});

Given('ComfyUIの向き先をCPU構成へ切り替える', async ({ ctx, request }) => {
  const current = await fetchComfyUiBaseUrlSetting(request);
  // DB由来でなければ(=環境変数のスタブ向き)、復元時は空文字を送って DB 設定を削除する。
  // PUT より前に ctx へ記録し、PUT が失敗しても After で戻せるようにする。
  ctx.cpuAiOriginalBaseUrl = current.source === 'DATABASE' ? (current.value ?? '') : '';
  await putComfyUiBaseUrl(request, CPU_COMFYUI_BASE_URL);
});

async function listCheckpoints(request: APIRequestContext, projectId: number): Promise<string[] | null> {
  const response = await request.get(`/api/projects/${projectId}/ai-models/comfyui/checkpoints`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  if (!response.ok()) return null;
  return ((await response.json()) as { checkpoints: string[] }).checkpoints;
}

Given('CPU向けの小さなチェックポイントが導入されている', async ({ ctx, request }) => {
  const projectId = ctx.mediaProjectId as number;

  // CPU 構成の ComfyUI が media から名前解決でき、応答するまで待つ(起動直後は一覧が取れない)。
  let checkpoints: string[] | null = null;
  const reachableDeadline = Date.now() + REACHABLE_TIMEOUT_MS;
  while (Date.now() < reachableDeadline) {
    checkpoints = await listCheckpoints(request, projectId);
    if (checkpoints !== null) break;
    await sleep(3_000);
  }
  expect(
    checkpoints,
    `media から ${CPU_COMFYUI_BASE_URL} のチェックポイント一覧を ${REACHABLE_TIMEOUT_MS}ms 以内に取得できません` +
      `(コンテナ ${CPU_CONTAINER} が lbs-net 上で応答していない)`
  ).not.toBeNull();

  if ((checkpoints as string[]).includes(SD_TURBO_FILE_NAME)) return;

  const token = await adminToken(request);
  const install = await request.post(`/api/projects/${projectId}/ai-models/comfyui/checkpoints/install`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { downloadUrl: SD_TURBO_URL, fileName: SD_TURBO_FILE_NAME },
  });
  expect(
    install.ok(),
    `チェックポイントの導入開始に失敗しました (status=${install.status()}): ${await install.text()}`
  ).toBe(true);
  const jobId = ((await install.json()) as { id: number }).id;

  const deadline = Date.now() + INSTALL_TIMEOUT_MS;
  let job: { status: string; resultPayload: string | null } = { status: 'pending', resultPayload: null };
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${jobId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.ok(), `ジョブの取得に失敗しました (status=${response.status()})`).toBe(true);
    job = (await response.json()) as typeof job;
    if (job.status !== 'running' && job.status !== 'pending') break;
    await sleep(5_000);
  }
  expect(job.status, `導入ジョブが完了しませんでした: ${job.resultPayload}`).toBe('done');

  const after = await listCheckpoints(request, projectId);
  expect(after, `導入後の一覧に ${SD_TURBO_FILE_NAME} が現れません`).toContain(SD_TURBO_FILE_NAME);
  // 導入したモデルは保全ボリュームに残す(再取得が重い)ので、後片付けで消さない。
});

When(
  /^そのプロジェクトで「([^」]+)」をCPU向けの低ステップ設定で画像生成を要求する$/,
  async ({ ctx, request }, prompt: string) => {
    const token = await adminToken(request);
    const listBefore = await request.get('/api/generated-images', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(listBefore.ok(), '生成画像一覧の取得に失敗しました').toBe(true);
    const idsBefore = ((await listBefore.json()) as { id: number }[]).map((i) => i.id);

    const started = Date.now();
    const response = await request.post('/api/ai/image', {
      headers: { Authorization: `Bearer ${token}` },
      // SD-Turbo は 1 ステップ・CFG 1.0 が前提。512x512 が学習解像度。
      data: {
        prompt,
        projectId: ctx.mediaProjectId,
        checkpoint: SD_TURBO_FILE_NAME,
        steps: 1,
        cfgScale: 1.0,
        samplerName: 'euler',
        scheduler: 'simple',
        width: 512,
        height: 512,
        batchSize: 1,
      },
      timeout: POLL_BUDGET_MS + 60_000,
    });
    const elapsedMs = Date.now() - started;
    const body = await response.text();
    const images = response.ok() ? (JSON.parse(body) as { images: { id: number }[] }).images : [];
    // `media.steps.ts` の「返った画像がすべて生成画像の一覧に現れる」と同じ形で渡す。
    ctx.imageGeneration = { status: response.status(), body, images, idsBefore, chatGptStubCallsBefore: null };
    ctx.cpuAiElapsedMs = elapsedMs;
    console.log(`[real-ai-cpu] POST /api/ai/image 所要時間: ${(elapsedMs / 1000).toFixed(1)}秒`);
  }
);

Then('実機の生成が120秒の予算内に1枚の画像を返す', async ({ ctx }) => {
  const result = ctx.imageGeneration as { status: number; body: string; images: unknown[] };
  expect(result.status, `画像生成に失敗しました (応答本文: ${result.body})`).toBe(200);
  expect(result.images.length, `生成された画像が1枚ではありません: ${result.body}`).toBe(1);
  const elapsedMs = ctx.cpuAiElapsedMs as number;
  expect(
    elapsedMs,
    `生成に ${(elapsedMs / 1000).toFixed(1)}秒かかり、media のポーリング予算(120秒)を超えています。` +
      '予算の見直しが要る(#1111 の担当)。本 Issue では広げない'
  ).toBeLessThanOrEqual(POLL_BUDGET_MS);
});

When('ComfyUIの向き先をスタブへ戻す', async ({ ctx, request }) => {
  await putComfyUiBaseUrl(request, ctx.cpuAiOriginalBaseUrl as string);
  ctx.cpuAiRestored = true;
});

Then('ComfyUIの向き先はスタブになっている', async ({ ctx, request }) => {
  const current = await fetchComfyUiBaseUrlSetting(request);
  expect(current.value, '向き先がCPU構成のまま残っています').not.toBe(CPU_COMFYUI_BASE_URL);
  const original = ctx.cpuAiOriginalBaseUrl as string;
  if (original === '') {
    // 元は環境変数(スタブ向き)だった: DB の上書きが消えている。
    expect(current.source, 'DB の上書きが残っています').not.toBe('DATABASE');
  } else {
    expect(current.value, '元の向き先に戻っていません').toBe(original);
  }
  expect(ctx.cpuAiRestored).toBe(true);
});

/** シナリオの途中で落ちても向き先を必ず戻す。 */
After({ tags: '@requires-real-ai-cpu' }, async ({ ctx, request }) => {
  if (ctx.cpuAiOriginalBaseUrl === undefined || ctx.cpuAiRestored === true) return;
  await putComfyUiBaseUrl(request, ctx.cpuAiOriginalBaseUrl as string);
});
