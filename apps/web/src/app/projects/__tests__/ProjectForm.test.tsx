/**
 * プロジェクト作成フォーム。自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は差し替えない。
 * 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

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
import { ProjectForm } from "../ProjectForm";

let created: Record<string, unknown>[];
let create: () => Response;

beforeEach(() => {
  created = [];
  resetServerRuntime(ADMIN_SESSION);
  create = () => jsonResponse({ id: 1 }, 201);
  installFetch({
    "POST /api/projects": (_u, init) => {
      created.push(JSON.parse(String(init.body)));
      return create();
    },
  });
});

const nameInput = (c: HTMLElement) => c.querySelector('[name="name"]') as HTMLInputElement;
const slugInput = (c: HTMLElement) => c.querySelector('[name="slug"]') as HTMLInputElement;

describe("ProjectForm", () => {
  it("プロジェクト名を入れると、slug を小文字・半角英数字とハイフンにして自動で埋める", () => {
    const { container } = render(<ProjectForm />);
    fireEvent.change(nameInput(container), { target: { value: "  My Project 1 " } });
    expect(slugInput(container).value).toBe("my-project-1");
  });

  it("slug を利用者が直接編集したら、以後は名前を変えても slug を上書きしない", () => {
    const { container } = render(<ProjectForm />);
    fireEvent.change(slugInput(container), { target: { value: "custom" } });
    fireEvent.change(nameInput(container), { target: { value: "Other" } });
    expect(slugInput(container).value).toBe("custom");
  });

  it("作成に成功すると完了を示し、フォームを空に戻して slug の自動入力を再開する", async () => {
    const { container } = render(<ProjectForm />);
    fireEvent.change(slugInput(container), { target: { value: "custom" } });
    fireEvent.change(nameInput(container), { target: { value: "案件" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);

    expect(await screen.findByText("作成しました。")).toBeInTheDocument();
    expect(created).toEqual([{ name: "案件", slug: "custom" }]);
    await waitFor(() => expect(nameInput(container).value).toBe(""));
    expect(slugInput(container).value).toBe("");

    fireEvent.change(nameInput(container), { target: { value: "Next One" } });
    expect(slugInput(container).value).toBe("next-one");
  });

  it("名前か slug が空なら、通信せずに入力を促す", async () => {
    const { container } = render(<ProjectForm />);
    fireEvent.change(nameInput(container), { target: { value: "   " } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);
    expect(await screen.findByText("プロジェクト名とslugを入力してください。")).toBeInTheDocument();
    expect(created).toEqual([]);
  });

  it("作成に失敗したら、バックエンドのエラーを表示する", async () => {
    create = () => textResponse("slug が重複しています", 409);
    const { container } = render(<ProjectForm />);
    fireEvent.change(nameInput(container), { target: { value: "A" } });
    fireEvent.change(slugInput(container), { target: { value: "a" } });
    fireEvent.submit(container.querySelector("form") as HTMLFormElement);
    expect(await screen.findByText(/slug が重複しています/)).toBeInTheDocument();
    expect(screen.queryByText("作成しました。")).not.toBeInTheDocument();
  });
});
