import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { UnifiedLogRow } from "../UnifiedLogRow";
import type { UnifiedLogEntry } from "@/lib/apiClient";
import { installFetch, jsonResponse, resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

// 自前の ../actions・@/lib/apiClient・@/lib/session は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
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

function entry(overrides: Partial<UnifiedLogEntry> = {}): UnifiedLogEntry {
  return {
    sourceType: "OPERATION",
    id: 1,
    createdAt: "2026-09-08T00:00:00Z",
    title: "GET /api/projects",
    detail: null,
    status: null,
    operationId: null,
    actorKeycloakSub: null,
    ...overrides,
  };
}

describe("UnifiedLogRow", () => {
  it("最小限のエントリ(detail・status・actorKeycloakSub・operationIdなし)を表示する", () => {
    render(<UnifiedLogRow entry={entry()} timezone={null} />);

    expect(screen.getByText("GET /api/projects")).toBeInTheDocument();
    expect(screen.getByText("操作")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "コピー" })).not.toBeInTheDocument();
  });

  it("detailがあれば表示する", () => {
    render(<UnifiedLogRow entry={entry({ detail: "詳細情報" })} timezone={null} />);
    expect(screen.getByText("詳細情報")).toBeInTheDocument();
  });

  it("actorKeycloakSubがあればバッジとして表示する", () => {
    render(<UnifiedLogRow entry={entry({ actorKeycloakSub: "sub-123" })} timezone={null} />);
    expect(screen.getByText(/Keycloak: sub-123/)).toBeInTheDocument();
  });

  it.each([
    ["SUCCESS", "SUCCESS"],
    ["done", "done"],
    ["FAILED", "FAILED"],
    ["failed", "failed"],
    ["PENDING", "PENDING"],
  ])("status=%sを表示する", (status, expected) => {
    render(<UnifiedLogRow entry={entry({ status })} timezone={null} />);
    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  it.each([
    ["AI_JOB", "AI"],
    ["SYSTEM_JOB", "システム"],
    ["AUDIT", "監査"],
  ] as const)("sourceType=%sのラベルを表示する", (sourceType, label) => {
    render(<UnifiedLogRow entry={entry({ sourceType })} timezone={null} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it("OPERATIONかつoperationIdがあればコピーボタンを表示し、クリックでトレース全体をクリップボードへコピーする", async () => {
    resetServerRuntime(ADMIN_SESSION);
    installFetch({
      "GET /api/operation-logs/op-1": () =>
        jsonResponse([
          {
            id: 1,
            operationId: "op-1",
            userId: 1,
            actorKeycloakSub: null,
            method: "GET",
            path: "/api/projects",
            statusCode: 200,
            durationMs: 10,
            success: true,
            errorMessage: null,
            createdAt: "2026-09-08T00:00:00",
          },
        ]),
    });
    // jsdom にはクリップボードが無い。ブラウザの機能を記録用に置く。
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } });
    render(<UnifiedLogRow entry={entry({ operationId: "op-1" })} timezone={null} />);

    const button = screen.getByRole("button", { name: "コピー" });
    fireEvent.click(button);

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
        [
          "操作ID: op-1",
          "開始日時: 2026-09-08T00:00:00Z",
          "呼び出し件数: 1",
          "結果: 成功",
          "",
          "呼び出し一覧:",
          "1. [2026-09-08T00:00:00Z] GET /api/projects",
          "   ステータス: 200 / 10ms / 成功",
        ].join("\n")
      );
    });
    expect(await screen.findByRole("button", { name: "コピーしました" })).toBeInTheDocument();
  });

  it("AI_JOBかつoperationIdがあってもコピーボタンは表示しない(OPERATION限定)", () => {
    render(<UnifiedLogRow entry={entry({ sourceType: "AI_JOB", operationId: "op-1" })} timezone={null} />);
    expect(screen.queryByRole("button", { name: "コピー" })).not.toBeInTheDocument();
  });

  describe("日時の表示タイムゾーン(issue #1260)", () => {
    afterEach(() => jest.restoreAllMocks());

    function mockBrowserTimeZone(timeZone: string) {
      jest
        .spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions")
        .mockReturnValue({ timeZone } as Intl.ResolvedDateTimeFormatOptions);
    }

    it("個人設定TZがあればブラウザTZに関係なくそれで表示する", () => {
      mockBrowserTimeZone("Pacific/Auckland");
      render(<UnifiedLogRow entry={entry({ createdAt: "2026-09-11T09:10:35" })} timezone="Asia/Tokyo" />);
      expect(screen.getByText("2026/09/11 18:10:35")).toBeInTheDocument();
    });

    it("個人設定TZが未設定ならブラウザTZ(Pacific/Auckland)で表示する", () => {
      mockBrowserTimeZone("Pacific/Auckland");
      render(<UnifiedLogRow entry={entry({ createdAt: "2026-09-11T09:10:35" })} timezone={null} />);
      expect(screen.getByText("2026/09/11 21:10:35")).toBeInTheDocument();
    });

    it("個人設定TZが未設定ならブラウザTZ(America/New_York)で表示する", () => {
      mockBrowserTimeZone("America/New_York");
      render(<UnifiedLogRow entry={entry({ createdAt: "2026-09-11T09:10:35" })} timezone={null} />);
      expect(screen.getByText("2026/09/11 05:10:35")).toBeInTheDocument();
    });
  });
});
