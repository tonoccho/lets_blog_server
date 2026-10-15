/**
 * 外部依存スタブ(issue #928 / AT-2)への入口。
 *
 * スタブはコンテナネットワーク上ではサービス名(例 http://llm-stub:8080)で見えるが、
 * Playwright はホスト側で動くため 127.0.0.1 の公開ポートを使う
 * (docker-compose.e2e-stubs.yml の ports)。
 *
 * ## `@stub` シナリオはスタブ未起動なら「失敗」させる
 *
 * #843 では、LLM が使えない環境で `test.skip` に落とした結果、その分岐が**恒常的に
 * 未検証**のまま気づかれずに残った。同じ轍を踏まないため、`@stub` が付いたシナリオは
 * スタブが起動していなければスキップではなく**明示的なエラー**で落とす。
 * 「スタブを起動していないこと」は環境の不備であって、検証しなくてよい理由ではない。
 */
import { expect } from '@playwright/test';

export type StubName =
  | 'llm'
  | 'google-analytics'
  | 'adsense'
  | 'brave-search'
  | 'openai-image'
  | 'github'
  | 'comfyui'
  | 'x'
  | 'threads'
  | 'facebook'
  | 'linkedin'
  | 'hatena';

/** ホストから見たスタブの公開先。docker-compose.e2e-stubs.yml の ports と対応する。 */
export const STUB_URLS: Record<StubName, string> = {
  llm: 'http://127.0.0.1:18081',
  'google-analytics': 'http://127.0.0.1:18082',
  adsense: 'http://127.0.0.1:18083',
  'brave-search': 'http://127.0.0.1:18084',
  'openai-image': 'http://127.0.0.1:18085',
  github: 'http://127.0.0.1:18086',
  comfyui: 'http://127.0.0.1:18087',
  x: 'http://127.0.0.1:18088',
  threads: 'http://127.0.0.1:18090',
  facebook: 'http://127.0.0.1:18091',
  linkedin: 'http://127.0.0.1:18092',
  hatena: 'http://127.0.0.1:18093',
};

export const ALL_STUBS = Object.keys(STUB_URLS) as StubName[];

const START_HINT =
  'スタブを起動してください:\n'
  + '  docker compose -f docker-compose.yml -f docker-compose.e2e-stubs.yml up -d\n'
  + '  ./scripts/e2e-clear-llm-db-overrides.sh --yes\n'
  + '詳細は docs/ACCEPTANCE_TESTING.md の「外部依存スタブ」節。';

async function isUp(name: StubName): Promise<boolean> {
  try {
    const res = await fetch(`${STUB_URLS[name]}/health`, {
      signal: AbortSignal.timeout(2000),
    });
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * `@stub` シナリオの前提確認。1つでも落ちていればエラーを投げる(スキップしない)。
 *
 * まとめて確認するのは、1つずつ落とすと「スタブを1個ずつ起動しては失敗する」という
 * 無駄な往復になるため。最初の実行で不足しているものを全部見せる。
 */
export async function requireStubs(names: StubName[] = ALL_STUBS): Promise<void> {
  const down: StubName[] = [];
  for (const name of names) {
    if (!(await isUp(name))) down.push(name);
  }
  if (down.length > 0) {
    throw new Error(
      `外部依存スタブが起動していません: ${down.join(', ')}\n`
      + down.map((n) => `  ${n} → ${STUB_URLS[n]}/health`).join('\n')
      + `\n\n${START_HINT}`
    );
  }
}

/**
 * そのリクエスト**1回だけ**を失敗させるヘッダ。スタブを直接叩くシナリオはこれを使う。
 * スタブの状態を変えないため、並列に走る他のシナリオへ影響しない。
 */
export function forceStatusHeader(status: number): Record<string, string> {
  return { 'X-E2E-Stub-Force-Status': String(status) };
}

/** そのリクエスト1回だけを遅延させるヘッダ(呼び元のタイムアウト誘発)。 */
export function forceDelayHeader(delayMs: number): Record<string, string> {
  return { 'X-E2E-Stub-Force-Delay': String(delayMs) };
}

/**
 * 次の `count` 回の呼び出しを指定ステータスで失敗させる。count 省略で解除まで継続。
 *
 * **スタブ全体の状態を変える。** サービス越しにスタブを呼ばせる異常系シナリオ専用で、
 * 使うシナリオには `@mode:serial` を付けること。スタブを直接叩くだけなら
 * `forceStatusHeader()` を使う(並列実行に干渉しない)。
 */
export async function forceStubStatus(
  name: StubName,
  status: number,
  count?: number
): Promise<void> {
  const res = await fetch(`${STUB_URLS[name]}/__control/force`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status, count }),
  });
  expect(res.ok, `${name} スタブへのエラー注入に失敗した`).toBeTruthy();
}

/** 次の `count` 回の呼び出しを遅延させ、呼び元のタイムアウトを誘発する。 */
export async function forceStubDelay(
  name: StubName,
  delayMs: number,
  count?: number
): Promise<void> {
  const res = await fetch(`${STUB_URLS[name]}/__control/force`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ delayMs, count }),
  });
  expect(res.ok, `${name} スタブへの遅延注入に失敗した`).toBeTruthy();
}

/** 注入した仕込みと受信件数を戻す。状態を持つスタブ(github)は初期シードへ戻る。 */
export async function resetStub(name: StubName): Promise<void> {
  const res = await fetch(`${STUB_URLS[name]}/__control/reset`, { method: 'POST' });
  expect(res.ok, `${name} スタブのリセットに失敗した`).toBeTruthy();
}

export async function resetAllStubs(): Promise<void> {
  await Promise.all(ALL_STUBS.map(resetStub));
}

/** スタブが受け取った件数。「サービスが実際に外部を呼んだか」の確認に使う。 */
export async function stubRequestCount(name: StubName): Promise<number> {
  const res = await fetch(`${STUB_URLS[name]}/__control/state`);
  const state = (await res.json()) as { requests: number };
  return state.requests;
}

/**
 * スタブが受け取った、指定メソッド・パスのリクエスト件数(issue #1781)。
 *
 * `stubRequestCount` はパスを区別せず、生成と無関係な呼び出し(連携サービスの状況確認が出す
 * `GET /models` / `GET /api/ps` など)も数える。「生成の依頼が届いたか」を並列実行でも安定して
 * 判定するときは、こちらで `POST /chat/completions` のように絞って数える。
 */
export async function stubRequestCountFor(
  name: StubName,
  method: string,
  pathname: string
): Promise<number> {
  const res = await fetch(`${STUB_URLS[name]}/__control/state`);
  const state = (await res.json()) as { requestsByPath?: Record<string, number> };
  expect(state.requestsByPath, `${name} スタブがパス別の受信件数(requestsByPath)を返さない`).toBeDefined();
  return state.requestsByPath![`${method.toUpperCase()} ${pathname}`] ?? 0;
}

/** LLM スタブへ届いた生成の依頼(`POST /chat/completions`)の件数。 */
export async function llmGenerationRequestCount(): Promise<number> {
  return await stubRequestCountFor('llm', 'POST', '/chat/completions');
}

/**
 * ComfyUI のワークフロー(issue #1106)。
 *
 * media-service の `ComfyUiClient.buildWorkflow` が組み立てる形をそのまま写している。
 * スタブが読むのは `EmptyLatentImage.inputs.batch_size` と `KSampler.inputs.seed` で、
 * ノード番号ではなく `class_type` で探すため、番号は実物に合わせてあるだけである。
 *
 * ここに置くのは、スタブの決定性プローブ(steps/stubs.steps.ts)と ComfyUI スタブの
 * シナリオが**同じ形**を投げる必要があるため。片方だけ形が古くなると、決定性は通るのに
 * 枚数だけ落ちるという分かりにくい失敗になる。
 */
export function comfyUiWorkflow(options: {
  seed: number;
  batchSize: number;
  prompt?: string;
  checkpoint?: string;
}): Record<string, unknown> {
  const { seed, batchSize } = options;
  const prompt = options.prompt ?? 'a blue button on a white background';
  const checkpoint = options.checkpoint ?? 'v1-5-pruned-emaonly.safetensors';
  return {
    '4': { class_type: 'CheckpointLoaderSimple', inputs: { ckpt_name: checkpoint } },
    '5': { class_type: 'EmptyLatentImage', inputs: { width: 512, height: 512, batch_size: batchSize } },
    '6': { class_type: 'CLIPTextEncode', inputs: { text: prompt, clip: ['4', 1] } },
    '7': { class_type: 'CLIPTextEncode', inputs: { text: '', clip: ['4', 1] } },
    '3': {
      class_type: 'KSampler',
      inputs: {
        seed,
        steps: 20,
        cfg: 7.0,
        sampler_name: 'euler',
        scheduler: 'normal',
        denoise: 1.0,
        model: ['4', 0],
        positive: ['6', 0],
        negative: ['7', 0],
        latent_image: ['5', 0],
      },
    },
    '8': { class_type: 'VAEDecode', inputs: { samples: ['3', 0], vae: ['4', 2] } },
    '9': { class_type: 'SaveImage', inputs: { filename_prefix: 'letsblog', images: ['8', 0] } },
  };
}

/**
 * extensions のポータル(`GET /api/connections`)のスタブ(issue #1782)。
 *
 * lbs-net 上では `lbx-portal:3100`、ホストからは 127.0.0.1:18094 で見える
 * (docker-compose.e2e-stubs.yml の `portal-stub`)。
 *
 * `StubName` / `ALL_STUBS` には**入れない**。応答の本文と注入の状態を差し替えるシナリオ
 * (`stubs/portal-stub.feature`)が、`external-stubs.feature` など他のレーンの決定性確認と
 * 並列に走ると、差し替え中の本文を読ませてしまうため。
 */
export const PORTAL_STUB_URL = 'http://127.0.0.1:18094';

/** ポータルスタブが起動していなければ、スキップではなくエラーで落とす(#843)。 */
export async function requirePortalStub(): Promise<void> {
  let up = false;
  try {
    const res = await fetch(`${PORTAL_STUB_URL}/health`, { signal: AbortSignal.timeout(2000) });
    up = res.ok;
  } catch {
    up = false;
  }
  if (!up) {
    throw new Error(
      `ポータルスタブが起動していません: ${PORTAL_STUB_URL}/health\n\n${START_HINT}`
    );
  }
}

/**
 * GitLab のスタブ(issue #1796。`GET /users/sign_in` だけを模す)。
 *
 * lbs-net 上では `lbx-gitlab`(ポート 80。同じ PC の extensions の GitLab と同じ名前)、ホストからは
 * 127.0.0.1:18095 で見える(docker-compose.e2e-stubs.yml の `gitlab-stub`)。
 *
 * `StubName` / `ALL_STUBS` には**入れない**。連携サービスの状況の GitLab の行へ異常を注入する
 * シナリオ(`platform/gitlab-reachability.feature`、@destructive)が、他のレーンの決定性確認と
 * 並列に走ると、仕込んだ異常を読ませてしまうため。
 */
export const GITLAB_STUB_URL = 'http://127.0.0.1:18095';

/** GitLab スタブが起動していなければ、スキップではなくエラーで落とす(#843)。 */
export async function requireGitLabStub(): Promise<void> {
  let up = false;
  try {
    const res = await fetch(`${GITLAB_STUB_URL}/health`, { signal: AbortSignal.timeout(2000) });
    up = res.ok;
  } catch {
    up = false;
  }
  if (!up) {
    throw new Error(
      `GitLab スタブが起動していません: ${GITLAB_STUB_URL}/health\n\n${START_HINT}`
    );
  }
}

/**
 * PlantUML のスタブ(issue #1802。`GET /png/<encoded>` に固定の PNG を返し、受け取ったソースを記録する)。
 *
 * lbs-net 上では `lbx-plantuml:8080`(同じ PC の extensions の PlantUML と同じ名前)、ホストからは
 * 127.0.0.1:18096 で見える(docker-compose.e2e-stubs.yml の `plantuml-stub`)。
 *
 * `StubName` / `ALL_STUBS` には**入れない**。エラー注入と reset をするシナリオ(`stubs/plantuml-stub.feature`、
 * @destructive)が、`resetAllStubs` や他の図のシナリオと並列に走ると、記録したソースを消してしまうため。
 */
export const PLANTUML_STUB_URL = 'http://127.0.0.1:18096';

/** PlantUML スタブが起動していなければ、スキップではなくエラーで落とす(#843)。 */
export async function requirePlantUmlStub(): Promise<void> {
  let up = false;
  try {
    const res = await fetch(`${PLANTUML_STUB_URL}/health`, { signal: AbortSignal.timeout(2000) });
    up = res.ok;
  } catch {
    up = false;
  }
  if (!up) {
    throw new Error(
      `PlantUML スタブが起動していません: ${PLANTUML_STUB_URL}/health\n\n${START_HINT}`
    );
  }
}
