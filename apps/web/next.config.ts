import type { NextConfig } from "next";

/**
 * ブラウザ向け応答に付ける基本のセキュリティヘッダ(issue #984)。
 *
 * ## なぜ reverse-proxy(nginx)ではなくここなのか
 *
 * infra/nginx/conf.d/default.conf の443サーバーブロックに `add_header ... always` を
 * 置けば全経路に一括で付けられるが、この構成では副作用が大きい。
 *   - nginx の `add_header` は「上流が同じヘッダを返していても追加する」ため、
 *     自前でヘッダを出す Keycloak / WordPress / phpMyAdmin / draw.io では
 *     同名ヘッダが重複する。X-Frame-Options は値が食い違うと解釈がブラウザ依存になる。
 *   - `/drawio/` は VSCode拡張の webview(lbs-net の外、vscode-webview: オリジン)が
 *     iframe で読む(#979)。全経路に X-Frame-Options を付けるとこの埋め込みが壊れる。
 *   - `location` に `add_header` を1つでも書くと server ブロックの `add_header` の継承が
 *     切れるため、経路ごとの例外を作った時点で穴が無言で開く。
 * 対象は管理画面(このNext.jsアプリ)であり、Next.js が推奨する `headers()` で
 * アプリの応答にだけ付けるのが、影響範囲と一致していて安全である。
 * 中継先のツール群(Keycloak等)は各々が自分のヘッダ方針を持つ。
 *
 * ## 値の選択
 *
 * - `X-Frame-Options: SAMEORIGIN` — 管理画面を外部サイトへ埋め込ませない。
 *   同一オリジンの iframe(記事プレビュー等)は従来どおり動く。
 * - `Strict-Transport-Security: max-age=300` — この環境の証明書は自己署名
 *   (scripts/generate-certs.sh、README.md参照)。長い max-age を焼き付けると
 *   証明書を作り直した後にブラウザが警告を回避できず開発者が締め出されるため、
 *   意図的に短くしてある(HTTPS強制自体は nginx の 80→443 リダイレクトが担う)。
 * - `Content-Security-Policy` は入れない。管理画面は draw.io / Penpot / PlantUML を
 *   iframe と画像で埋め込み、開発サーバー(next dev)は eval とインラインを使うため、
 *   実効性のある CSP を今この Issue の範囲で書き切れない。理由は SECURITY.md にも残す。
 */
const SECURITY_HEADERS = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "SAMEORIGIN" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Strict-Transport-Security", value: "max-age=300" },
];

/**
 * リバースプロキシ(nginx)に、この管理画面の応答をバッファさせない(issue #1475)。
 *
 * `loading.tsx` は先に骨組みを返し、データ取得の完了後に残りをストリーミングで続ける。
 * ところが nginx は既定で `proxy_buffering on` のため、上流の応答を溜めてからまとめて
 * 返し、利用者には loading UI が届かない。`infra/nginx/conf.d/default.conf` の `location /`
 * は既定のまま(SSE 用の `/api/dashboard/` 以外は `proxy_buffering off` を持たない)。
 * `X-Accel-Buffering: no` は nginx が応答ごとに読み取り、その応答だけバッファを切る
 * (Next.js のセルフホスト手順が示す方法)。`location /` 全体を `proxy_buffering off`
 * にすると、巨大な静的ファイルで遅いクライアントが上流を占有し続けるため採らない。
 * nginx はこのヘッダをクライアントへは返さない(内部向け)。
 */
const STREAMING_HEADERS = [{ key: "X-Accel-Buffering", value: "no" }];

const nextConfig: NextConfig = {
  // `headers()` は proxy.ts(middleware)より先に評価されるため、認証ゲートが返す
  // /login へのリダイレクト応答にもこのヘッダが付く。
  headers() {
    return Promise.resolve([
      { source: "/:path*", headers: SECURITY_HEADERS },
      { source: "/:path*", headers: STREAMING_HEADERS },
    ]);
  },
  experimental: {
    serverActions: {
      bodySizeLimit: "500mb",
    },
    // proxy.ts(middleware)を経由するリクエストのボディサイズ上限。
    // デフォルト10MBのため、zipアップロードがここで切り詰められて
    // Server Action側で "Unexpected end of form" になっていた。
    proxyClientMaxBodySize: "500mb",
  },
};

export default nextConfig;
