import { getServerUrl } from './config';

/**
 * 拡張からバックエンドAPI(`/api/**`)を呼ぶ際のベースURLを組み立てる唯一の場所(issue #585)。
 *
 * 呼び先は常に gateway(services/gateway)であり、個々のドメインサービスへ直接向けることはしない。
 * gateway がルーティング・JWT検証・レート制限・相関ID付与を一手に引き受けるため、拡張側は
 * サービス分割の進行(Epic #551)に関わらず gateway だけを見ていればよい。ルーティング表は
 * services/gateway/src/main/resources/application.yml を参照(未移行パスは同ファイルの
 * fallback-uri で legacy-api へ到達する)。Web(BFF)側の対応する実装は web/src/lib/apiBaseUrl.ts。
 *
 * <b>なぜ `letsBlog.serverUrl` をそのまま使うのか</b>: gateway コンテナはホストへポートを公開して
 * おらず(docker-compose.yml の gateway サービスに ports 指定は無い)、拡張が動くVSCodeは
 * lbs-net の外にいる。到達経路は常にリバースプロキシ(nginx)であり、
 * nginx/conf.d/default.conf の `location /api/` が `gateway:8080` へ中継する。つまり
 * 「gatewayのベースURL」は拡張から見ればリバースプロキシの公開URLそのものになる。
 * gateway用に別設定を増やしても標準構成では指す先が存在しないため、設定は増やさず
 * 「`/api/**` の組み立てはこの関数だけを通る」という一点に集約する。
 *
 * `getServerUrl()`(config.ts)はリバースプロキシの公開URLという意味のままで、gateway 以外の
 * 中継先—Keycloak(`/auth/realms/...`、deviceAuth.ts)と draw.io(`/drawio/`、
 * diagramEditorPanel.ts)—が引き続き使う。apiClient.ts からは参照しない。
 */
export function gatewayBaseUrl(): string {
  return getServerUrl();
}

/** `/api/...` から始まるパスを gateway 宛の絶対URLへ変換する。 */
export function gatewayUrl(path: string): string {
  return `${gatewayBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`;
}
