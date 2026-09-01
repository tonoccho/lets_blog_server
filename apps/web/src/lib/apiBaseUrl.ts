/**
 * Web(BFF)からバックエンドを呼ぶ際のベースURLを組み立てる唯一の場所(issue #584)。
 *
 * 呼び先は常に gateway(services/gateway)であり、個々のドメインサービスへ直接向けることはしない。
 * gateway がルーティング・JWT検証・レート制限・相関ID付与を一手に引き受けるため、クライアント側は
 * サービス分割の進行(Epic #551)に関わらず gateway だけを見ていればよい。
 * ルーティング表は services/gateway/src/main/resources/application.yml を参照
 * (未移行パスは同ファイルの fallback-uri で legacy-api へ到達する)。
 *
 * `LETS_BLOG_GATEWAY_URL` は必ずサーバーサイドでのみ参照される(NEXT_PUBLIC_ 接頭辞を付けないため
 * クライアントバンドルには載らない)。ブラウザからのAPI呼び出しはルート相対パス(`/api/...`)で
 * reverse-proxy(nginx)へ送られ、nginx の `location /api/` が同じ gateway へ中継する。
 *
 * 値の例:
 * - コンテナ実行時(docker-compose.yml の web サービス): `http://gateway:8080`
 *   (lbs-net 内部の平文HTTP。自己署名証明書を経由しないため NODE_EXTRA_CA_CERTS 不要)
 * - ホスト上で `npm run dev` する場合(apps/web/.env.local): `https://localhost`
 *   (nginx 経由。NODE_EXTRA_CA_CERTS が必要。docs/setup.md 参照)
 */
const DEFAULT_GATEWAY_URL = 'https://localhost';

/** 末尾スラッシュを除去した gateway のベースURL。 */
export function gatewayBaseUrl(): string {
  return (process.env.LETS_BLOG_GATEWAY_URL ?? DEFAULT_GATEWAY_URL).replace(/\/+$/, '');
}

/** `/api/...` から始まるパスを gateway 宛の絶対URLへ変換する。 */
export function gatewayUrl(path: string): string {
  return `${gatewayBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`;
}
