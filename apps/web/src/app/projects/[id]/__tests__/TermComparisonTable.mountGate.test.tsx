import { fireEvent, render, screen } from "@testing-library/react";
import type { TermComparisonPage, TermEnvironmentValue } from "@/lib/apiClient";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";

/**
 * issue #1414(#1413の横展開): 新規追加・編集フォームの送信ボタンは、ハイドレーション完了前は
 * 押せないようにする。
 *
 * `react` の `useEffect` は差し替えない(#1836)。フォームはクリックで開くので、サーバー描画では
 * 「開いた直後でマウント前」の出力が得られない。代わりに、React がフォームを実際の DOM へ差し込む瞬間
 * (`Node.prototype.insertBefore` / `appendChild` の呼び出しの直後。passive effect で `mounted` が真になる前)の
 * 送信ボタンの状態を記録する。差し込みそのものは本物へ通す(DOM の呼び出しを観測するだけ)。
 *
 * 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` も差し替えない。外部との通信(`fetch`)と
 * Next.js の実行基盤だけを代替する。
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

import { TermComparisonTable } from "../TermComparisonTable";

const value: TermEnvironmentValue = {
  available: true,
  error: false,
  errorMessage: null,
  slug: "news",
  parentSlug: null,
  description: null,
};

const initialPage: TermComparisonPage = {
  items: [{ name: "お知らせ", slug: "news", local: value, test: value, production: value }],
  page: 0,
  size: 20,
  totalCount: 1,
  masterEnvironment: "production",
};

/** フォームが DOM に差し込まれた直後(effect が走る前)の、送信ボタンの disabled を記録する。 */
function observeSubmitButtonOnInsert(buttonName: string): { disabledAtInsert: boolean[]; restore: () => void } {
  const disabledAtInsert: boolean[] = [];
  const record = (node: unknown) => {
    if (!(node instanceof HTMLElement) || !node.isConnected) return;
    const buttons = node.matches("form") ? [node] : Array.from(node.querySelectorAll("form"));
    for (const form of buttons) {
      const submit = Array.from(form.querySelectorAll("button")).find((b) => b.textContent === buttonName);
      if (submit) disabledAtInsert.push(submit.disabled);
    }
  };
  const originalInsertBefore = Node.prototype.insertBefore;
  const originalAppendChild = Node.prototype.appendChild;
  const insertBefore = jest.spyOn(Node.prototype, "insertBefore").mockImplementation(function <T extends Node>(
    this: Node,
    node: T,
    child: Node | null
  ) {
    const result = originalInsertBefore.call(this, node, child) as T;
    record(node);
    return result;
  });
  const appendChild = jest.spyOn(Node.prototype, "appendChild").mockImplementation(function <T extends Node>(this: Node, node: T) {
    const result = originalAppendChild.call(this, node) as T;
    record(node);
    return result;
  });
  return {
    disabledAtInsert,
    restore: () => {
      insertBefore.mockRestore();
      appendChild.mockRestore();
    },
  };
}

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  installFetchByPath({});
});

describe("TermComparisonTable(マウント前)", () => {
  it("マウント前は新規追加フォームの送信ボタンを押せない(issue #1414)", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={initialPage} />);
    const observer = observeSubmitButtonOnInsert("マスター環境に追加");

    fireEvent.click(screen.getByRole("button", { name: "+ 新規追加" }));
    observer.restore();

    expect(observer.disabledAtInsert).toEqual([true]);
    expect(screen.getByRole("button", { name: "マスター環境に追加" })).toBeEnabled();
  });

  it("マウント前は編集フォームの送信ボタンを押せない(issue #1414)", () => {
    render(<TermComparisonTable projectId={1} kind="category" initialPage={initialPage} />);
    const observer = observeSubmitButtonOnInsert("保存(全環境に反映)");

    fireEvent.click(screen.getByRole("button", { name: "編集" }));
    observer.restore();

    expect(observer.disabledAtInsert).toEqual([true]);
    expect(screen.getByRole("button", { name: "保存(全環境に反映)" })).toBeEnabled();
  });
});
