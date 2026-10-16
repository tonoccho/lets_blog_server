/**
 * @jest-environment node
 */
// 記事計画の Server Action。自前の @/lib/session・@/lib/apiClient は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installPlanBackend, type PlanBackend } from "./planBackend";
import {
  acceptPlan,
  acceptPlanStructure,
  loadIssueDescription,
  loadPlanSession,
  loadPlanSessionByIssue,
  loadPlanSessions,
  loadRepositoryIssues,
  sendPlanChatMessage,
  suggestPlanStructure,
  suggestPlanTitles,
} from "../actions";

let backend: PlanBackend;
beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  backend = installPlanBackend();
});

const HISTORY = [{ role: "user" as const, content: "テーマ" }];
const DOWN = () => textResponse("down", 503);

describe("sendPlanChatMessage", () => {
  it("履歴・メッセージ・セッションID・Issue番号を送り、返答とセッションIDを返す", async () => {
    await expect(sendPlanChatMessage(7, HISTORY, "こんにちは", 5, 3)).resolves.toEqual({
      ok: true,
      data: { reply: "返答", sessionId: 11 },
    });
    expect(backend.bodies.chat).toEqual([{ history: HISTORY, message: "こんにちは", sessionId: 5, githubIssueNumber: 3 }]);
  });

  it("失敗したら理由を error として返す", async () => {
    backend.chat = DOWN;
    await expect(sendPlanChatMessage(7, [], "x", null, null)).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });

  it("Error でない値で失敗しても文字列にして返す", async () => {
    global.fetch = jest.fn().mockRejectedValue("offline") as unknown as typeof fetch;
    await expect(sendPlanChatMessage(7, [], "x", null, null)).resolves.toEqual({ ok: false, error: "offline" });
  });

  it("管理者でなければ / へ戻し、バックエンドへ問い合わせない", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(sendPlanChatMessage(7, [], "x", null, null)).rejects.toThrow("NEXT_REDIRECT:/");
    expect(backend.requests).toEqual([]);
  });
});

describe("loadPlanSessions", () => {
  it("セッションの一覧を返す", async () => {
    backend.sessions = () => jsonResponse([{ id: 1, title: "t", githubIssueNumber: null, createdAt: "", updatedAt: "" }]);
    const result = await loadPlanSessions(7);
    expect(result).toMatchObject({ ok: true, data: { sessions: [{ id: 1 }] } });
  });

  it("失敗したら理由を返す", async () => {
    backend.sessions = DOWN;
    await expect(loadPlanSessions(7)).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });
});

describe("loadPlanSession", () => {
  it("セッションの詳細を返す", async () => {
    await expect(loadPlanSession(7, 1)).resolves.toMatchObject({ ok: true, data: { session: { id: 1 } } });
    expect(backend.requests).toEqual(["GET session/1"]);
  });

  it("失敗したら理由を返す", async () => {
    backend.session = DOWN;
    await expect(loadPlanSession(7, 1)).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });
});

describe("loadPlanSessionByIssue", () => {
  it("Issue に紐付くセッションを返す", async () => {
    backend.sessionByIssue = () => jsonResponse({ id: 4, title: "t", githubIssueNumber: 3, history: [], createdAt: "", updatedAt: "" });
    await expect(loadPlanSessionByIssue(7, 3)).resolves.toMatchObject({ ok: true, data: { session: { id: 4 } } });
  });

  it("取得に失敗しても(セッションが無いのと同じ扱いで)成功としてセッションなしを返す", async () => {
    backend.sessionByIssue = () => textResponse("not found", 404);
    await expect(loadPlanSessionByIssue(7, 3)).resolves.toEqual({ ok: true, data: { session: null } });
  });
});

describe("loadIssueDescription", () => {
  it("Issue の本文を返す", async () => {
    backend.issueDescription = () => jsonResponse({ body: "構成" });
    await expect(loadIssueDescription(7, 3)).resolves.toEqual({ ok: true, data: { body: "構成" } });
  });

  it("失敗したら理由を返す", async () => {
    backend.issueDescription = DOWN;
    await expect(loadIssueDescription(7, 3)).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });
});

describe("loadRepositoryIssues", () => {
  it("指定した状態の Issue 一覧を返す", async () => {
    backend.issues = () => jsonResponse([{ number: 1, title: "t", htmlUrl: "u", state: "open" }]);
    await expect(loadRepositoryIssues(7, "closed")).resolves.toMatchObject({ ok: true, data: { issues: [{ number: 1 }] } });
    expect(backend.requests).toEqual(["GET issues?state=closed"]);
  });

  it("失敗したら理由を返す", async () => {
    backend.issues = DOWN;
    await expect(loadRepositoryIssues(7, "open")).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });
});

describe("suggestPlanTitles / acceptPlan", () => {
  it("タイトル案を返す。失敗したら理由を返す", async () => {
    await expect(suggestPlanTitles(7, HISTORY)).resolves.toEqual({ ok: true, data: { titles: ["案A", "案B"] } });
    expect(backend.bodies.suggestTitles).toEqual([{ history: HISTORY }]);
    backend.suggestTitles = DOWN;
    await expect(suggestPlanTitles(7, HISTORY)).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });

  it("選んだタイトルで計画を受け入れ、結果を返す。失敗したら理由を返す", async () => {
    backend.accept = () => jsonResponse({ results: [{ title: "案A", issueNumber: 3, issueUrl: "u" }] });
    await expect(acceptPlan(7, ["案A"])).resolves.toEqual({ ok: true, data: { results: [{ title: "案A", issueNumber: 3, issueUrl: "u" }] } });
    expect(backend.bodies.accept).toEqual([{ titles: ["案A"] }]);
    backend.accept = DOWN;
    await expect(acceptPlan(7, ["案A"])).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });
});

describe("suggestPlanStructure / acceptPlanStructure", () => {
  it("構成案を返す。失敗したら理由を返す", async () => {
    await expect(suggestPlanStructure(7, HISTORY)).resolves.toEqual({ ok: true, data: { structure: "# 構成" } });
    backend.suggestStructure = DOWN;
    await expect(suggestPlanStructure(7, HISTORY)).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });

  it("構成案で Issue を更新し、Issue 番号と URL を返す。失敗したら理由を返す", async () => {
    await expect(acceptPlanStructure(7, 3, "# 構成")).resolves.toEqual({
      ok: true,
      data: { issueNumber: 3, issueUrl: "https://github.com/o/r/issues/3" },
    });
    expect(backend.bodies.acceptStructure).toEqual([{ structure: "# 構成" }]);
    backend.acceptStructure = DOWN;
    await expect(acceptPlanStructure(7, 3, "x")).resolves.toEqual({ ok: false, error: "APIエラー (503): down" });
  });
});

/** どの Action も、Error でない値で失敗した場合は、文字列にして error として返す。 */
describe("Error でない値で失敗したとき", () => {
  beforeEach(() => {
    global.fetch = jest.fn().mockRejectedValue("offline") as unknown as typeof fetch;
  });
  const failed = { ok: false, error: "offline" };

  it.each([
    ["loadPlanSessions", () => loadPlanSessions(7)],
    ["loadPlanSession", () => loadPlanSession(7, 1)],
    ["loadIssueDescription", () => loadIssueDescription(7, 3)],
    ["loadRepositoryIssues", () => loadRepositoryIssues(7, "open")],
    ["suggestPlanTitles", () => suggestPlanTitles(7, HISTORY)],
    ["acceptPlan", () => acceptPlan(7, ["案A"])],
    ["suggestPlanStructure", () => suggestPlanStructure(7, HISTORY)],
    ["acceptPlanStructure", () => acceptPlanStructure(7, 3, "x")],
  ])("%s は理由の文字列を返す", async (_name, call) => {
    await expect(call()).resolves.toEqual(failed);
  });
});
