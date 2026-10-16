import { fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * 自前の Server Action(../custom-tags/actions)・@/lib/session・@/lib/apiClient は差し替えない。
 * 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { CssSelectorPrefixForm } from "../custom-tags/CssSelectorPrefixForm";

let saved: { id: string; body: unknown }[];
let save: () => Response;

beforeEach(() => {
  saved = [];
  resetServerRuntime(ADMIN_SESSION);
  save = () => jsonResponse({});
  installFetch({
    "PUT /api/projects/:id/css-selector-prefix": (_u, init, [id]) => {
      saved.push({ id, body: JSON.parse(String(init.body)) });
      return save();
    },
  });
});

describe("CssSelectorPrefixForm", () => {
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

  it("保存するとプロジェクトIDと入力値をバックエンドへ送り、成功を表示する", async () => {
    render(<CssSelectorPrefixForm projectId={5} projectSlug="my-site" cssSelectorPrefix={null} />);

    fireEvent.change(screen.getByLabelText("CSSセレクタ接頭辞"), { target: { value: "alpha" } });
    fireEvent.click(screen.getByRole("button", { name: "接頭辞を保存" }));

    await waitFor(() => expect(screen.getByText("CSSセレクタ接頭辞を保存しました。")).toBeInTheDocument());
    expect(saved).toEqual([{ id: "5", body: { cssSelectorPrefix: "alpha" } }]);
  });

  it("失敗すると理由を表示し、成功メッセージは出さない", async () => {
    save = () => textResponse("接頭辞が不正です", 400);
    render(<CssSelectorPrefixForm projectId={5} projectSlug="my-site" cssSelectorPrefix="a" />);

    fireEvent.click(screen.getByRole("button", { name: "接頭辞を保存" }));

    await waitFor(() => expect(screen.getByText(/接頭辞が不正です/)).toBeInTheDocument());
    expect(screen.queryByText("CSSセレクタ接頭辞を保存しました。")).not.toBeInTheDocument();
  });
});
