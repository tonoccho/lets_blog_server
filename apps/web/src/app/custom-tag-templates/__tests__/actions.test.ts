/**
 * @jest-environment node
 */

/**
 * カスタムタグテンプレートの Server Action。
 * 自前の @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤
 * (next/cache・next/headers・next/server の after・next-auth・next/navigation の redirect)と、
 * 外部との通信(fetch)だけ(#1834)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
const revalidatePath = jest.fn();
jest.mock("next/cache", () => ({ revalidatePath: (...a: unknown[]) => revalidatePath(...a) }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import {
  applyCustomTagTemplateAction,
  cloneCustomTagTemplateAction,
  createCustomTagTemplateAction,
  deleteCustomTagTemplateAction,
  publishCustomTagTemplateAction,
  unpublishCustomTagTemplateAction,
  updateCustomTagTemplateAction,
} from "../actions";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

let fetchMock: jest.Mock;
const backendCalls = () =>
  fetchMock.mock.calls
    .filter(([url]) => !String(url).endsWith("/api/operation-logs"))
    .map(([url, init]) => ({
      method: String(init?.method ?? "GET"),
      path: String(url).replace(/^https?:\/\/[^/]+/, ""),
      body: init?.body ? JSON.parse(String(init.body)) : null,
    }));

const TEMPLATE_INPUT = { templateName: "t", htmlTemplate: "<p/>" };

/** 7 つのアクションを、呼び出し・成功時の再検証先・バックエンドへの要求の形で並べる。 */
const CASES: {
  name: string;
  call: () => Promise<{ data?: unknown; error?: string; success?: boolean }>;
  route: string;
  expected: { method: string; path: string; body: unknown };
  revalidated: string;
}[] = [
  {
    name: "createCustomTagTemplateAction",
    call: () => createCustomTagTemplateAction(TEMPLATE_INPUT),
    route: "POST /api/custom-tag-templates",
    expected: { method: "POST", path: "/api/custom-tag-templates", body: TEMPLATE_INPUT },
    revalidated: "/custom-tag-templates",
  },
  {
    name: "updateCustomTagTemplateAction",
    call: () => updateCustomTagTemplateAction(5, TEMPLATE_INPUT),
    route: "PUT /api/custom-tag-templates/:id",
    expected: { method: "PUT", path: "/api/custom-tag-templates/5", body: TEMPLATE_INPUT },
    revalidated: "/custom-tag-templates",
  },
  {
    name: "publishCustomTagTemplateAction",
    call: () => publishCustomTagTemplateAction(5),
    route: "POST /api/custom-tag-templates/:id/publish",
    expected: { method: "POST", path: "/api/custom-tag-templates/5/publish", body: null },
    revalidated: "/custom-tag-templates",
  },
  {
    name: "unpublishCustomTagTemplateAction",
    call: () => unpublishCustomTagTemplateAction(5),
    route: "POST /api/custom-tag-templates/:id/unpublish",
    expected: { method: "POST", path: "/api/custom-tag-templates/5/unpublish", body: null },
    revalidated: "/custom-tag-templates",
  },
  {
    name: "cloneCustomTagTemplateAction",
    call: () => cloneCustomTagTemplateAction(5, { newTemplateName: "copy" }),
    route: "POST /api/custom-tag-templates/:id/clone",
    expected: { method: "POST", path: "/api/custom-tag-templates/5/clone", body: { newTemplateName: "copy" } },
    revalidated: "/custom-tag-templates",
  },
  {
    name: "applyCustomTagTemplateAction",
    call: () => applyCustomTagTemplateAction(5, { projectId: 3, tagName: "note" }),
    route: "POST /api/custom-tag-templates/:id/apply",
    expected: { method: "POST", path: "/api/custom-tag-templates/5/apply", body: { projectId: 3, tagName: "note" } },
    revalidated: "/projects/3/custom-tags",
  },
  {
    name: "deleteCustomTagTemplateAction",
    call: () => deleteCustomTagTemplateAction(5),
    route: "DELETE /api/custom-tag-templates/:id",
    expected: { method: "DELETE", path: "/api/custom-tag-templates/5", body: null },
    revalidated: "/custom-tag-templates",
  },
];

describe.each(CASES)("$name", ({ call, route, expected, revalidated, name }) => {
  const succeeds = name === "deleteCustomTagTemplateAction" ? { success: true } : { data: { id: 5 } };

  beforeEach(() => {
    revalidatePath.mockReset();
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [route]: () => jsonResponse({ id: 5 }) });
  });

  it("管理者セッションを確認し、バックエンドを呼んで、一覧(または対象プロジェクトのタグ画面)を再検証する", async () => {
    await expect(call()).resolves.toEqual(succeeds);

    expect(backendCalls()).toEqual([expected]);
    expect(revalidatePath).toHaveBeenCalledWith(revalidated);
  });

  it("Error が投げられたらそのメッセージを error に入れる(409 の理由など)", async () => {
    fetchMock = installFetch({ [route]: () => textResponse("タグ名 'note' は既に登録されています", 409) });

    await expect(call()).resolves.toEqual({ error: "APIエラー (409): タグ名 'note' は既に登録されています" });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it("Error 以外が投げられても文字列にして error に入れる", async () => {
    fetchMock = installFetch({
      [route]: () => {
        throw "boom";
      },
    });

    await expect(call()).resolves.toEqual({ error: "boom" });
  });

  it("管理者でなければバックエンドを呼ばず、ログイン後の遷移を促す", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(call()).rejects.toThrow("NEXT_REDIRECT:/");
    expect(backendCalls()).toEqual([]);
  });
});
