import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";

/**
 * SiteForm(既存サイトの登録)。自前の Server Action(../actions)・@/lib/session・@/lib/apiClient は
 * 差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
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

import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { SiteForm } from "../SiteForm";

const keyPairs = [
  { id: 1, name: "鍵A", comment: "メモ", publicKeyLine: "ssh-ed25519 AAA", createdAt: "2026-01-01T00:00:00Z" },
  { id: 2, name: "鍵B", comment: null, publicKeyLine: "ssh-ed25519 BBB", createdAt: "2026-01-01T00:00:00Z" },
];

let registered: Record<string, unknown>[];
let register: () => Promise<Response> | Response;
let keygen: () => Promise<Response> | Response;

beforeEach(() => {
  registered = [];
  resetServerRuntime(ADMIN_SESSION);
  register = () => jsonResponse({ connectionCheckStatus: "SUCCESS" });
  keygen = () => jsonResponse({ publicKeyLine: "ssh-ed25519 NEW", privateKeyPem: "PEM-NEW" });
  installFetch({
    "POST /api/sites": (_u, init) => {
      registered.push(JSON.parse(String(init.body)));
      return register();
    },
    "POST /api/sites/ssh-keypair": () => keygen(),
  });
});

function fill(container: HTMLElement, extra: Record<string, string> = {}) {
  const values: Record<string, string> = {
    name: "N",
    siteKey: "k",
    baseUrl: "https://e.example.com",
    sshHost: "h",
    sshUser: "u",
    wpPath: "/w",
    ...extra,
  };
  for (const [name, value] of Object.entries(values)) {
    fireEvent.change(container.querySelector(`[name="${name}"]`) as HTMLInputElement, { target: { value } });
  }
}

const submit = (container: HTMLElement) => fireEvent.submit(container.querySelector("form") as HTMLFormElement);

describe("SiteForm の登録操作", () => {
  it("保存済みの鍵ペアがあれば既定でそれを選ぶ選択肢を出し、鍵ペアIDを付けて登録する", async () => {
    const { container } = render(<SiteForm sshKeyPairs={keyPairs} defaultAdminPath={null} />);
    expect(screen.getByRole("option", { name: "鍵A(メモ)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "鍵B" })).toBeInTheDocument();
    fill(container, { sshPort: "2222" });
    submit(container);
    expect(await screen.findByText("登録しました。")).toBeInTheDocument();
    expect(screen.getByText("疎通確認: 成功しました。")).toBeInTheDocument();
    const credentials = registered[0].credentials as Record<string, string>;
    expect(credentials.sshKeyPairId).toBe("1");
    expect(credentials.sshPort).toBe("2222");
  });

  it("疎通確認に失敗しても登録自体は完了したと示す", async () => {
    register = () => jsonResponse({ connectionCheckStatus: "FAILED" });
    const { container } = render(<SiteForm sshKeyPairs={keyPairs} defaultAdminPath={null} />);
    fill(container);
    submit(container);
    expect(await screen.findByText(/疎通確認: 失敗しました/)).toBeInTheDocument();
    expect(screen.queryByText("疎通確認: 成功しました。")).toBeNull();
  });

  it("疎通確認の結果が無いときは疎通確認の行を出さない", async () => {
    register = () => jsonResponse({ connectionCheckStatus: null });
    const { container } = render(<SiteForm sshKeyPairs={keyPairs} defaultAdminPath={null} />);
    fill(container);
    submit(container);
    expect(await screen.findByText("登録しました。")).toBeInTheDocument();
    expect(screen.queryByText(/疎通確認:/)).toBeNull();
  });

  it("登録に失敗したらバックエンドのメッセージを示す", async () => {
    register = () => textResponse("サイトキーが重複しています", 409);
    const { container } = render(<SiteForm sshKeyPairs={keyPairs} defaultAdminPath={null} />);
    fill(container);
    submit(container);
    expect(await screen.findByText("APIエラー (409): サイトキーが重複しています")).toBeInTheDocument();
  });

  it("登録の往復の間だけ送信ボタンを無効にする", async () => {
    let release: (r: Response) => void = () => {};
    register = () => new Promise<Response>((resolve) => (release = resolve));
    const { container } = render(<SiteForm sshKeyPairs={keyPairs} defaultAdminPath={null} />);
    fill(container);
    submit(container);
    expect(await screen.findByRole("button", { name: /登録中/ })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({ connectionCheckStatus: "SUCCESS" }));
    });
    expect(await screen.findByRole("button", { name: "登録" })).toBeEnabled();
  });

  it("鍵ペアが無いときは生成ボタンだけを出し、生成した公開鍵を示して、その秘密鍵で登録する", async () => {
    const { container } = render(<SiteForm sshKeyPairs={[]} defaultAdminPath={null} />);
    expect(screen.queryByText("保存済みの鍵ペアを使う")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));
    const textarea = (await screen.findByDisplayValue("ssh-ed25519 NEW")) as HTMLTextAreaElement;
    fireEvent.focus(textarea);
    fill(container);
    submit(container);
    expect(await screen.findByText("登録しました。")).toBeInTheDocument();
    expect((registered[0].credentials as Record<string, string>).sshPrivateKeyPem).toBe("PEM-NEW");
    // 登録に成功したら、生成した鍵の表示は消える。
    await waitFor(() => expect(screen.queryByDisplayValue("ssh-ed25519 NEW")).toBeNull());
  });

  it("鍵ペアの生成に失敗したら理由を示し、公開鍵は出さない", async () => {
    keygen = () => textResponse("生成できません", 500);
    render(<SiteForm sshKeyPairs={[]} defaultAdminPath={null} />);
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));
    expect(await screen.findByText("APIエラー (500): 生成できません")).toBeInTheDocument();
    expect(screen.queryByText(/以下の公開鍵/)).toBeNull();
  });

  it("生成の応答に鍵が含まれないときは空のまま扱う", async () => {
    keygen = () => jsonResponse({});
    render(<SiteForm sshKeyPairs={[]} defaultAdminPath={null} />);
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "SSH鍵ペアを生成" })).toBeEnabled());
    expect(screen.queryByText(/以下の公開鍵/)).toBeNull();
  });

  it("生成の往復の間は生成ボタンを無効にする", async () => {
    let release: (r: Response) => void = () => {};
    keygen = () => new Promise<Response>((resolve) => (release = resolve));
    render(<SiteForm sshKeyPairs={[]} defaultAdminPath={null} />);
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));
    expect(await screen.findByRole("button", { name: /鍵ペアを生成中/ })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({ publicKeyLine: "ssh-ed25519 NEW", privateKeyPem: "PEM-NEW" }));
    });
    expect(await screen.findByDisplayValue("ssh-ed25519 NEW")).toBeInTheDocument();
  });

  it("鍵ペアを新しく生成する方式へ切り替えられ、保存済みの鍵ペアへも戻せる", () => {
    render(<SiteForm sshKeyPairs={keyPairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("新しい鍵ペアを生成する"));
    expect(screen.getByRole("button", { name: "SSH鍵ペアを生成" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "鍵B" })).toBeNull();
    fireEvent.click(screen.getByLabelText("保存済みの鍵ペアを使う"));
    expect(screen.getByRole("option", { name: "鍵B" })).toBeInTheDocument();
  });

  it("登録に成功したら、方式の選択を初期(鍵ペアありなら保存済み)へ戻す", async () => {
    const { container } = render(<SiteForm sshKeyPairs={keyPairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("新しい鍵ペアを生成する"));
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));
    await screen.findByDisplayValue("ssh-ed25519 NEW");
    fill(container);
    submit(container);
    await screen.findByText("登録しました。");
    await waitFor(() => expect(screen.getByRole("option", { name: "鍵B" })).toBeInTheDocument());
  });
});
