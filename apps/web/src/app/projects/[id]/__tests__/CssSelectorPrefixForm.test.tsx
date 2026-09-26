import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { CssSelectorPrefixForm } from "../custom-tags/CssSelectorPrefixForm";
import { updateProjectCssSelectorPrefixAction } from "../custom-tags/actions";

jest.mock("../custom-tags/actions", () => ({
  updateProjectCssSelectorPrefixAction: jest.fn(),
}));

const actionMock = updateProjectCssSelectorPrefixAction as jest.MockedFunction<
  typeof updateProjectCssSelectorPrefixAction
>;

describe("CssSelectorPrefixForm", () => {
  beforeEach(() => {
    actionMock.mockReset();
  });

  it("設定済みの接頭辞を入力欄の初期値にする", () => {
    render(<CssSelectorPrefixForm projectId={5} projectSlug="my-site" cssSelectorPrefix="theme-x" />);
    expect(screen.getByLabelText("CSSセレクタ接頭辞")).toHaveValue("theme-x");
    expect(screen.getByText(/theme-x/, { selector: "[data-testid='effective-prefix']" })).toBeInTheDocument();
  });

  it("未設定なら入力欄は空で、有効な接頭辞としてslugを示す", () => {
    render(<CssSelectorPrefixForm projectId={5} projectSlug="my-site" cssSelectorPrefix={null} />);
    expect(screen.getByLabelText("CSSセレクタ接頭辞")).toHaveValue("");
    expect(screen.getByTestId("effective-prefix")).toHaveTextContent("my-site");
  });

  it("保存するとプロジェクトIDと入力値でアクションを呼び、成功を表示する", async () => {
    actionMock.mockResolvedValue({ success: true });
    render(<CssSelectorPrefixForm projectId={5} projectSlug="my-site" cssSelectorPrefix={null} />);

    fireEvent.change(screen.getByLabelText("CSSセレクタ接頭辞"), { target: { value: "alpha" } });
    fireEvent.click(screen.getByRole("button", { name: "接頭辞を保存" }));

    await waitFor(() => expect(screen.getByText("CSSセレクタ接頭辞を保存しました。")).toBeInTheDocument());
    expect(actionMock).toHaveBeenCalledTimes(1);
    const [projectId, , formData] = actionMock.mock.calls[0];
    expect(projectId).toBe(5);
    expect(formData.get("cssSelectorPrefix")).toBe("alpha");
  });

  it("失敗すると理由を表示し、成功メッセージは出さない", async () => {
    actionMock.mockResolvedValue({ error: "接頭辞が不正です" });
    render(<CssSelectorPrefixForm projectId={5} projectSlug="my-site" cssSelectorPrefix="a" />);

    fireEvent.click(screen.getByRole("button", { name: "接頭辞を保存" }));

    await waitFor(() => expect(screen.getByText("接頭辞が不正です")).toBeInTheDocument());
    expect(screen.queryByText("CSSセレクタ接頭辞を保存しました。")).not.toBeInTheDocument();
  });
});
