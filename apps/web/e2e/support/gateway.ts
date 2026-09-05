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

const GATEWAY_CONTAINER = 'lbs-gateway';
const GATEWAY_ORIGIN = 'http://localhost:8080';

/**
 * 一斉走査で使うクライアントIP。`api-global` は1クライアント100req/分なので、
 * これを下回る単位(80件)で区切り、区切りごとに別のIPを使う。
 * TEST-NET-2(RFC 5737。文書用に予約され実在しない)から採る。
 */
const PROBE_CHUNK_SIZE = 80;
/**
 * 呼び出しをまたいで別のIPを使う。1回の走査の中で区切るだけでは足りない
 * (同じ1分の中で2つのシナリオが走ると、2つ目が1つ目の使った枠を引き継いでしまい、
 * ルーティングの検証が丸ごと429になる)。開始位置を乱数にしてあるのは、
 * ワーカーが別プロセスで並列に走る場合に同じIPから始めないため。
 */
let probeClientSequence = Math.floor(Math.random() * 250);
const nextProbeClientIp = (): string => {
  probeClientSequence = (probeClientSequence + 1) % 250;
  return `198.51.100.${probeClientSequence + 1}`;
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
