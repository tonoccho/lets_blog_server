/**
 * 統合CSSの表示とコピー。差し替えるのは外部との通信(fetch)・ブラウザのクリップボード・時計だけ(#1835)。
 */
import { act, fireEvent, render, screen } from "@testing-library/react";
import { CssBundleViewer } from "../CssBundleViewer";

function cssResponse(text: string): Response {
  return { ok: true, status: 200, text: async () => text } as unknown as Response;
}

function failureResponse(status: number, body: unknown): Response {
  return {
    ok: false,
    status,
    json: async () => {
      if (body === undefined) throw new Error("not json");
      return body;
    },
  } as unknown as Response;
}

describe("CssBundleViewer", () => {
  let fetchMock: jest.Mock;
  let writeText: jest.Mock;

  beforeEach(() => {
    fetchMock = jest.fn().mockResolvedValue(cssResponse(".a{color:red}"));
    global.fetch = fetchMock as unknown as typeof fetch;
    writeText = jest.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
  });

  it("読み込み中を示し、取得したCSSを表示する", async () => {
    render(<CssBundleViewer projectId={7} />);
    expect(screen.getByText("読み込み中...")).toBeInTheDocument();

    expect(await screen.findByText(".a{color:red}")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith("/projects/7/custom-tags/css-bundle");
  });

  it("失敗の応答に error があればその文言を、無ければ状態コードを示す", async () => {
    fetchMock.mockResolvedValueOnce(failureResponse(502, { error: "gateway down" }));
    const first = render(<CssBundleViewer projectId={7} />);
    expect(await screen.findByText("gateway down")).toBeInTheDocument();
    first.unmount();

    fetchMock.mockResolvedValueOnce(failureResponse(500, undefined));
    render(<CssBundleViewer projectId={7} />);
    expect(await screen.findByText("取得に失敗しました (500)")).toBeInTheDocument();
  });

  it("通信そのものが Error でない値で失敗しても、その文字列を示す", async () => {
    fetchMock.mockRejectedValueOnce("offline");
    render(<CssBundleViewer projectId={7} />);
    expect(await screen.findByText("offline")).toBeInTheDocument();
  });

  it("取得が終わる前に外れても、状態を更新しない", async () => {
    let resolve: (r: Response) => void = () => {};
    fetchMock.mockReturnValueOnce(new Promise<Response>((r) => (resolve = r)));
    const { unmount } = render(<CssBundleViewer projectId={7} />);
    unmount();
    await act(async () => resolve(cssResponse("x")));
  });

  it("取得が終わる前に外れて、そのあと失敗しても、状態を更新しない", async () => {
    let reject: (e: unknown) => void = () => {};
    fetchMock.mockReturnValueOnce(new Promise<Response>((_, r) => (reject = r)));
    const { unmount } = render(<CssBundleViewer projectId={7} />);
    unmount();
    await act(async () => reject(new Error("late")));
  });

  it("「CSSをコピー」でクリップボードへ書き、しばらくして表示を戻す", async () => {
    jest.useFakeTimers();
    try {
      render(<CssBundleViewer projectId={7} />);
      const button = await screen.findByRole("button", { name: "CSSをコピー" });

      await act(async () => {
        fireEvent.click(button);
      });
      expect(writeText).toHaveBeenCalledWith(".a{color:red}");
      expect(screen.getByRole("button", { name: "コピーしました" })).toBeInTheDocument();

      act(() => {
        jest.advanceTimersByTime(2000);
      });
      expect(screen.getByRole("button", { name: "CSSをコピー" })).toBeInTheDocument();
    } finally {
      jest.useRealTimers();
    }
  });

  it("空のCSSが返ったときは、コピーしても何も書かない", async () => {
    fetchMock.mockResolvedValueOnce(cssResponse(""));
    render(<CssBundleViewer projectId={7} />);
    const button = await screen.findByRole("button", { name: "CSSをコピー" });
    await act(async () => {
      fireEvent.click(button);
    });
    expect(writeText).not.toHaveBeenCalled();
  });
});
