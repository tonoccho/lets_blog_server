import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { UnifiedLogRow } from "../UnifiedLogRow";
import type { UnifiedLogEntry } from "@/lib/apiClient";

jest.mock("../actions", () => ({
  copyOperationTraceAction: jest.fn().mockResolvedValue("copied-trace"),
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

  it("OPERATIONかつoperationIdがあればコピーボタンを表示し、クリックでクリップボードへコピーする", async () => {
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } });
    render(<UnifiedLogRow entry={entry({ operationId: "op-1" })} timezone={null} />);

    const button = screen.getByRole("button", { name: "コピー" });
    fireEvent.click(button);

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith("copied-trace");
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
