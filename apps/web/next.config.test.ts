import nextConfig from "./next.config";

/**
 * 管理画面が返すセキュリティヘッダの設定(issue #984)。
 *
 * 利用者から見たふるまい(実際の応答にヘッダが付くこと)は受け入れテスト
 * `e2e/features/cross-cutting/security-headers.feature` が担保する。ここで固定するのは
 * その設定の形である。`source` が全経路(`/:path*`)を覆っていないと、ページを1本足した
 * 時点でヘッダが無言で外れる。
 */
describe("next.config の headers()", () => {
  async function headerRules() {
    const headers = nextConfig.headers;
    if (!headers) {
      throw new Error("next.config に headers() が定義されていない");
    }
    return await headers();
  }

  it("全経路にヘッダを適用する", async () => {
    const rules = await headerRules();
    // ヘッダの種類ごとに規則を分けてよい(#1475でストリーミング用を追加)。どの規則も全経路を覆う。
    expect(rules.length).toBeGreaterThanOrEqual(1);
    expect(rules.map((rule) => rule.source)).toEqual(rules.map(() => "/:path*"));
  });

  it("nginx がストリーミング応答をバッファしないようにするヘッダを付ける(issue #1475)", async () => {
    // loading.tsx が先に描画を返しても、リバースプロキシが応答を溜め込むと利用者へ届かない。
    // nginx は既定で proxy_buffering on のため、応答ごとに X-Accel-Buffering: no で切る
    // (Next.js のセルフホスト手順が示す方法)。
    const rules = await headerRules();
    const applied = rules.flatMap((rule) => rule.headers.map((h) => [h.key, h.value]));
    expect(applied).toContainEqual(["X-Accel-Buffering", "no"]);
  });

  it("基本のセキュリティヘッダを付ける", async () => {
    const rules = await headerRules();
    const applied = Object.fromEntries(rules[0].headers.map((h) => [h.key, h.value]));
    expect(applied).toEqual({
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "SAMEORIGIN",
      "Referrer-Policy": "strict-origin-when-cross-origin",
      // 自己署名証明書での締め出しを避けるため意図的に短い(SECURITY.md 参照)。
      "Strict-Transport-Security": "max-age=300",
    });
  });
});
