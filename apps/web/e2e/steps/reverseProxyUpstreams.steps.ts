import { execFileSync } from 'node:child_process';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * reverse-proxy の中継先のステップ定義(issue #1012 / #1752)。
 *
 * `default.conf` は宛先を `set $upstream_<name> ...` という変数に入れてから `proxy_pass` している。
 * nginx は変数を使った宛先を**リクエストごとに**名前解決するため、宛先が存在しないホスト名でも
 * `nginx -t` は通り、コンテナも healthy のまま起動する。壊れていることが分かるのは利用者が
 * アクセスした瞬間だけで、その症状は 502 という「上流が落ちている」ようにしか見えない形で現れる。
 *
 * したがってここでは設定ファイルの字面ではなく、**稼働中の reverse-proxy が実際に中継できること**を
 * 確かめる。「全ての中継先へ到達できる」は、コンテナ内の実配置(/etc/nginx/conf.d/default.conf)から
 * 宛先を総当たりして固定する。
 */

/** reverse-proxy のコンテナ名(docker-compose.yml の container_name)。 */
const REVERSE_PROXY = 'lbs-reverse-proxy';

/** reverse-proxy コンテナ内での nginx 設定の実配置。ホスト側のファイルではなく稼働中の中身を見る。 */
const NGINX_CONF_IN_CONTAINER = '/etc/nginx/conf.d/default.conf';

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
  const response = ctx.proxiedResponse as ProbedResponse | undefined;
  if (!response) {
    throw new Error('先に入口を開くステップを実行すること');
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

async function probe(
  ctx: Record<string, unknown>,
  request: { get(url: string): Promise<{ status(): number; text(): Promise<string> }> },
  path: string,
): Promise<void> {
  const since = rfc3339SecondsAgo();
  const response = await request.get(path);
  ctx.proxiedResponse = {
    status: response.status(),
    body: await response.text(),
    url: path,
    since,
  } satisfies ProbedResponse;
}

When('認証なしで web の入口を開く', async ({ ctx, request }) => {
  await probe(ctx, request, '/');
});

When('管理者として {string} を開く', async ({ ctx, page }, path: string) => {
  // ログイン済みの `page` のセッションで要求する。未認証だと web の認証ゲートが 307 でログイン画面へ寄せる。
  // `/penpot/` は web が末尾スラッシュを外す 308 を経て `/penpot` の 404 に着く(リダイレクトは追う)。
  const response = await page.request.get(path);
  const opened = (ctx.openedAsAdmin ?? []) as { path: string; status: number; body: string }[];
  opened.push({ path, status: response.status(), body: await response.text() });
  ctx.openedAsAdmin = opened;
});

Then('どちらの応答も 404 で、Penpot の画面ではない', async ({ ctx }) => {
  const opened = ctx.openedAsAdmin as { path: string; status: number; body: string }[];
  expect(opened.length, '開いた経路が無い').toBeGreaterThan(0);
  for (const { path, status, body } of opened) {
    expect(status, `${path} の応答が 404 ではない(削除した経路がまだ中継されている)`).toBe(404);
    expect(body, `${path} の応答に Penpot の画面が含まれている`).not.toContain('main-auth.js');
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
    // 名前解決とTCP接続を分けて見る。ホスト名の誤り(#1012)とポートの誤りは別の誤りで、
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
