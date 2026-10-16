/**
 * @jest-environment node
 */
// 自前の @/lib/session・@/lib/apiClient は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);

import { GET } from "../route";
import { installFetch, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

let requested: string[];
let bundle: () => Response;

beforeEach(() => {
  requested = [];
  resetServerRuntime(ADMIN_SESSION);
  bundle = () =>
    ({
      ok: true,
      status: 200,
      statusText: "OK",
      text: async () => "body{}",
      arrayBuffer: async () => new TextEncoder().encode("body{}").buffer,
      headers: { get: () => "text/css" },
    }) as unknown as Response;
  installFetch({
    "GET /api/projects/:id/custom-tags/css-bundle": (_u, _i, [id]) => {
      requested.push(id);
      return bundle();
    },
  });
});

function callGet(id: string): ReturnType<typeof GET> {
  return GET(new Request(`http://localhost/projects/${id}/custom-tags/css-bundle`), {
    params: Promise.resolve({ id }),
  });
}

/** issue #1263: ログイン必須のCSSバンドル配信はCache-Controlを明示し、ブラウザに保存させない。 */
describe("GET /projects/[id]/custom-tags/css-bundle", () => {
  it("ログイン済みなら200でCSSを返し、Cache-Controlはno-store", async () => {
    const res = await callGet("7");

    expect(res.status).toBe(200);
    expect(requested).toEqual(["7"]);
    expect(await res.text()).toBe("body{}");
    expect(res.headers.get("Content-Type")).toBe("text/css");
    expect(res.headers.get("Content-Disposition")).toContain("integrated-css.css");
    expect(res.headers.get("Cache-Control") ?? "").toContain("no-store");
  });

  it("未ログインなら401を返し、取得しない", async () => {
    resetServerRuntime(null);
    const res = await callGet("7");
    expect(res.status).toBe(401);
    expect(requested).toEqual([]);
  });

  it("取得に失敗したら502を返す", async () => {
    bundle = () => textResponse("boom", 500);
    const res = await callGet("7");
    expect(res.status).toBe(502);
    expect((await res.json()).error).toContain("boom");
  });

  it("Error以外がthrowされても502を返す", async () => {
    global.fetch = jest.fn().mockRejectedValue("x") as unknown as typeof fetch;
    const res = await callGet("7");
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: "x" });
  });
});
