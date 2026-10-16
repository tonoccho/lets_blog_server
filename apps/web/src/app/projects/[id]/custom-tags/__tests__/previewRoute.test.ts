/**
 * @jest-environment node
 */
// カスタムタグのプレビュー Route Handler。自前の @/lib/session・@/lib/apiClient は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { POST } from "../preview/route";

let previewBodies: unknown[];
let preview: () => Response;

beforeEach(() => {
  previewBodies = [];
  resetServerRuntime(ADMIN_SESSION);
  preview = () => jsonResponse({ html: "<b>x</b>", css: ".b{}" });
  installFetch({
    "POST /api/projects/:id/custom-tags/preview": (_u, init) => {
      previewBodies.push(JSON.parse(String(init.body)));
      return preview();
    },
  });
});

const call = (body: string) =>
  POST(new Request("http://localhost/projects/7/custom-tags/preview", { method: "POST", body }), {
    params: Promise.resolve({ id: "7" }),
  });

describe("POST /projects/[id]/custom-tags/preview", () => {
  it("未ログインなら 401 を返し、バックエンドへ問い合わせない", async () => {
    resetServerRuntime(null);
    const res = await call(JSON.stringify({ htmlTemplate: "a", testContent: "b" }));
    expect(res.status).toBe(401);
    expect(previewBodies).toEqual([]);
  });

  it.each([
    ["JSON でない本文", "not json"],
    ["null", "null"],
    ["htmlTemplate が文字列でない", JSON.stringify({ htmlTemplate: 1, testContent: "b" })],
    ["testContent が文字列でない", JSON.stringify({ htmlTemplate: "a" })],
  ])("%s は 400 を返す", async (_name, body) => {
    const res = await call(body);
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "リクエストの形式が不正です。" });
    expect(previewBodies).toEqual([]);
  });

  it("レンダリング結果をそのまま返す", async () => {
    const res = await call(JSON.stringify({ htmlTemplate: "<b>{{content}}</b>", cssContent: ".b{}", testContent: "x" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ html: "<b>x</b>", css: ".b{}" });
    expect(previewBodies).toEqual([{ htmlTemplate: "<b>{{content}}</b>", cssContent: ".b{}", testContent: "x" }]);
  });

  it("バックエンドが失敗したら 502 とそのメッセージを返す", async () => {
    preview = () => textResponse("render failed", 500);
    const res = await call(JSON.stringify({ htmlTemplate: "a", testContent: "b" }));
    expect(res.status).toBe(502);
    expect((await res.json()).error).toContain("render failed");
  });

  it("Error でない値で失敗しても 502 を返す", async () => {
    global.fetch = jest.fn().mockRejectedValue("network down") as unknown as typeof fetch;
    const res = await call(JSON.stringify({ htmlTemplate: "a", testContent: "b" }));
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: "network down" });
  });
});
