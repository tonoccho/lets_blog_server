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
import { execFileSync } from 'node:child_process';

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
 */
export function requestServiceDirectly(
  service: DomainService,
  path: string,
  options: { token?: string; rawAuthorization?: string; method?: string } = {}
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
  args.push(url);

  const output = execFileSync('docker', args, { encoding: 'utf8', timeout: 30_000 });
  const separator = output.lastIndexOf('\n');
  return {
    status: Number(output.slice(separator + 1).trim()),
    body: output.slice(0, separator),
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
