'use strict';
/**
 * Docker Engine API のスタブ(issue #1399)。
 *
 * platform-service の演算デバイス切り替え(`RestDockerEngineClient`)が実際に叩く経路だけを実装する。
 *
 *   GET  /containers/json?all=true      コンテナ一覧(このプロジェクトのラベル付き)
 *   GET  /containers/{id}/json          詳細(HostConfig.Runtime と State.Health.Status。issue #1585)
 *   POST /containers/{id}/start         起動(204)。拒否・稼働しないふるまいはシナリオで指定する
 *   POST /containers/{id}/stop          停止(204)
 *
 * これ以外(create / delete / exec / images ...)は 403 を返す。実物の docker-socket-proxy が
 * 開けていない操作を、スタブが黙って受け付けて「通ってしまう」テストを作らないための模倣である。
 *
 * ## なぜスタブが要るのか
 *
 * 受け入れ環境の ComfyUI コンテナを実際に start / stop すると共有スタックを壊す。platform の切り替え専用の
 * 向き先(`COMPUTE_DEVICE_DOCKER_BASE_URL`)だけをこのスタブへ向け、「GPU 構成なし」「両構成あり」
 * 「start しても running にならない」を状態として作る。ダッシュボードのコンテナ一覧は実物の proxy を
 * 向いたままなので、既存のシナリオに影響しない。
 *
 * ## シナリオの設定(共通土台の `/__control` の外。`/__control/reset` で既定へ戻る)
 *
 *   POST /__scenario
 *     {
 *       "containers": { "lbs-comfyui": "running", "lbs-comfyui-cpu": "exited" },   キーが無ければ「存在しない」
 *       "rejectStart": ["lbs-comfyui-cpu"],     start を 403 で拒否する
 *       "neverRunning": ["lbs-comfyui-cpu"],    start は 204 だが running にならない
 *       "neverHealthy": ["lbs-ollama-cpu"],      start で running にはなるが、ヘルスチェックが healthy にならない
 *       "runningAfterMs": 3000,                 start から running になるまでの遅延
 *       "runtimes": { "lbs-ollama": "nvidia" }  HostConfig.Runtime(無いコンテナは空文字 = 既定ランタイム)
 *     }
 *
 * `GET /__control/state` は `containers`(現在の状態)と `calls`(start / stop の呼び出し記録)を返す。
 * 「どのコンテナも操作されていない」を確かめるのは `calls` である。
 */
const { createStub } = require('../lib/stub');

const PROJECT = process.env.COMPOSE_PROJECT_NAME || 'lets_blog_server';
const PROJECT_LABEL = 'com.docker.compose.project';

/** 既定: 両構成があり、GPU 構成が稼働中・CPU 構成が停止中。 */
function defaults() {
  return {
    containers: { 'lbs-comfyui': 'running', 'lbs-comfyui-cpu': 'exited' },
    rejectStart: [],
    neverRunning: [],
    neverHealthy: [],
    runningAfterMs: 0,
    runtimes: {},
  };
}

let scenario = defaults();
/** コンテナ名 → running になる時刻(ms)。start から runningAfterMs 後。 */
let pendingRunning = {};
/** start / stop の記録。形式 "start:lbs-comfyui"。 */
let calls = [];

function reset() {
  scenario = defaults();
  pendingRunning = {};
  calls = [];
}

function idOf(name) {
  return `id-${name}`;
}

function nameOf(id) {
  return id.startsWith('id-') ? id.slice(3) : null;
}

/** 起動待ちの遅延が過ぎていれば running へ進める。 */
function settle() {
  const now = Date.now();
  for (const [name, at] of Object.entries(pendingRunning)) {
    if (now >= at) {
      if (scenario.containers[name] !== undefined) scenario.containers[name] = 'running';
      delete pendingRunning[name];
    }
  }
}

createStub({
  name: 'docker-engine',
  port: Number(process.env.PORT || 8080),
  onReset: reset,
  extraState: () => {
    settle();
    return { containers: scenario.containers, calls };
  },
  handle: async ({ method, pathname, body, res, sendJson }) => {
    if (method === 'POST' && pathname === '/__scenario') {
      let parsed;
      try {
        parsed = JSON.parse(body || '{}');
      } catch {
        sendJson(res, 400, { message: 'body must be JSON' });
        return true;
      }
      scenario = { ...defaults(), ...parsed, containers: parsed.containers || {} };
      pendingRunning = {};
      calls = [];
      sendJson(res, 200, { ok: true });
      return true;
    }

    if (method === 'GET' && pathname === '/containers/json') {
      settle();
      const items = Object.entries(scenario.containers).map(([name, state]) => ({
        Id: idOf(name),
        Names: [`/${name}`],
        State: state,
        Status: state === 'running' ? 'Up 1 minute' : 'Exited (0) 1 minute ago',
        Labels: { [PROJECT_LABEL]: PROJECT },
      }));
      sendJson(res, 200, items);
      return true;
    }

    const inspect = /^\/containers\/([^/]+)\/json$/.exec(pathname);
    if (method === 'GET' && inspect) {
      settle();
      const name = nameOf(decodeURIComponent(inspect[1]));
      if (!name || scenario.containers[name] === undefined) {
        sendJson(res, 404, { message: `No such container: ${inspect[1]}` });
        return true;
      }
      const running = scenario.containers[name] === 'running';
      sendJson(res, 200, {
        Id: idOf(name),
        Name: `/${name}`,
        HostConfig: { Runtime: (scenario.runtimes || {})[name] || '' },
        State: { Status: scenario.containers[name], Health: { Status: running && !(scenario.neverHealthy || []).includes(name) ? 'healthy' : 'starting' } },
      });
      return true;
    }

    const action = /^\/containers\/([^/]+)\/(start|stop)$/.exec(pathname);
    if (method === 'POST' && action) {
      settle();
      const name = nameOf(decodeURIComponent(action[1]));
      if (!name || scenario.containers[name] === undefined) {
        sendJson(res, 404, { message: `No such container: ${action[1]}` });
        return true;
      }
      calls.push(`${action[2]}:${name}`);
      if (action[2] === 'stop') {
        delete pendingRunning[name];
        scenario.containers[name] = 'exited';
      } else if (scenario.rejectStart.includes(name)) {
        sendJson(res, 403, { message: `start ${name} is forbidden (stub)` });
        return true;
      } else if (!scenario.neverRunning.includes(name)) {
        if (scenario.runningAfterMs > 0) {
          pendingRunning[name] = Date.now() + scenario.runningAfterMs;
        } else {
          scenario.containers[name] = 'running';
        }
      }
      res.writeHead(204);
      res.end();
      return true;
    }

    if (method !== 'GET') {
      // 実物の docker-socket-proxy が開けていない書き込み(create / delete / exec / images ...)。
      sendJson(res, 403, { message: `${method} ${pathname} is not allowed (stub mimics docker-socket-proxy)` });
      return true;
    }
    return false;
  },
});
