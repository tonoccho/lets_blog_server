import type { APIRequestContext, Page } from '@playwright/test';
import { Before, After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';
import {
  BOTH_CONFIGURATIONS,
  CPU_CONTAINER,
  GPU_CONTAINER,
  getDockerEngineState,
  requireDockerEngineStub,
  resetDockerEngineStub,
  setDockerEngineScenario,
} from '../support/dockerEngineStub';

/**
 * ComfyUI の演算デバイス(GPU / CPU)切り替えの受け入れシナリオ(issue #1399、
 * `features/platform/compute-device.feature`)のステップ定義。
 *
 * 実コンテナは触らない。platform-service の切り替え専用の向き先を Docker Engine API スタブへ向け
 * (`docker-compose.e2e-stubs.yml`)、スタブの状態で「GPU 構成なし」「両構成あり」「start しても
 * running にならない」を作る。シナリオごとに `@docker-engine-stub` の Before で既定へ戻す。
 */

const STATUS_PATH = '/api/system-settings/compute-devices/comfyui';
const APPLY_PATH = `${STATUS_PATH}/apply`;

Before({ tags: '@docker-engine-stub' }, async () => {
  await requireDockerEngineStub();
  await resetDockerEngineStub();
  await setDockerEngineScenario(BOTH_CONFIGURATIONS);
});

After({ tags: '@docker-engine-stub' }, async () => {
  await resetDockerEngineStub();
});

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function userToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

async function openSystemSettings(page: Page): Promise<void> {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: '演算デバイス(ComfyUI)' })).toBeVisible({ timeout: 30_000 });
}

function containerFor(device: string): string {
  return device === 'GPU' ? GPU_CONTAINER : CPU_CONTAINER;
}

// ---- 前提: スタブの状態 ----

Given('Docker Engine APIスタブに、GPU構成が稼働中でCPU構成が停止中の両構成がある', async () => {
  await setDockerEngineScenario({ ...BOTH_CONFIGURATIONS, runningAfterMs: 3000 });
});

Given('Docker Engine APIスタブに、CPU構成だけがありGPU構成のコンテナは無い', async () => {
  await setDockerEngineScenario({ containers: { [CPU_CONTAINER]: 'running' } });
});

Given('Docker Engine APIスタブは、CPU構成を起動しても稼働状態にならない', async () => {
  await setDockerEngineScenario({ ...BOTH_CONFIGURATIONS, neverRunning: [CPU_CONTAINER] });
});

// ---- 操作 ----

When('システム設定画面へ移動する', async ({ page }) => {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
});

When('システム設定画面の演算デバイス欄でCPUを選んで適用する', async ({ page }) => {
  await openSystemSettings(page);
  await page.getByRole('radio', { name: 'CPU' }).check();
  await page.getByRole('button', { name: '適用する' }).click();
});

When('管理者としてAPIにGPUへの適用を直接送る', async ({ request, ctx }) => {
  const response = await request.post(APPLY_PATH, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { device: 'GPU' },
  });
  ctx.computeDeviceApplyStatus = response.status();
});

When('一般ユーザーとして演算デバイスの参照APIを呼ぶ', async ({ request, ctx }) => {
  const response = await request.get(STATUS_PATH, {
    headers: { Authorization: `Bearer ${await userToken(request)}` },
  });
  ctx.computeDeviceReadStatus = response.status();
});

When('一般ユーザーとして演算デバイスの適用APIを呼ぶ', async ({ request, ctx }) => {
  const response = await request.post(APPLY_PATH, {
    headers: { Authorization: `Bearer ${await userToken(request)}` },
    data: { device: 'CPU' },
  });
  ctx.computeDeviceApplyStatus = response.status();
});

// ---- 検証: 画面 ----

Then('演算デバイス欄に「適用中」が表示され、その後「適用が完了しました」が表示される', async ({ page }) => {
  const state = page.getByTestId('compute-device-apply-state');
  await expect(state).toContainText('適用中', { timeout: 30_000 });
  await expect(state).toContainText('適用が完了しました', { timeout: 60_000 });
});

Then('演算デバイス欄の適用結果に「適用に失敗しました」と上限時間内に完了しなかった理由が表示される', async ({ page }) => {
  const state = page.getByTestId('compute-device-apply-state');
  await expect(state).toContainText('適用に失敗しました', { timeout: 90_000 });
  await expect(state).toContainText('秒以内に');
  await expect(state).toContainText('元の構成(GPU)に戻しました');
});

Then(/^演算デバイス欄の現在の構成は「(.+)」と表示される$/, async ({ page }, label: string) => {
  await expect(page.getByTestId('compute-device-current')).toHaveText(`現在の構成: ${label}`, { timeout: 30_000 });
});

Then(/^演算デバイス欄ではGPUを選べず、理由として「(.+)」が表示される$/, async ({ page }, reason: string) => {
  await expect(page.getByRole('radio', { name: 'GPU' })).toBeDisabled();
  await expect(page.getByRole('radiogroup', { name: '演算デバイス' })).toContainText(reason);
});

Then('演算デバイス欄は表示されない', async ({ page }) => {
  // 一般ユーザーは管理画面に留まれず、リダイレクトが終わるまで待ってから欄の不在を確かめる。
  await expect(page).not.toHaveURL(/\/admin\/system-settings/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: '演算デバイス(ComfyUI)' })).toHaveCount(0);
});

// ---- 検証: Docker Engine API スタブ ----

Then(/^Docker Engine APIスタブでは(GPU|CPU)構成だけが稼働している$/, async ({}, device: string) => {
  const { containers } = await getDockerEngineState();
  const other = device === 'GPU' ? CPU_CONTAINER : GPU_CONTAINER;
  expect(containers[containerFor(device)]).toBe('running');
  expect(containers[other]).not.toBe('running');
});

Then(/^Docker Engine APIスタブでは元の(GPU|CPU)構成が稼働し、選んだ構成は止まっている$/, async ({}, device: string) => {
  const { containers } = await getDockerEngineState();
  const other = device === 'GPU' ? CPU_CONTAINER : GPU_CONTAINER;
  expect(containers[containerFor(device)]).toBe('running');
  expect(containers[other]).not.toBe('running');
});

Then('Docker Engine APIスタブでは、どのコンテナも起動も停止もされていない', async () => {
  const { calls } = await getDockerEngineState();
  expect(calls).toEqual([]);
});

// ---- 検証: API ----

Then('適用APIは4xxで拒否される', async ({ ctx }) => {
  const status = ctx.computeDeviceApplyStatus as number;
  expect(status, `適用APIの応答 ${status}`).toBeGreaterThanOrEqual(400);
  expect(status).toBeLessThan(500);
});

Then('演算デバイスのAPIは403を返す', async ({ ctx }) => {
  expect(ctx.computeDeviceReadStatus ?? 403).toBe(403);
  expect(ctx.computeDeviceApplyStatus ?? 403).toBe(403);
});
