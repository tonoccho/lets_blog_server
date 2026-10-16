import { render, screen } from "@testing-library/react";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";

/**
 * issue #1414(#1413の横展開): ハイドレーション完了前は「保存」ボタンを押せないようにする。
 *
 * `react` の `useEffect` は差し替えない(#1837)。代わりに、React が DOM へ要素を差し込む瞬間
 * (`Node.prototype.insertBefore` / `appendChild` の呼び出しの直後。passive effect で `mounted` が真になる前)の
 * 保存ボタンの状態を記録する。差し込みそのものは本物へ通す(DOM の呼び出しを観測するだけ)。
 *
 * 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` も差し替えない。外部との通信(`fetch`)と
 * Next.js の実行基盤だけを代替する。
 */
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule);
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));

import { LlmModelPanel } from "../LlmModelPanel";

/** 要素が DOM に差し込まれた直後(effect が走る前)の、保存ボタンの disabled を記録する。 */
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

describe("LlmModelPanel(マウント前)", () => {
  it("マウント前は保存ボタンを押せない(ネイティブPOSTへのフォールバックを塞ぐ、issue #1414)", () => {
    const observer = observeSubmitButtonOnInsert("保存");

    render(<LlmModelPanel projectId={1} initialData={{ selected: "gpt-4o-mini", availableModels: [] }} />);
    observer.restore();

    expect(observer.disabledAtInsert).toEqual([true]);
    expect(screen.getByRole("button", { name: "保存" })).toBeEnabled();
  });
});
