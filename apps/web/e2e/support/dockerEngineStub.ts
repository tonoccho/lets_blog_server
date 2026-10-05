/**
 * Docker Engine API スタブ(issue #1399)への入口。
 *
 * ComfyUI の演算デバイス切り替えは、platform-service が docker-socket-proxy 経由で
 * コンテナを start / stop する。共有の受け入れ環境で実コンテナを止めると他のシナリオを壊すため、
 * platform の切り替え専用の向き先(`COMPUTE_DEVICE_DOCKER_BASE_URL`)だけをこのスタブへ向ける。
 * ダッシュボードのコンテナ一覧(`DOCKER_SOCKET_PROXY_BASE_URL`)は実物のままなので影響しない。
 *
 * 他のスタブ(`stubs.ts` の `STUB_URLS`)へは足さない。`requireStubs()` の既定は全スタブを要求するため、
 * 足すとこのスタブを持たない環境で無関係な `@stub` シナリオまで落ちる。
 */
import { expect } from '@playwright/test';

export const DOCKER_ENGINE_STUB_URL = 'http://127.0.0.1:18089';

export const GPU_CONTAINER = 'lbs-comfyui';
export const CPU_CONTAINER = 'lbs-comfyui-cpu';

export interface DockerEngineScenario {
  /** コンテナ名 → 状態。キーが無いコンテナは「存在しない」。 */
  containers: Record<string, string>;
  /** start を 403 で拒否するコンテナ名。 */
  rejectStart?: string[];
  /** start は 204 を返すが running にならないコンテナ名。 */
  neverRunning?: string[];
  /** start から running になるまでの遅延(ms)。 */
  runningAfterMs?: number;
}

export interface DockerEngineState {
  containers: Record<string, string>;
  calls: string[];
}

export async function requireDockerEngineStub(): Promise<void> {
  let ok = false;
  try {
    ok = (await fetch(`${DOCKER_ENGINE_STUB_URL}/health`, { signal: AbortSignal.timeout(2000) })).ok;
  } catch {
    ok = false;
  }
  if (!ok) {
    throw new Error(
      `Docker Engine API スタブが起動していません: ${DOCKER_ENGINE_STUB_URL}/health\n`
      + '  docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml up -d\n'
      + '詳細は docs/ACCEPTANCE_TESTING.md の「外部依存スタブ」節。'
    );
  }
}

export async function resetDockerEngineStub(): Promise<void> {
  const res = await fetch(`${DOCKER_ENGINE_STUB_URL}/__control/reset`, { method: 'POST' });
  expect(res.status, 'Docker Engine API スタブのリセットに失敗').toBe(200);
}

export async function setDockerEngineScenario(scenario: DockerEngineScenario): Promise<void> {
  const res = await fetch(`${DOCKER_ENGINE_STUB_URL}/__scenario`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(scenario),
  });
  expect(res.status, 'Docker Engine API スタブのシナリオ設定に失敗').toBe(200);
}

export async function getDockerEngineState(): Promise<DockerEngineState> {
  const res = await fetch(`${DOCKER_ENGINE_STUB_URL}/__control/state`);
  expect(res.status, 'Docker Engine API スタブの状態取得に失敗').toBe(200);
  return (await res.json()) as DockerEngineState;
}

/** 両構成があり、GPU 構成が稼働中・CPU 構成が停止中の既定の状態。 */
export const BOTH_CONFIGURATIONS: DockerEngineScenario = {
  containers: { [GPU_CONTAINER]: 'running', [CPU_CONTAINER]: 'exited' },
};
