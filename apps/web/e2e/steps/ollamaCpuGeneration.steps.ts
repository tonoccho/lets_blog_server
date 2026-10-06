import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * CPU構成のOllamaでのAI生成(`ai/generation-ollama-cpu.feature`、issue #1402)を支えるステップ。
 *
 * 通常の AT 経路は常にスタブ(`llm-stub`)へ向く。ここでは DB のシステム設定
 * (`llm_provider` / `llm_ollama_base_url` / `llm_ollama_model`。環境変数より優先される。
 * `AppSettingService`)を CPU 構成のコンテナへ書き換えて呼び出し、必ず元へ戻す。
 * 新しいタグ・環境変数・Playwright プロジェクトは足さない(#1401 の `@requires-real-ai-cpu`、
 * `at-destructive` の `workers: 1`)。`cpuAiGeneration.steps.ts`(ComfyUI)と同じ形。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** CPU構成のコンテナ名(`docker-compose.yml` の `ollama-cpu` の `container_name`)。 */
const CPU_CONTAINER = 'lbs-ollama-cpu';

/** `lbs-net` 上でコンテナ名が名前解決される。GPU 構成の `ollama` エイリアスとは衝突させない。 */
const CPU_OLLAMA_BASE_URL = `http://${CPU_CONTAINER}:11434/v1`;

/** 既定の `qwen2.5:7b-instruct`(4.7GB)は CPU で120秒に収まらない。約400MBの小さいモデルを使う。 */
const SMALL_MODEL = 'qwen2.5:0.5b-instruct';

/** `LLM_REQUEST_TIMEOUT_SECONDS`(既定120秒)。広げない。 */
const LLM_BUDGET_MS = 120_000;

/** `PlatformServiceClient` のキャッシュ TTL(5秒)を確実に越えるための待ち。 */
const CACHE_WAIT_MS = 6_000;

const KEYS = ['llm_provider', 'llm_ollama_base_url', 'llm_ollama_model'] as const;
type Key = (typeof KEYS)[number];

interface AppSettingStatus {
  key: string;
  value: string | null;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchSettings(request: APIRequestContext): Promise<AppSettingStatus[]> {
  const response = await request.get('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.ok(), `アプリ設定の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as AppSettingStatus[];
}

async function putSettings(request: APIRequestContext, updates: Record<Key, string>): Promise<void> {
  const response = await request.put('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: updates,
  });
  expect(
    response.ok(),
    `LLM接続設定の更新に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  await sleep(CACHE_WAIT_MS);
}

function docker(args: string[], timeout = 60_000): string {
  return execFileSync('docker', args, { cwd: REPO_ROOT, stdio: 'pipe', timeout }).toString().trim();
}

function containerHealth(): string {
  try {
    return docker(['inspect', '-f', '{{.State.Health.Status}}', CPU_CONTAINER]);
  } catch {
    return 'absent';
  }
}

Given('CPU構成のOllamaを起動し小さなモデルを用意する', async ({ ctx }) => {
  // 起動前の状態を覚え、After で元へ戻す(元から動いていたなら止めない)。
  const before = containerHealth();
  ctx.ollamaCpuWasRunning = before === 'healthy';
  if (!ctx.ollamaCpuWasRunning) {
    ctx.ollamaCpuStarted = true;
    // AT のスタックでは ollama 系は profile で抑止されている(#1090)。CPU 構成は profile `ollama-cpu`。
    try {
      docker(
        [
          'compose', '-p', 'lets_blog_server',
          '-f', 'docker-compose.yml', '-f', 'docker-compose.e2e-stubs.yml',
          '--profile', 'ollama-cpu', 'up', '-d', 'ollama-cpu',
        ],
        300_000
      );
    } catch (e) {
      throw new Error(
        `CPU構成のOllama(コンテナ ${CPU_CONTAINER})を起動できません。実機AIレーンを除外する場合は ` +
          `AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1 を設定してください: ${(e as Error).message}`
      );
    }
  }

  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline && containerHealth() !== 'healthy') await sleep(3_000);
  expect(containerHealth(), `${CPU_CONTAINER} が180秒以内に healthy になりません`).toBe('healthy');

  // 取得済みなら即座に終わる。モデルは保全ボリューム ollama_models に残し、後片付けで消さない。
  docker(['exec', CPU_CONTAINER, 'ollama', 'pull', SMALL_MODEL], 1_500_000);
});

Given('システム全体のLLM接続設定をCPU構成のOllamaへ切り替える', async ({ ctx, request }) => {
  const settings = await fetchSettings(request);
  // DB由来でなければ(=環境変数のスタブ向き)、復元時は空文字を送って DB 設定を削除する。
  // PUT より前に ctx へ記録し、PUT が失敗しても After で戻せるようにする。
  const original = {} as Record<Key, string>;
  for (const key of KEYS) {
    const s = settings.find((x) => x.key === key);
    expect(s, `${key} の設定項目が見つかりません`).toBeDefined();
    original[key] = s!.source === 'DATABASE' ? (s!.value ?? '') : '';
  }
  ctx.ollamaCpuOriginalSettings = original;
  await putSettings(request, {
    llm_provider: 'OLLAMA',
    llm_ollama_base_url: CPU_OLLAMA_BASE_URL,
    llm_ollama_model: SMALL_MODEL,
  });
});

When('短い本文でタグ提案を要求する', async ({ ctx, request }) => {
  const started = Date.now();
  const response = await request.post('/api/ai/tags', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { text: 'りんごは赤い果物です。' },
    timeout: LLM_BUDGET_MS + 60_000,
  });
  ctx.ollamaCpuElapsedMs = Date.now() - started;
  ctx.ollamaCpuResponse = { status: response.status(), body: await response.text() };
  console.log(`[real-ai-cpu] POST /api/ai/tags 所要時間: ${((ctx.ollamaCpuElapsedMs as number) / 1000).toFixed(1)}秒`);
});

Then('実機のLLMが120秒の予算内に応答を返す', async ({ ctx }) => {
  const result = ctx.ollamaCpuResponse as { status: number; body: string };
  expect(result.status, `CPU構成のOllamaでタグ提案に失敗しました (応答本文: ${result.body})`).toBe(200);
  const elapsedMs = ctx.ollamaCpuElapsedMs as number;
  expect(
    elapsedMs,
    `応答に ${(elapsedMs / 1000).toFixed(1)}秒かかり、LLM_REQUEST_TIMEOUT_SECONDS(120秒)を超えています。` +
      '予算は広げない。入力かモデルを見直す'
  ).toBeLessThanOrEqual(LLM_BUDGET_MS);
});

When('システム全体のLLM接続設定を切り替え前へ戻す', async ({ ctx, request }) => {
  await putSettings(request, ctx.ollamaCpuOriginalSettings as Record<Key, string>);
  ctx.ollamaCpuRestored = true;
});

Then('システム全体のLLM接続設定は切り替え前になっている', async ({ ctx, request }) => {
  const original = ctx.ollamaCpuOriginalSettings as Record<Key, string>;
  const settings = await fetchSettings(request);
  for (const key of KEYS) {
    const s = settings.find((x) => x.key === key)!;
    if (original[key] === '') {
      expect(s.source, `${key} の DB の上書きが残っています`).not.toBe('DATABASE');
    } else {
      expect(s.value, `${key} が元に戻っていません`).toBe(original[key]);
    }
  }
  expect(ctx.ollamaCpuRestored).toBe(true);
});

/** シナリオの途中で落ちても向き先を戻し、このシナリオが起動した CPU 構成のコンテナを止める。 */
After({ tags: '@requires-real-ai-cpu' }, async ({ ctx, request }) => {
  if (ctx.ollamaCpuOriginalSettings !== undefined && ctx.ollamaCpuRestored !== true) {
    await putSettings(request, ctx.ollamaCpuOriginalSettings as Record<Key, string>);
  }
  if (ctx.ollamaCpuStarted === true) {
    try {
      docker(['stop', CPU_CONTAINER], 120_000);
    } catch {
      // すでに止まっている
    }
  }
});
