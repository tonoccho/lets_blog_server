/**
 * gateway コンテナへ**直接**HTTPリクエストを送るためのヘルパー(issue #943 / AT-17)。
 *
 * ## なぜ reverse-proxy(https://localhost)経由にしないのか
 *
 * 1. **レート制限の枠を分けられる。** gateway の `api-global` バケットは
 *    `X-Forwarded-For` の**末尾**の値で分割される(#749)。nginx はクライアント申告値の後ろへ
 *    自分が観測したpeerアドレスを追記するので、公開URL経由では末尾を指定できず、
 *    テストホストの全リクエストが1つの枠(100req/分)を食い合う。全エンドポイントを
 *    走査するシナリオはこれだけで上限に達してしまう。gateway を直接叩けば末尾の値を
 *    指定でき、シナリオごとに独立した枠を使える。レート制限そのものの検証
 *    (429 が返る・他のクライアントが巻き添えにならない)にも同じ性質が要る。
 * 2. **検証対象が gateway だから。** 「gateway 経由で担当サービスへ到達するか」を見たいのであって
 *    nginx の設定を見たいのではない。
 *
 * gateway はホストにポートを公開していないため、`support/services.ts` と同じく
 * lbs-net 上のコンテナの中から curl を実行する。ここでは gateway 自身を踏み台にする。
 */
import { execFileSync } from 'node:child_process';
import { closeSync, openSync, readFileSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const GATEWAY_CONTAINER = 'lbs-gateway';
const GATEWAY_ORIGIN = 'http://localhost:8080';

/**
 * 一斉走査で使うクライアントIP。`api-global` は1クライアント100req/分なので、
 * これを下回る単位(80件)で区切り、区切りごとに別のIPを使う。
 * TEST-NET-2(RFC 5737。文書用に予約され実在しない)から採る。
 */
const PROBE_CHUNK_SIZE = 80;
const PROBE_IP_POOL_SIZE = 250;

/**
 * 払い出し済みの連番を全Workerプロセスで共有するための状態ファイル(issue #995)。
 *
 * 以前はこの連番をモジュール変数(= プロセス単位)として持っていた。`playwright.config.ts`
 * は `workers: process.env.CI ? 1 : undefined` としており、`CI` を設定しないローカル実行では
 * Playwright既定の並列Worker数(複数プロセス)で走る。各プロセスが独立したモジュール変数を
 * 持つ以上、開始位置を乱数にしても衝突を避けられるのは確率的でしかなく、実際に#943のQAで
 * 2回、異なるWorkerが同じIPを選んで無関係なシナリオが429で落ちた。
 *
 * OSの一時ディレクトリ上のファイルへ連番を書き出し、全Workerプロセスがそこを読み書きする
 * ことで、プロセス数によらず同じ連番(= 同じIP)が二重に払い出されないようにする。
 * 複数プロセスからの同時読み書きを直列化するため、ロックファイルによる排他制御を伴う
 * (下記 {@link acquireProbeLock})。
 *
 * ファイル名は**1回の `playwright test` 実行にスコープ**する。固定ファイル名だと、Worker間の
 * 衝突は直っても、同一ホスト上で無関係な別の `playwright test` 実行(開発者の別ターミナル、
 * 共有ホスト上の別ジョブ等)とカウンタ・ロックを共有してしまう(実行間の分離が失われる。
 * ホスト共有時の衝突は本プロジェクトで既知の運用課題、#1065)。`process.ppid` — このモジュールを
 * 読み込むWorkerプロセスの**親**プロセス、すなわちPlaywrightが全Workerをforkする側のテスト
 * ランナー本体のPID — は1回の実行内の全Workerで共通かつ安定している一方、別の実行(別の
 * ターミナル・別ジョブ)では別のプロセスなので別の値になる。これをファイル名へ含めることで、
 * 実行内ではWorkerをまたいで共有しつつ、実行間では自然に分離される。
 */
export function probeStateFilePath(scopePid: number): string {
  return join(tmpdir(), `lbs-e2e-probe-client-ip.${scopePid}.state`);
}
const PROBE_STATE_FILE = probeStateFilePath(process.ppid);
const PROBE_LOCK_FILE = `${PROBE_STATE_FILE}.lock`;
/**
 * 1回の実行での払い出し総数(issue #1422)。連番は剰余で一周するが、こちらは一周しない。
 * 総数が {@link PROBE_IP_POOL_SIZE} に達したら、使用中のIPを黙って再配布する代わりに例外で止める。
 * 消費量は `cat` で確認できる(`probeStateFilePath` と同じ ppid でスコープされる)。ファイルは消さないので、
 * PIDが再利用されても古い総数を引き継がないよう、{@link PROBE_COUNT_STALE_MS} より古ければ0件と見なす。
 */
const PROBE_COUNT_FILE = `${PROBE_STATE_FILE}.count`;
const PROBE_COUNT_STALE_MS = 60 * 60 * 1000;
/** ロック保持中に異常終了したプロセスが残したロックを、これより古ければ放棄する。 */
const PROBE_LOCK_STALE_MS = 30_000;
/** ロック取得を待つ上限。通常は連番の読み書きだけなのでほぼ即時に取得できる。 */
const PROBE_LOCK_TIMEOUT_MS = 10_000;

/** ロックファイルが古すぎる(保持していたプロセスが解放せずに終了した)かどうか。 */
function isProbeLockStale(): boolean {
  try {
    return Date.now() - statSync(PROBE_LOCK_FILE).mtimeMs > PROBE_LOCK_STALE_MS;
  } catch {
    return false; // 既に他プロセスが解放済み
  }
}

/** 状態ファイルへの排他アクセスを取得する。取得できるまで待つ(busy-wait)。 */
function acquireProbeLock(): void {
  const deadline = Date.now() + PROBE_LOCK_TIMEOUT_MS;
  for (;;) {
    try {
      // 'wx' はファイルが既に存在すると失敗する(= 排他的な作成)。この失敗自体を
      // 「他プロセスがロック中」の判定に使う。
      closeSync(openSync(PROBE_LOCK_FILE, 'wx'));
      return;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') {
        throw error;
      }
      if (isProbeLockStale()) {
        try {
          unlinkSync(PROBE_LOCK_FILE);
        } catch {
          // 放棄判定と削除の間に別プロセスが解放/再取得した。ループして取り直す。
        }
        continue;
      }
      if (Date.now() > deadline) {
        throw new Error(`probeClientIpのロック取得がタイムアウトしました: ${PROBE_LOCK_FILE}`);
      }
    }
  }
}

function releaseProbeLock(): void {
  try {
    unlinkSync(PROBE_LOCK_FILE);
  } catch {
    // 既に無い(放棄判定で他プロセスに削除された等)なら何もしない。
  }
}

function readProbeSequence(): number {
  try {
    const parsed = Number(readFileSync(PROBE_STATE_FILE, 'utf8').trim());
    return Number.isInteger(parsed) ? parsed : 0;
  } catch {
    // 初回実行で状態ファイルがまだ無い。以前の「開始位置を乱数にする」挙動を踏襲する。
    return Math.floor(Math.random() * PROBE_IP_POOL_SIZE);
  }
}

function readProbeCount(): number {
  try {
    if (Date.now() - statSync(PROBE_COUNT_FILE).mtimeMs > PROBE_COUNT_STALE_MS) {
      return 0;
    }
    const parsed = Number(readFileSync(PROBE_COUNT_FILE, 'utf8').trim());
    return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0;
  } catch {
    return 0;
  }
}

/**
 * 呼び出しをまたいで別のIPを使う。1回の走査の中で区切るだけでは足りない
 * (同じ1分の中で2つのシナリオが走ると、2つ目が1つ目の使った枠を引き継いでしまい、
 * ルーティングの検証が丸ごと429になる)。連番を状態ファイルで共有し、ロックで直列化する
 * ことで、Workerプロセスをまたいでも同じIPが払い出されないようにする(issue #995)。
 */
export const nextProbeClientIp = (): string => {
  acquireProbeLock();
  try {
    const issued = readProbeCount();
    if (issued >= PROBE_IP_POOL_SIZE) {
      throw new Error(
        `合成クライアントIPを${PROBE_IP_POOL_SIZE}件使い切りました。これ以上払い出すと使用中のIPを再配布し、` +
          '無関係なシナリオが429で落ちます(issue #1422)。プールを広げてください。',
      );
    }
    const sequence = (readProbeSequence() + 1) % PROBE_IP_POOL_SIZE;
    writeFileSync(PROBE_STATE_FILE, String(sequence));
    writeFileSync(PROBE_COUNT_FILE, String(issued + 1));
    return `198.51.100.${sequence + 1}`;
  } finally {
    releaseProbeLock();
  }
};

export interface GatewayProbe {
  method: string;
  path: string;
  /** 付ける場合は Bearer トークン。省略すると認証なしで送る。 */
  token?: string;
}

export interface GatewayProbeResult extends GatewayProbe {
  status: number;
  /** 応答ボディのバイト数。gateway 自身の「経路なし404」は本文を持たない(0)。 */
  size: number;
}

/**
 * 複数のリクエストをまとめて送る。1リクエストごとに `docker exec` すると
 * 200件で数分かかるため、シェルスクリプトを1回の `docker exec` へ流し込む。
 */
export function probeThroughGateway(probes: GatewayProbe[]): GatewayProbeResult[] {
  if (probes.length === 0) {
    return [];
  }
  const chunkClientIps = Array.from(
    { length: Math.ceil(probes.length / PROBE_CHUNK_SIZE) }, () => nextProbeClientIp()
  );
  const script = probes
    .map((probe, index) => {
      const clientIp = chunkClientIps[Math.floor(index / PROBE_CHUNK_SIZE)];
      const auth = probe.token ? `-H 'Authorization: Bearer ${probe.token}' ` : '';
      return `printf '%s %s\\n' ${index} "$(curl -s -o /dev/null`
        + ` -w '%{http_code}:%{size_download}' -X ${probe.method}`
        + ` -H 'X-Forwarded-For: ${clientIp}' ${auth}--max-time 20`
        + ` '${GATEWAY_ORIGIN}${probe.path}')"`;
    })
    .join('\n');

  const output = execFileSync('docker', ['exec', '-i', GATEWAY_CONTAINER, 'sh'], {
    input: script,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    timeout: 15 * 60 * 1000,
  });

  const parsed = new Map<number, { status: number; size: number }>();
  for (const line of output.split('\n')) {
    const match = /^(\d+) (\d+):(\d+)$/.exec(line.trim());
    if (match) {
      parsed.set(Number(match[1]), { status: Number(match[2]), size: Number(match[3]) });
    }
  }
  return probes.map((probe, index) => {
    const result = parsed.get(index);
    if (!result) {
      throw new Error(`gatewayへの要求結果を取得できませんでした: ${probe.method} ${probe.path}`);
    }
    return { ...probe, ...result };
  });
}

export interface GatewayResponse {
  status: number;
  /** ヘッダー名は小文字に正規化して返す。 */
  headers: Record<string, string>;
}

/** 1リクエストだけ送り、ステータスと応答ヘッダーを返す。 */
export function sendThroughGateway(options: {
  method?: string;
  path: string;
  token?: string;
  headers?: Record<string, string>;
  clientIp?: string;
}): GatewayResponse {
  const args = [
    'exec', GATEWAY_CONTAINER, 'curl', '-s', '-o', '/dev/null', '-D', '-',
    '--max-time', '30', '-X', options.method ?? 'GET',
  ];
  if (options.clientIp) {
    args.push('-H', `X-Forwarded-For: ${options.clientIp}`);
  }
  if (options.token) {
    args.push('-H', `Authorization: Bearer ${options.token}`);
  }
  for (const [name, value] of Object.entries(options.headers ?? {})) {
    args.push('-H', `${name}: ${value}`);
  }
  args.push(`${GATEWAY_ORIGIN}${options.path}`);

  const output = execFileSync('docker', args, { encoding: 'utf8', timeout: 60_000 });
  const lines = output.split('\n').map((line) => line.trim()).filter((line) => line !== '');
  const statusLine = lines.find((line) => line.startsWith('HTTP/'));
  if (!statusLine) {
    throw new Error(`gatewayからの応答を解釈できませんでした: ${output}`);
  }
  const headers: Record<string, string> = {};
  for (const line of lines) {
    const separator = line.indexOf(':');
    if (separator > 0 && !line.startsWith('HTTP/')) {
      headers[line.slice(0, separator).trim().toLowerCase()] = line.slice(separator + 1).trim();
    }
  }
  return { status: Number(statusLine.split(/\s+/)[1]), headers };
}

export interface GatewayBodyResponse {
  status: number;
  /** 応答本文(そのままの文字列)。 */
  body: string;
}

/**
 * 1リクエストだけ送り、ステータスと**応答本文**を返す(issue #1002)。
 *
 * `sendThroughGateway` はヘッダーしか返さないため、下流サービスが何を受け取ったかを
 * 本文から確かめる検証には使えない。クエリ文字列の中継(二重エンコードしていないこと)は
 * 応答本文に現れるので、本文まで読める入口をここに足す。
 *
 * `path` は**エンコード済みの**パス+クエリをそのまま渡すこと。`execFileSync` はシェルを
 * 介さないので、`%` や `&` を含んでいても書き換えられない。
 */
export function fetchThroughGateway(options: {
  method?: string;
  path: string;
  token?: string;
  clientIp?: string;
}): GatewayBodyResponse {
  const args = [
    'exec', GATEWAY_CONTAINER, 'curl', '-s', '-w', '\n%{http_code}',
    '--max-time', '90', '-X', options.method ?? 'GET',
  ];
  if (options.clientIp) {
    args.push('-H', `X-Forwarded-For: ${options.clientIp}`);
  }
  if (options.token) {
    args.push('-H', `Authorization: Bearer ${options.token}`);
  }
  args.push(`${GATEWAY_ORIGIN}${options.path}`);

  const output = execFileSync('docker', args, { encoding: 'utf8', timeout: 120_000 });
  const separator = output.lastIndexOf('\n');
  if (separator < 0) {
    throw new Error(`gatewayからの応答を解釈できませんでした: ${output}`);
  }
  return { status: Number(output.slice(separator + 1).trim()), body: output.slice(0, separator) };
}

/**
 * gateway コンテナに実際に効いている `api-global` の上限(`RateLimitProperties.apiGlobal`)を
 * `API_RATE_LIMIT_REQUESTS` 環境変数から読む(issue #1132)。
 *
 * `docker-compose.e2e-stubs.yml` は受け入れテスト実行時にこの値を引き上げるため
 * (`docs/API_RATE_LIMITING.md` 「Acceptance-test override」)、上限を超えさせる系の
 * シナリオ(`cross-cutting/rate-limit.feature`)が本番既定値の100を決め打ちすると、
 * 引き上げ後の環境では上限に達せず前提が壊れる。値を決め打ちせず、実際にコンテナへ
 * 設定されている値を都度読むことで、どちらの環境でも(引き上げていても、いなくても)
 * 正しく上限を超えられるようにする。
 *
 * 変数が未設定なら、gateway 自身の既定値(`RateLimitProperties`、100)にフォールバックする。
 */
export function gatewayApiGlobalLimit(): number {
  const DEFAULT_LIMIT = 100; // RateLimitProperties の既定値と同じ
  try {
    const output = execFileSync(
      'docker', ['exec', GATEWAY_CONTAINER, 'printenv', 'API_RATE_LIMIT_REQUESTS'],
      { encoding: 'utf8', timeout: 30_000 }
    ).trim();
    const parsed = Number(output);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_LIMIT;
  } catch {
    // 環境変数が未設定だと printenv は非0で終了する(=本番既定値のまま)。
    return DEFAULT_LIMIT;
  }
}

/**
 * gateway コンテナに実際に効いている `upload-endpoint` の上限
 * (`RateLimitProperties.uploadEndpoint`)を `UPLOAD_RATE_LIMIT_REQUESTS` 環境変数から読む
 * (issue #1286)。{@link gatewayApiGlobalLimit} と同じ理由・同じ作り —
 * `docker-compose.e2e-stubs.yml` が受け入れテスト実行時にこの値を引き上げるため
 * (`docs/API_RATE_LIMITING.md` 「Acceptance-test override」)、上限を決め打ちする検証は
 * 実際にコンテナへ設定されている値を都度読む必要がある。
 *
 * 変数が未設定なら、gateway 自身の既定値(`RateLimitProperties`、10)にフォールバックする。
 */
export function gatewayUploadEndpointLimit(): number {
  const DEFAULT_LIMIT = 10; // RateLimitProperties の既定値と同じ
  try {
    const output = execFileSync(
      'docker', ['exec', GATEWAY_CONTAINER, 'printenv', 'UPLOAD_RATE_LIMIT_REQUESTS'],
      { encoding: 'utf8', timeout: 30_000 }
    ).trim();
    const parsed = Number(output);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_LIMIT;
  } catch {
    // 環境変数が未設定だと printenv は非0で終了する(=本番既定値のまま)。
    return DEFAULT_LIMIT;
  }
}

/**
 * 同じクライアント(= 同じ `X-Forwarded-For` 末尾)から連続して送り、ステータスの列を返す。
 * レート制限の検証に使う。
 */
export function floodGateway(requestPath: string, clientIp: string, count: number): number[] {
  const script =
    `for i in $(seq 1 ${count}); do`
    + ` curl -s -o /dev/null -w '%{http_code}\\n' -H 'X-Forwarded-For: ${clientIp}'`
    + ` --max-time 20 '${GATEWAY_ORIGIN}${requestPath}';`
    + ' done';
  const output = execFileSync('docker', ['exec', '-i', GATEWAY_CONTAINER, 'sh'], {
    input: script,
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
    timeout: 10 * 60 * 1000,
  });
  return output.split('\n').map((line) => line.trim()).filter(Boolean).map(Number);
}

/**
 * コンテナのログに文字列が現れるまで待つ。相関IDの伝播の検証に使う。
 *
 * ログはリクエスト処理と非同期に書き出されるため、1回読んで無いことを結論にしない。
 * 見つからないまま制限時間に達したら false を返し、判定は呼び出し側(ステップ)に委ねる。
 */
export function waitForContainerLog(
  container: string, needle: string, timeoutMs = 30_000
): boolean {
  const deadline = Date.now() + timeoutMs;
  do {
    // 標準出力・標準エラーの両方を見る(Spring Boot の出力先は設定次第で変わる)。
    const output = execFileSync(
      'sh', ['-c', `docker logs --tail 400 ${container} 2>&1`], { encoding: 'utf8', timeout: 60_000 }
    );
    if (output.includes(needle)) {
      return true;
    }
    execFileSync('sleep', ['1']);
  } while (Date.now() < deadline);
  return false;
}

/**
 * コンテナのログから、`needle` を含む最初の1行が現れるまで待って、その行を返す(issue #1470)。
 * 1行の中に複数の項目が並んでいること(例: 所要時間ログ)を検証するのに使う。
 * 制限時間内に現れなければ undefined を返し、判定は呼び出し側に委ねる。
 */
export function waitForContainerLogLine(
  container: string, needle: string, timeoutMs = 30_000
): string | undefined {
  const deadline = Date.now() + timeoutMs;
  do {
    const output = execFileSync(
      'sh', ['-c', `docker logs --tail 400 ${container} 2>&1`], { encoding: 'utf8', timeout: 60_000 }
    );
    const line = output.split('\n').find((candidate) => candidate.includes(needle));
    if (line !== undefined) {
      return line;
    }
    execFileSync('sleep', ['1']);
  } while (Date.now() < deadline);
  return undefined;
}
