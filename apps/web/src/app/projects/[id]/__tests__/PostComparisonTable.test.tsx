import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { PostComparisonTable } from "../PostComparisonTable";
import {
  fetchPostComparisonAction,
  fetchPostStatusesAction,
  deletePostEverywhereAction,
  updatePostStatusEverywhereAction,
} from "../actions";
import type { PostComparisonPage, PostComparisonRow, PostEnvironmentValue } from "@/lib/apiClient";

/**
 * issue #1181: 投稿の行削除の確認に、その投稿が存在する環境の数と環境名を表示する。
 * 環境の有無は行データ(available / postId / error)だけから求め、追加のAPI呼び出しはしない。
 */
jest.mock("../actions", () => ({
  fetchPostComparisonAction: jest.fn(),
  fetchPostStatusesAction: jest.fn(),
  deletePostEverywhereAction: jest.fn(),
  updatePostStatusEverywhereAction: jest.fn(),
}));

const fetchPageMock = fetchPostComparisonAction as jest.MockedFunction<typeof fetchPostComparisonAction>;
const statusesMock = fetchPostStatusesAction as jest.MockedFunction<typeof fetchPostStatusesAction>;
const deleteMock = deletePostEverywhereAction as jest.MockedFunction<typeof deletePostEverywhereAction>;
const updateMock = updatePostStatusEverywhereAction as jest.MockedFunction<typeof updatePostStatusEverywhereAction>;

function envValue(overrides: Partial<PostEnvironmentValue> = {}): PostEnvironmentValue {
  return {
    available: true,
    error: false,
    errorMessage: null,
    postId: "10",
    title: "こんにちは",
    status: "publish",
    ...overrides,
  };
}

function row(overrides: Partial<PostComparisonRow> = {}): PostComparisonRow {
  return { slug: "hello", local: envValue(), test: envValue(), production: envValue(), ...overrides };
}

function page(overrides: Partial<PostComparisonPage> = {}): PostComparisonPage {
  return { items: [row()], page: 0, size: 20, totalCount: 1, postType: "post", ...overrides } as PostComparisonPage;
}

describe("PostComparisonTable", () => {
  beforeEach(() => {
    fetchPageMock.mockReset();
    statusesMock.mockReset();
    deleteMock.mockReset();
    updateMock.mockReset();
    statusesMock.mockResolvedValue([{ value: "draft", label: "下書き" }]);
    window.confirm = jest.fn();
  });

  async function renderTable(overrides: Partial<PostComparisonPage> = {}) {
    render(<PostComparisonTable projectId={1} initialPage={page(overrides)} />);
    await waitFor(() => expect(statusesMock).toHaveBeenCalled());
  }

  it("削除の確認に、投稿が存在する環境の数と環境名を表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(window.confirm).toHaveBeenCalledWith(
      "「hello」を、存在する3つの環境(ローカル・テスト・本番)から削除します。よろしいですか?"
    );
  });

  it("一部の環境にしか無い投稿では、存在しない環境を確認に含めない", async () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    await renderTable({
      items: [
        row({
          local: envValue({ available: false, postId: null }),
          test: envValue({ postId: null }),
          production: envValue({ error: true }),
        }),
      ],
    });
    // 本番はエラー、ローカルは対象外、テストは未投稿 → 1環境も存在しない
    fireEvent.click(screen.getByRole("button", { name: "削除" }));
    expect(window.confirm).toHaveBeenCalledWith("「hello」を、存在するすべての環境から削除します。よろしいですか?");
  });

  it("2環境にある投稿は、その2環境だけを確認に表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    await renderTable({ items: [row({ local: envValue({ postId: null }) })] });

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    const message = (window.confirm as jest.Mock).mock.calls[0][0] as string;
    expect(message).toContain("存在する2つの環境(テスト・本番)");
    expect(message).not.toContain("ローカル");
  });

  it("確認をキャンセルすると削除しない", async () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(deleteMock).not.toHaveBeenCalled();
  });

  it("確認を承認すると削除し、成功メッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => expect(screen.getByText("削除しました。")).toBeInTheDocument());
    expect(deleteMock).toHaveBeenCalledWith(1, "post", "hello");
  });

  it("削除に失敗するとエラーメッセージを表示する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    deleteMock.mockResolvedValue({ error: "削除に失敗しました" });
    fetchPageMock.mockResolvedValue(page());
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => expect(screen.getByText("削除に失敗しました")).toBeInTheDocument());
  });

  it("ステータス未選択で適用するとエラーを表示し、選択後は確認して変更する", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    updateMock.mockResolvedValue({});
    fetchPageMock.mockResolvedValue(page());
    await renderTable();

    fireEvent.click(screen.getByRole("button", { name: "適用" }));
    expect(screen.getByText("変更後のステータスを選択してください。")).toBeInTheDocument();

    fireEvent.change(screen.getAllByRole("combobox")[1], { target: { value: "draft" } });
    fireEvent.click(screen.getByRole("button", { name: "適用" }));

    await waitFor(() => expect(screen.getByText("変更しました。")).toBeInTheDocument());
  });

  // issue #1382: ステータス一覧の取得失敗を無言にしない
  it("ステータス一覧の取得に失敗するとエラーメッセージを表示する", async () => {
    statusesMock.mockRejectedValue(new Error("boom"));
    await renderTable();

    await waitFor(() => expect(screen.getByText("ステータス一覧の取得に失敗しました。")).toBeInTheDocument());
  });

  it("ステータス一覧を取得できたときはエラーメッセージを表示せず、選択肢を描画する", async () => {
    await renderTable();

    await waitFor(() => expect(screen.getByRole("option", { name: "下書き" })).toBeInTheDocument());
    expect(screen.queryByText("ステータス一覧の取得に失敗しました。")).not.toBeInTheDocument();
  });
});
