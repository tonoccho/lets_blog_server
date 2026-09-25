import { execFileSync } from 'node:child_process';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * Penpot のサブパス中継のステップ定義(issue #1012)。
 *
 * `default.conf` の `location /penpot` は宛先を `set $upstream_penpot ...` という変数に
 * 入れてから `proxy_pass` している。nginx は変数を使った宛先を**リクエストごとに**
 * 名前解決するため、宛先が存在しないホスト名でも `nginx -t` は通り、コンテナも
 * healthy のまま起動する。壊れていることが分かるのは利用者がアクセスした瞬間だけで、
 * その症状は 502 という「上流が落ちている」ようにしか見えない形で現れる。
 *
 * したがってここでは設定ファイルの字面ではなく、**稼働中の reverse-proxy が実際に
 * 中継できること**を確かめる。最後のシナリオは同じ誤りが他の location に無いことを、
 * コンテナ内の実配置(/etc/nginx/conf.d/default.conf)から宛先を総当たりして固定する。
 */

/** reverse-proxy のコンテナ名(docker-compose.yml の container_name)。 */
const REVERSE_PROXY = 'lbs-reverse-proxy';

/** reverse-proxy コンテナ内での nginx 設定の実配置。ホスト側のファイルではなく稼働中の中身を見る。 */
const NGINX_CONF_IN_CONTAINER = '/etc/nginx/conf.d/default.conf';

/** 利用者に案内している Penpot の入口(docs/DOCUMENTATION.md / docs/e2e-validation-guide.md)。 */
const PENPOT_ENTRY = '/penpot';

/**
 * Penpot 自身が応答したときにだけ現れる印。Penpot の index.html は必ず自身のタイトルと
 * フロントエンドのモジュールマップを含む。
 */
const PENPOT_MARKERS = ['<title>Penpot', './js/main-auth.js'];

/**
 * ログイン画面を組み立てるアセット。Penpot の index.html はこれらを**相対パス**で参照する
 * (`./js/config.js` のように書かれ、`<base href>` は無い)。つまり入口の URL が
 * `/penpot`(末尾スラッシュ無し)のままだと、ブラウザはこれらを `/js/...` として要求し、
 * `location /`(Next.js)へ落ちてログイン画面が組み上がらない。
 *   - `js/config.js`    : PENPOT_FLAGS 等をフロントエンドへ渡す設定スクリプト
 *   - `js/main-auth.js` : ログイン画面そのものを描画するモジュール
 */
const PENPOT_LOGIN_ASSETS = ['js/config.js', 'js/main-auth.js'];

/** nginx が上流を名前解決できなかったときにログへ出す文言。 */
const RESOLVE_FAILURE = 'could not be resolved';

/** `set $upstream_<name> <host>:<port>;` から宛先を取り出す。 */
const UPSTREAM_TARGET = /set\s+\$upstream_[a-z0-9_]+\s+([a-z0-9._-]+):(\d+)\s*;/gi;

interface ProbedResponse {
  status: number;
  body: string;
  url: string;
  /** 応答を取りに行く直前の時刻(RFC3339、秒精度)。ログを見る範囲の起点に使う。 */
  since: string;
}

interface UpstreamTarget {
  host: string;
  port: string;
}

function seen(ctx: Record<string, unknown>): ProbedResponse {
  const response = ctx.penpotResponse as ProbedResponse | undefined;
  if (!response) {
    throw new Error('先に Penpot の入口を開くステップを実行すること');
  }
  return response;
}

function docker(args: string[]): string {
  return execFileSync('docker', args, { encoding: 'utf8', timeout: 60_000 });
}

/** nginx のエラーログは標準エラーへ出るため、両方まとめて受け取る。 */
function dockerLogsSince(container: string, since: string): string {
  return execFileSync('sh', ['-c', `docker logs --since ${since} ${container} 2>&1`], {
    encoding: 'utf8',
    timeout: 60_000,
  });
}

/**
 * `docker logs --since` に渡せる時刻。ミリ秒を落とし、1秒分さかのぼる。
 * 秒精度で切り捨てると要求より後の時刻になり、直後に出たログを取りこぼすため。
 */
function rfc3339SecondsAgo(): string {
  return new Date(Date.now() - 1_000).toISOString().replace(/\.\d+Z$/, 'Z');
}

When('未認証で Penpot の入口を開く', async ({ ctx, request }) => {
  const since = rfc3339SecondsAgo();
  const response = await request.get(PENPOT_ENTRY);
  ctx.penpotResponse = {
    status: response.status(),
    body: await response.text(),
    url: PENPOT_ENTRY,
    since,
  } satisfies ProbedResponse;
});

Then('その応答は中継の失敗ではない', async ({ ctx }) => {
  const { status, url } = seen(ctx);
  // 502 は「宛先を名前解決できない」ときに nginx が返す唯一の外形。
  expect(status, `${url} が中継に失敗している`).not.toBe(502);
  expect(status, `${url} が中継に失敗している`).not.toBe(504);
});

Then('その応答は Penpot 自身が返している', async ({ ctx }) => {
  const { status, body, url } = seen(ctx);
  for (const marker of PENPOT_MARKERS) {
    expect(body, `${url} が Penpot 以外から返っている(${marker} が無い, status=${status})`)
      .toContain(marker);
  }
});

Then('Penpot のログイン画面の HTML が返る', async ({ ctx }) => {
  const { status, body, url } = seen(ctx);
  expect(status, `${url} が開けない`).toBe(200);
  // ログイン画面は SPA のモジュール `main-auth` が描画する。index.html の importmap に
  // その入口が載っていることが「ログイン画面を配信できる HTML」であることの印。
  expect(body, `${url} がログイン画面を組み立てる HTML ではない (status=${status})`)
    .toContain('./js/main-auth.js');
});

Then('ログイン画面が読み込むアセットも同じ経路で取得できる', async ({ ctx, request }) => {
  const { url } = seen(ctx);
  for (const asset of PENPOT_LOGIN_ASSETS) {
    // index.html が相対パスで参照するのと同じ解決結果(= /penpot/<asset>)を要求する。
    const target = `${PENPOT_ENTRY}/${asset}`;
    const response = await request.get(target);
    expect(response.status(), `${target} が取得できない(${url} からは相対パスで参照される)`)
      .toBe(200);
    expect(response.headers()['content-type'] ?? '', `${target} が JavaScript として配信されていない`)
      .toContain('javascript');
  }
});

Then('その間に reverse-proxy は名前解決に失敗していない', async ({ ctx }) => {
  const { since, url } = seen(ctx);
  // コンテナ起動時からの全ログを見ると、修正前に出た古い行を拾ってしまう。
  // 上のアクセスの直前を起点にすることで「今の中継で失敗したか」だけを見る。
  const offenders = dockerLogsSince(REVERSE_PROXY, since)
    .split('\n')
    .filter((line) => line.includes(RESOLVE_FAILURE));
  expect(offenders, `${url} の中継で上流を名前解決できていない:\n  ${offenders.join('\n  ')}`)
    .toEqual([]);
});

When('稼働中の reverse-proxy から中継先を全て取り出す', async ({ ctx }) => {
  const conf = docker(['exec', REVERSE_PROXY, 'cat', NGINX_CONF_IN_CONTAINER]);
  const targets: UpstreamTarget[] = [];
  for (const [, host, port] of conf.matchAll(UPSTREAM_TARGET)) {
    if (!targets.some((t) => t.host === host && t.port === port)) {
      targets.push({ host, port });
    }
  }
  expect(targets.length, '稼働中の設定から中継先を1つも取り出せていない').toBeGreaterThan(0);
  ctx.upstreamTargets = targets;
});

Then('その全てが名前解決でき TCP 接続もできる', async ({ ctx }) => {
  const targets = ctx.upstreamTargets as UpstreamTarget[];
  const offenders: string[] = [];
  for (const { host, port } of targets) {
    // 名前解決とTCP接続を分けて見る。ホスト名の誤り(#1012 の `penpot`)と
    // ポートの誤り(`penpot-frontend:80`。実際の listen は 8080)は別の誤りで、
    // どちらもリクエストが来るまで表に出ず、症状はどちらも 502 で見分けが付かない。
    try {
      docker(['exec', REVERSE_PROXY, 'getent', 'hosts', host]);
    } catch {
      offenders.push(`${host}:${port} … 名前解決できない(実在するサービス名/エイリアスではない)`);
      continue;
    }
    try {
      docker(['exec', REVERSE_PROXY, 'nc', '-z', '-w', '3', host, port]);
    } catch {
      offenders.push(`${host}:${port} … TCP接続できない(そのポートでlistenしていない)`);
    }
  }
  expect(offenders, `到達できない中継先がある:\n  ${offenders.join('\n  ')}`).toEqual([]);
});
