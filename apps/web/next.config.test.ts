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
    expect(rules).toHaveLength(1);
    expect(rules[0].source).toBe("/:path*");
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
