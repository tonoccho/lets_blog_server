/**
 * `/sites` のエラー画面。エラーの内容を画面に出し、「もう一度試す」で再試行でき、
 * ブラウザ側のエラーを BFF の /client-errors へ 1 件送る。
 * 差し替えるのは外部との通信(fetch)と標準エラー出力だけ。自前の errorLogger は実物(#1834)。
 */
import { StrictMode } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import SitesError from "../error";

describe("SitesError", () => {
  let fetchMock: jest.Mock;
  let consoleSpy: jest.SpyInstance;

  beforeEach(() => {
    fetchMock = jest.fn().mockResolvedValue({ ok: true });
    global.fetch = fetchMock as unknown as typeof fetch;
    consoleSpy = jest.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => consoleSpy.mockRestore());

  it("エラーのメッセージを表示する", () => {
    render(<SitesError error={new Error("一覧が壊れています")} reset={() => {}} />);

    expect(screen.getByText("サイトの読み込みに失敗しました")).toBeInTheDocument();
    expect(screen.getByText("一覧が壊れています")).toBeInTheDocument();
  });

  it("メッセージが空なら既定の文言を表示する", () => {
    render(<SitesError error={new Error("")} reset={() => {}} />);

    expect(screen.getByText("予期しないエラーが発生しました")).toBeInTheDocument();
  });

  it("「もう一度試す」で reset を呼び、「ホームに戻る」はトップへのリンクになる", () => {
    const reset = jest.fn();
    render(<SitesError error={new Error("boom")} reset={reset} />);

    fireEvent.click(screen.getByRole("button", { name: "もう一度試す" }));

    expect(reset).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("link", { name: "ホームに戻る" })).toHaveAttribute("href", "/");
  });

  it("エラーを /client-errors へ、ページ名と digest を添えて送り、コンソールにも出す", () => {
    const error = Object.assign(new Error("boom"), { digest: "d-1" });
    render(<SitesError error={error} reset={() => {}} />);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/client-errors");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toMatchObject({
      message: "boom",
      level: "error",
      context: { page: "sites", digest: "d-1" },
    });
    expect(consoleSpy).toHaveBeenCalledWith("[ERROR] Sites page error", expect.objectContaining({ error }));
  });

  it("StrictMode で effect が二重に走っても、同じエラーは 1 件しか送らない。別のエラーに変われば改めて送る", () => {
    const first = new Error("first");
    const { rerender } = render(
      <StrictMode>
        <SitesError error={first} reset={() => {}} />
      </StrictMode>
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);

    rerender(
      <StrictMode>
        <SitesError error={new Error("second")} reset={() => {}} />
      </StrictMode>
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).message).toBe("second");
  });
});
