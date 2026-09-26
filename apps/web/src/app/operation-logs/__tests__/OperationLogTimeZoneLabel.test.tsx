/**
 * @jest-environment jsdom
 */
import { act } from "react";
import { hydrateRoot } from "react-dom/client";
import { renderToString } from "react-dom/server";
import { render, screen } from "@testing-library/react";
import { OperationLogTimeZoneLabel } from "../OperationLogTimeZoneLabel";
import { UnifiedLogRow } from "../UnifiedLogRow";
import type { UnifiedLogEntry } from "@/lib/apiClient";

jest.mock("../actions", () => ({
  copyOperationTraceAction: jest.fn().mockResolvedValue("copied-trace"),
}));

const entry: UnifiedLogEntry = {
  sourceType: "OPERATION",
  id: 1,
  createdAt: "2026-09-11T09:10:35",
  title: "GET /api/projects",
  detail: null,
  status: null,
  operationId: null,
  actorKeycloakSub: null,
};

function mockBrowserTimeZone(timeZone: string) {
  jest
    .spyOn(Intl.DateTimeFormat.prototype, "resolvedOptions")
    .mockReturnValue({ timeZone } as Intl.ResolvedDateTimeFormatOptions);
}

describe("OperationLogTimeZoneLabel(issue #1260)", () => {
  afterEach(() => jest.restoreAllMocks());

  it("個人設定TZがあればそれを表記する", () => {
    mockBrowserTimeZone("Pacific/Auckland");
    render(<OperationLogTimeZoneLabel personalTimeZone="Asia/Tokyo" />);
    expect(screen.getByTestId("operation-log-timezone")).toHaveTextContent("表示タイムゾーン: Asia/Tokyo");
  });

  it("個人設定TZが未設定ならブラウザTZを表記する", () => {
    mockBrowserTimeZone("America/New_York");
    render(<OperationLogTimeZoneLabel personalTimeZone={null} />);
    expect(screen.getByTestId("operation-log-timezone")).toHaveTextContent("表示タイムゾーン: America/New_York");
  });

  it("サーバー描画(マウント前)では未設定のときブラウザTZを出さず固定の仮表示にする", () => {
    mockBrowserTimeZone("Pacific/Auckland");
    const html = renderToString(<OperationLogTimeZoneLabel personalTimeZone={null} />);
    expect(html).not.toContain("Pacific/Auckland");
    expect(html).toContain("読み込み中");
  });

  it("サーバー描画の結果をブラウザTZが違う環境でハイドレートしても不一致を起こさず、その後ブラウザTZへ切り替わる", async () => {
    // サーバー(UTC)で描画した状態を再現してから、ブラウザ(Auckland)でハイドレートする。
    mockBrowserTimeZone("UTC");
    const tree = (
      <>
        <OperationLogTimeZoneLabel personalTimeZone={null} />
        <UnifiedLogRow entry={entry} timezone={null} />
      </>
    );
    const serverHtml = renderToString(tree);
    mockBrowserTimeZone("Pacific/Auckland");

    const container = document.createElement("div");
    container.innerHTML = serverHtml;
    document.body.appendChild(container);
    const errors = jest.spyOn(console, "error").mockImplementation(() => {});
    await act(async () => {
      hydrateRoot(container, tree);
    });

    expect(errors).not.toHaveBeenCalled();
    expect(container.textContent).toContain("表示タイムゾーン: Pacific/Auckland");
    expect(container.textContent).toContain("2026/09/11 21:10:35");
    container.remove();
  });
});
