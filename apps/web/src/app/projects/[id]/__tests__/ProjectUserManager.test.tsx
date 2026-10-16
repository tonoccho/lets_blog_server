import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { ProjectUserManager } from "../ProjectUserManager";
import type { ProjectUser } from "@/lib/apiClient";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, calledRequests, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies } from "./projectCoreRuntime";

// 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1836)。
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))

const SYNC = "POST /api/projects/1/users/5/sync";
const ROLE = "PUT /api/projects/1/users/5";
const REMOVE = "DELETE /api/projects/1/users/5";

/** 同期の応答(各サイトの結果)を返すバックエンドにする。 */
function backendSyncing(results: unknown) {
  return installFetchByPath({ [SYNC]: () => jsonResponse(results) });
}

function buildMember(overrides: Partial<ProjectUser> = {}): ProjectUser {
  return {
    userId: 1,
    email: "member@example.com",
    displayName: "テストメンバー",
    wpRole: "author",
    ...overrides,
  };
}

describe("ProjectUserManager(issue #1242: ユーザー情報同期ボタン)", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    installFetchByPath({ [SYNC]: () => jsonResponse([]), [ROLE]: () => jsonResponse(null, 204), [REMOVE]: () => jsonResponse(null, 204) });
  });

  it("各メンバー行に「ユーザー情報を同期」ボタンが表示される", () => {
    render(<ProjectUserManager projectId={1} members={[buildMember()]} />);

    expect(screen.getByRole("button", { name: "ユーザー情報を同期" })).toBeInTheDocument();
  });

  it("ボタンを押すとユーザー情報の同期がバックエンドへ送られ、成功した環境が表示される", async () => {
    const fetchMock = backendSyncing([
      { siteId: 10, siteKey: "local-key", siteName: "ローカル", success: true, errorMessage: null },
    ]);

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    await waitFor(() => {
      expect(calledRequests(fetchMock)).toEqual([SYNC]);
    });
    expect(await screen.findByText(/ローカル/)).toBeInTheDocument();
    expect(screen.getByText(/成功/)).toBeInTheDocument();
  });

  it("一部の環境が失敗すると、成功分は保持したまま失敗した環境名と理由が表示される", async () => {
    installFetchByPath({
      [SYNC]: () =>
        jsonResponse([
          { siteId: 10, siteKey: "local-key", siteName: "ローカル", success: true, errorMessage: null },
          { siteId: 20, siteKey: "test-key", siteName: "テスト環境", success: false, errorMessage: "接続に失敗しました" },
        ]),
    });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText(/ローカル/)).toBeInTheDocument();
    expect(screen.getByText(/テスト環境/)).toBeInTheDocument();
    expect(screen.getByText(/接続に失敗しました/)).toBeInTheDocument();
  });

  it("同期そのものが失敗するとエラーメッセージが表示される", async () => {
    installFetchByPath({ [SYNC]: () => textResponse("サーバーエラーが発生しました", 500) });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText("APIエラー (500): サーバーエラーが発生しました")).toBeInTheDocument();
  });

  it("紐づくWordPress環境が無い場合はその旨を表示する", async () => {
    backendSyncing([]);

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText("紐づくWordPress環境がありません。")).toBeInTheDocument();
  });

  it("失敗理由が無い場合は理由の括弧を付けずに「失敗」とだけ表示する", async () => {
    backendSyncing([{ siteId: 20, siteKey: "test-key", siteName: "テスト環境", success: false, errorMessage: null }]);

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText("テスト環境: 失敗")).toBeInTheDocument();
  });
});

describe("ProjectUserManager(issue #1302: ロール変更の失敗表示)", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    installFetchByPath({ [SYNC]: () => jsonResponse([]), [ROLE]: () => jsonResponse(null, 204), [REMOVE]: () => jsonResponse(null, 204) });
  });

  it("ロール変更が失敗すると、そのメンバー行にエラーメッセージが表示される", async () => {
    const fetchMock = installFetchByPath({
      [ROLE]: () => textResponse("テスト環境: 著者プロビジョニング呼び出しに失敗しました: connect timed out", 500),
    });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "editor" } });

    await waitFor(() => {
      expect(bodies(fetchMock, ROLE)).toEqual([{ wpRole: "editor" }]);
    });
    const message = await screen.findByText(/テスト環境: 著者プロビジョニング呼び出しに失敗しました/);
    expect(message.closest("tr")).toHaveTextContent("member@example.com");
  });

  it("ロール変更が成功すると、エラーは表示されない", async () => {
    const fetchMock = installFetchByPath({ [ROLE]: () => jsonResponse(null, 204) });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "editor" } });

    await waitFor(() => {
      expect(bodies(fetchMock, ROLE)).toEqual([{ wpRole: "editor" }]);
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("失敗後に再度ロール変更して成功すると、前回のエラー表示は消える", async () => {
    let calls = 0;
    installFetchByPath({ [ROLE]: () => (++calls === 1 ? textResponse("失敗しました", 500) : jsonResponse(null, 204)) });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "editor" } });
    expect(await screen.findByText("APIエラー (500): 失敗しました")).toBeInTheDocument();

    fireEvent.change(screen.getByRole("combobox"), { target: { value: "contributor" } });
    await waitFor(() => {
      expect(screen.queryByText("APIエラー (500): 失敗しました")).not.toBeInTheDocument();
    });
    expect(calls).toBe(2);
  });
});

describe("ProjectUserManager のユーザー情報同期(応答の形)", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
  });

  it("バックエンドが本文の無い成功(204)を返したときは、環境が無いものとして扱う", async () => {
    installFetchByPath({ [SYNC]: () => jsonResponse(null, 204) });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "ユーザー情報を同期" }));

    expect(await screen.findByText("紐づくWordPress環境がありません。")).toBeInTheDocument();
  });
});

describe("ProjectUserManager のメンバー削除", () => {
  let confirmSpy: jest.SpyInstance;

  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    confirmSpy = jest.spyOn(window, "confirm");
  });
  afterEach(() => {
    confirmSpy.mockRestore();
  });

  it("確認で承認するとプロジェクトからメンバーを外す要求を送る", async () => {
    confirmSpy.mockReturnValue(true);
    const fetchMock = installFetchByPath({ [REMOVE]: () => jsonResponse(null, 204) });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => expect(calledRequests(fetchMock)).toEqual([REMOVE]));
  });

  it("確認で取り消すと何も送らない", () => {
    confirmSpy.mockReturnValue(false);
    const fetchMock = installFetchByPath({ [REMOVE]: () => jsonResponse(null, 204) });

    render(<ProjectUserManager projectId={1} members={[buildMember({ userId: 5 })]} />);
    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(calledRequests(fetchMock)).toEqual([]);
  });
});

describe("ProjectUserManager の空状態(issue #1069)", () => {
  it("メンバーが居ないときは、居ない旨に加えて追加の方法を案内する", () => {
    render(<ProjectUserManager projectId={1} members={[]} />);

    expect(screen.getByText(/参加ユーザーはいません/)).toBeInTheDocument();
    expect(screen.getByText(/「ユーザーを追加」から追加してください/)).toBeInTheDocument();
    expect(screen.getByText(/先に.*サイトを紐付けて/)).toBeInTheDocument();
  });

  it("メンバーが居るときは空状態の案内を出さない", () => {
    render(<ProjectUserManager projectId={1} members={[buildMember()]} />);

    expect(screen.queryByText(/参加ユーザーはいません/)).not.toBeInTheDocument();
  });
});
