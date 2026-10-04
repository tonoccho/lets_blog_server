import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const getStatus = jest.fn();
const install = jest.fn();
jest.mock("../../../actions", () => ({
  getLetsblogPluginStatusAction: (...a: unknown[]) => getStatus(...a),
  installLetsblogPluginAction: (...a: unknown[]) => install(...a),
}));
import { LetsblogPluginPanel } from "../LetsblogPluginPanel";

const installed = { state: "INSTALLED", version: "1.0.0", protocolVersion: 1 };
const notInstalled = { state: "NOT_INSTALLED", version: null, protocolVersion: null };
const needsUpdate = { state: "NEEDS_UPDATE", version: "0.9.0", protocolVersion: 0 };

describe("LetsblogPluginPanel(issue #1557)", () => {
  beforeEach(() => jest.clearAllMocks());

  it("導入済みならバージョンつきで表示し、再導入ボタンは出さない", async () => {
    getStatus.mockResolvedValue(installed);
    render(<LetsblogPluginPanel siteId={3} />);
    expect(await screen.findByText("導入済み(v1.0.0)")).toBeInTheDocument();
    expect(getStatus).toHaveBeenCalledWith(3);
    expect(screen.queryByRole("button", { name: "プラグインを再導入" })).toBeNull();
  });

  it("取得中はその旨を表示する", () => {
    getStatus.mockReturnValue(new Promise(() => {}));
    render(<LetsblogPluginPanel siteId={3} />);
    expect(screen.getByText("プラグインの状態を確認中…")).toBeInTheDocument();
  });

  it("未導入なら未導入と表示し、再導入すると導入済みへ変わる", async () => {
    getStatus.mockResolvedValue(notInstalled);
    install.mockResolvedValue(installed);
    render(<LetsblogPluginPanel siteId={3} />);
    expect(await screen.findByText("未導入")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "プラグインを再導入" }));

    expect(await screen.findByText("導入済み(v1.0.0)")).toBeInTheDocument();
    expect(install).toHaveBeenCalledWith(3);
    expect(screen.queryByRole("button", { name: "プラグインを再導入" })).toBeNull();
  });

  it("要更新なら要更新と表示し、投稿とプレビューができない旨と再導入ボタンを出す", async () => {
    getStatus.mockResolvedValue(needsUpdate);
    render(<LetsblogPluginPanel siteId={3} />);
    expect(await screen.findByText("要更新")).toBeInTheDocument();
    expect(screen.getByText(/投稿とプレビューはできません/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "プラグインを再導入" })).toBeInTheDocument();
  });

  it("状態を取得できなかったときはエラーを表示する", async () => {
    getStatus.mockRejectedValue(new Error("APIエラー (502): bad gateway"));
    render(<LetsblogPluginPanel siteId={3} />);
    expect(await screen.findByText(/導入状態を取得できませんでした/)).toBeInTheDocument();
    expect(screen.getByText(/bad gateway/)).toBeInTheDocument();
  });

  it("エラーが Error でなくても表示する", async () => {
    getStatus.mockRejectedValue("boom");
    render(<LetsblogPluginPanel siteId={3} />);
    expect(await screen.findByText(/boom/)).toBeInTheDocument();
  });

  it("再導入に失敗したらエラーを表示し、状態は変えない", async () => {
    getStatus.mockResolvedValue(notInstalled);
    install.mockRejectedValue(new Error("有効化に失敗しました"));
    render(<LetsblogPluginPanel siteId={3} />);
    await userEvent.click(await screen.findByRole("button", { name: "プラグインを再導入" }));
    await waitFor(() => expect(screen.getByText(/有効化に失敗しました/)).toBeInTheDocument());
    expect(screen.getByText("未導入")).toBeInTheDocument();
  });

  it("再導入に失敗したのが Error でなくても表示する", async () => {
    getStatus.mockResolvedValue(notInstalled);
    install.mockRejectedValue("nope");
    render(<LetsblogPluginPanel siteId={3} />);
    await userEvent.click(await screen.findByRole("button", { name: "プラグインを再導入" }));
    expect(await screen.findByText(/nope/)).toBeInTheDocument();
  });

  it("アンマウント後に取得が完了しても状態を更新しない", async () => {
    let resolve: (v: unknown) => void = () => {};
    getStatus.mockReturnValue(new Promise((r) => (resolve = r)));
    const errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    const { unmount } = render(<LetsblogPluginPanel siteId={3} />);
    unmount();
    resolve(installed);
    await Promise.resolve();
    expect(errorSpy).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
