/**
 * gateway を経由せず、各ドメインサービスへ**直接**HTTPリクエストを送るためのヘルパー
 * (issue #929 / AT-3)。
 *
 * ## なぜ docker exec なのか
 *
 * 認証ゲート(ADR-0008)は「gateway を迂回しても各サービスの SecurityConfig が
 * 認証を要求する」ことを保証する。これを検証するには **gateway を通さずに**
 * サービスへ到達しなければならない。
 *
 * ところが各サービスはホストにポートを公開していない(公開しているのは reverse-proxy の
 * 443 だけ)。Playwright はホストで動くので、そのままでは到達できない。
 * そこで lbs-net に参加しているコンテナの中から curl を実行する。
 *
 * 踏み台には gateway コンテナを使う。lbs-net の全サービスへ到達でき、curl を持ち、
 * 常時起動しているため。踏み台自身の状態は変えない(読み取りのみ)。
 */
import { execFileSync, spawnSync } from 'node:child_process';

/** 認証ゲートの検証対象。ADR-0008 の「全サービスが自分で認証を要求する」の"全"がこれ。 */
export const DOMAIN_SERVICES = [
  'identity',
  'project',
  'content',
  'media',
  'ai',
  'publishing',
  'analytics',
  'platform',
  'log-writer',
] as const;

export type DomainService = (typeof DOMAIN_SERVICES)[number];

/** 踏み台コンテナ。lbs-net 上にあり curl を持つもの。 */
const BASTION_CONTAINER = 'lbs-gateway';

export interface DirectResponse {
  status: number;
  body: string;
}

/**
 * サービスへ直接リクエストを送る。gateway もリバースプロキシも経由しない。
 *
 * @param service 対象サービス(compose のサービス名 = lbs-net 上のホスト名)
 * @param path    `/api/...` 形式のパス
 * @param options `token` を渡すと `Authorization: Bearer` を付ける。
 *                `rawAuthorization` は Bearer を含む生のヘッダ値(改竄トークンの検証用)。
 *                `headers` は任意の追加ヘッダ。
 */
export function requestServiceDirectly(
  service: DomainService,
  path: string,
  options: {
    token?: string;
    rawAuthorization?: string;
    method?: string;
    /** 追加のリクエストヘッダー。gateway が付けるヘッダーの偽装(#943 / AT-17)に使う。 */
    headers?: Record<string, string>;
  } = {}
): DirectResponse {
  const url = `http://${service}:8080${path}`;
  const args = [
    'exec', BASTION_CONTAINER, 'curl',
    '--silent', '--show-error', '--max-time', '15',
    // ステータスと本文を分けて取り出せるよう、本文の後ろにステータスを付ける。
    '--write-out', '\n%{http_code}',
    '--request', options.method ?? 'GET',
  ];
  const authorization = options.rawAuthorization
    ?? (options.token ? `Bearer ${options.token}` : undefined);
  if (authorization) {
    args.push('--header', `Authorization: ${authorization}`);
  }
  for (const [name, value] of Object.entries(options.headers ?? {})) {
    args.push('--header', `${name}: ${value}`);
  }
  args.push(url);

  const output = execFileSync('docker', args, { encoding: 'utf8', timeout: 30_000 });
  const separator = output.lastIndexOf('\n');
  return {
    status: Number(output.slice(separator + 1).trim()),
    body: output.slice(0, separator),
  };
}

export interface DirectBodyResponse {
  status: number;
  /** 応答の Content-Type。中身が画像かエラーJSONかを取り違えないために見る。 */
  contentType: string;
  /** 応答本文。PNG のようなバイナリを壊さずに扱えるよう Buffer で返す。 */
  body: Buffer;
}

/**
 * サービスへ**JSONの本文を伴う**リクエストを直接送り、応答をバイト列で受け取る
 * (issue #937 / AT-11)。
 *
 * ## {@link requestServiceDirectly} と分けてある理由
 *
 * 踏み台と経路は同じだが、用途と必要な入出力が違う。
 *
 * - **なぜ gateway を通さないのか**が違う。{@link requestServiceDirectly} は
 *   「gateway を迂回しても各サービスが自分で認証を要求する」ことを確かめるために
 *   **わざと**迂回する(ADR-0008)。こちらは `/api/render/**` が
 *   **gateway のルート表にそもそも載っていない**(issue #830。
 *   `services/gateway/src/main/resources/application.yml` の「`/api/render/**` は載せない」)
 *   ため、lbs-net の中からしか到達できないという事実に従っているだけである。
 *   認可は依頼する側が済ませている前提の、サービス間専用のエンドポイントである。
 * - **本文を送れる必要がある。** レンダリング要求は JSON の本文がすべてで、
 *   {@link requestServiceDirectly} は本文を送れない。
 * - **応答をバイト列で受け取る必要がある。** `POST /api/render/plantuml` は PNG を返す。
 *   文字列として読むと PNG のシグネチャもメタデータチャンクも壊れ、
 *   「返ってきた画像の中身」を検証できない(#937 の受け入れ基準がそれを要求している)。
 *
 * ## 呼び出し方は {@link requestServiceDirectly} と同じにしてある
 *
 * `docker exec ... curl ...` を**引数の配列**で起動する。コンテナの中でシェルを
 * 起動しないので、引用符やエスケープの穴が生まれない。ステータスの取り出し方も
 * `--write-out` を本文の後ろに付ける同じやり方で、Content-Type だけを足してある。
 * 本文を Buffer のまま読む(PNG が返るため文字列にすると壊れる)ので、区切りは
 * 最後の改行バイトで探す。
 *
 * 本文は標準入力から curl へ渡す(`--data-binary @-` と `spawnSync` の `input`)。
 * 巨大な入力に対するふるまいを確かめるシナリオが数百KBのソースを送るため、
 * コマンドラインに載せると引数長の上限に触れる。
 *
 * @param service 対象サービス(compose のサービス名 = lbs-net 上のホスト名)
 * @param path    `/api/...` 形式のパス
 * @param json    リクエスト本文。`JSON.stringify` して送る
 * @param options `token` を渡すと `Authorization: Bearer` を付ける。
 *                media-service は `/api/render/**` にも認証を要求する(SecurityConfig)。
 */
export function postJsonToServiceDirectly(
  service: DomainService,
  path: string,
  json: unknown,
  options: { token?: string; timeoutSeconds?: number } = {}
): DirectBodyResponse {
  const timeoutSeconds = options.timeoutSeconds ?? 120;
  const args = [
    'exec', '-i', BASTION_CONTAINER, 'curl',
    '--silent', '--show-error', '--max-time', String(timeoutSeconds),
    // 本文の後ろにステータスと Content-Type を付ける({@link requestServiceDirectly} と同じ)。
    '--write-out', '\n%{http_code} %{content_type}',
    '--request', 'POST',
    '--header', 'Content-Type: application/json',
  ];
  if (options.token) {
    args.push('--header', `Authorization: Bearer ${options.token}`);
  }
  args.push('--data-binary', '@-', `http://${service}:8080${path}`);

  // encoding を渡さないので stdout/stderr は Buffer のまま返る。PNG を壊さずに読める。
  const result = spawnSync('docker', args, {
    input: JSON.stringify(json),
    maxBuffer: 64 * 1024 * 1024,
    timeout: (timeoutSeconds + 30) * 1000,
  });
  if (result.error) {
    throw result.error;
  }
  const stdout = result.stdout ?? Buffer.alloc(0);
  // `--write-out` は必ず末尾に付き、その中に改行は無い。最後の改行が本文との区切りである。
  const separator = stdout.lastIndexOf(0x0a);
  if (separator < 0) {
    throw new Error(
      `${service} への直接リクエストの結果を解釈できませんでした`
      + ` (POST ${path}): ${(result.stderr ?? Buffer.alloc(0)).toString('utf8')}`
    );
  }
  const [status, contentType = ''] = stdout
    .subarray(separator + 1)
    .toString('utf8')
    .trim()
    .split(' ');
  return {
    status: Number(status),
    contentType,
    body: stdout.subarray(0, separator),
  };
}

/**
 * 各サービスで「認証されていれば必ず到達できる」パス。
 *
 * 認証ゲートの検証に使うので、**認証さえ通れば200/403/404のいずれかになり、
 * 401にはならない**ものを選ぶ。逆にJWTが無ければ401になることが期待値である。
 * 公開パス(actuator や初回セットアップ導線)を選ぶとゲートを素通りしてしまうため使わない。
 */
export const AUTH_GATED_PATHS: Record<DomainService, string> = {
  identity: '/api/identity/me',
  project: '/api/projects',
  content: '/api/posts',
  media: '/api/generated-images',
  ai: '/api/generation-jobs',
  publishing: '/api/posts',
  analytics: '/api/projects/1/dashboard/google-analytics',
  platform: '/api/dashboard/service-status',
  'log-writer': '/api/audit-logs',
};
