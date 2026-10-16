import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { SavedSshKeyPair, SiteDetail } from "@/lib/apiClient";

/**
 * 自前の Server Action(../actions・../../../actions)・InstallWpCliButton・@/lib/session・@/lib/apiClient は
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
import { SiteEditForm } from "../SiteEditForm";

const site = (over: Partial<SiteDetail> = {}): SiteDetail => ({
  id: 1,
  name: "S",
  siteKey: "s-key",
  cmsType: "WORDPRESS",
  baseUrl: "https://s.example.com",
  createdAt: "",
  updatedAt: "",
  managedWordpress: false,
  sshConfigured: false,
  credentials: {},
  configuredSecretFields: [],
  adminPath: null,
  ...over,
});

const pairs: SavedSshKeyPair[] = [
  { id: 1, name: "鍵A", comment: "メモ", publicKeyLine: "ssh-ed25519 AAA", createdAt: "" },
  { id: 2, name: "鍵B", comment: null, publicKeyLine: "ssh-ed25519 BBB", createdAt: "" },
];

let updates: { id: number; body: Record<string, unknown> }[];
let update: () => Promise<Response> | Response;
let keygen: () => Promise<Response> | Response;
let keygenBodies: unknown[];

beforeEach(() => {
  updates = [];
  keygenBodies = [];
  resetServerRuntime(ADMIN_SESSION);
  update = () => jsonResponse({ connectionCheckStatus: "SUCCESS" });
  keygen = () => jsonResponse({ publicKeyLine: "ssh-ed25519 NEW", privateKeyPem: "PEM-NEW" });
  installFetch({
    "PUT /api/sites/:id": (_u, init, [id]) => {
      updates.push({ id: Number(id), body: JSON.parse(String(init.body)) });
      return update();
    },
    "POST /api/sites/ssh-keypair": (_u, init) => {
      keygenBodies.push(JSON.parse(String(init.body)));
      return keygen();
    },
  });
});

const submit = (container: HTMLElement) => fireEvent.submit(container.querySelector("form") as HTMLFormElement);

describe("SiteEditForm の保存", () => {
  it("保存すると、そのサイトを更新し、疎通確認の結果を示す", async () => {
    const { container } = render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    submit(container);
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(screen.getByText("疎通確認: 成功しました。")).toBeInTheDocument();
    expect(updates[0].id).toBe(1);
    expect(updates[0].body.name).toBe("S");
  });

  it("疎通確認に失敗したことを示す", async () => {
    update = () => jsonResponse({ connectionCheckStatus: "FAILED" });
    const { container } = render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    submit(container);
    expect(await screen.findByText(/疎通確認: 失敗しました/)).toBeInTheDocument();
  });

  it("疎通確認の結果が無いときは疎通確認の行を出さない", async () => {
    update = () => jsonResponse({ connectionCheckStatus: null });
    const { container } = render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    submit(container);
    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(screen.queryByText(/疎通確認:/)).toBeNull();
  });

  it("保存に失敗したら理由を示す", async () => {
    update = () => textResponse("名前が長すぎます", 400);
    const { container } = render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    submit(container);
    expect(await screen.findByText("APIエラー (400): 名前が長すぎます")).toBeInTheDocument();
  });

  it("保存中は保存ボタンを無効にする", async () => {
    let release: (r: Response) => void = () => {};
    update = () => new Promise<Response>((resolve) => (release = resolve));
    const { container } = render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    submit(container);
    expect(await screen.findByRole("button", { name: "保存中…" })).toBeDisabled();
    release(jsonResponse({ connectionCheckStatus: null }));
    expect(await screen.findByRole("button", { name: "保存" })).toBeEnabled();
  });
});

describe("SiteEditForm の SSH 鍵の表示", () => {
  it("鍵ペアを使用中なら、その名前を示す", () => {
    render(<SiteEditForm site={site({ credentials: { sshKeyPairId: "2" } })} sshKeyPairs={pairs} defaultAdminPath={null} />);
    expect(screen.getByText("現在: 保存済み鍵ペア「鍵B」を使用中")).toBeInTheDocument();
  });

  it("保存済みの一覧に無い鍵ペアを使用中なら、IDを示す", () => {
    render(<SiteEditForm site={site({ credentials: { sshKeyPairId: "99" } })} sshKeyPairs={pairs} defaultAdminPath={null} />);
    expect(screen.getByText("現在: 保存済み鍵ペア「99」を使用中")).toBeInTheDocument();
  });

  it("個別に生成した秘密鍵が設定済みならそう示す", () => {
    render(
      <SiteEditForm site={site({ configuredSecretFields: ["sshPrivateKeyPem"] })} sshKeyPairs={pairs} defaultAdminPath={null} />,
    );
    expect(screen.getByText("現在: 個別に生成した秘密鍵が設定済みです")).toBeInTheDocument();
  });

  it("どちらも無ければ未設定と示す", () => {
    render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    expect(screen.getByText("SSH秘密鍵は未設定です")).toBeInTheDocument();
  });

  it("SSH 接続が設定されたサイトには wp-cli のインストールを出す", () => {
    render(<SiteEditForm site={site({ sshConfigured: true })} sshKeyPairs={pairs} defaultAdminPath={null} />);
    expect(screen.getByRole("button", { name: "wp-cliをインストール" })).toBeInTheDocument();
  });

  it("保存済みの鍵ペアが無ければ、切り替えの選択肢を出さない", () => {
    render(<SiteEditForm site={site()} sshKeyPairs={[]} defaultAdminPath={null} />);
    expect(screen.queryByLabelText("保存済みの鍵ペアに切り替える")).toBeNull();
  });
});

describe("SiteEditForm の鍵ペアの切り替えと生成", () => {
  it("保存済みの鍵ペアに切り替えると選択欄を出し、使用中の鍵ペアを初期値にする", () => {
    render(<SiteEditForm site={site({ credentials: { sshKeyPairId: "2" } })} sshKeyPairs={pairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("保存済みの鍵ペアに切り替える"));
    expect(screen.getByRole("option", { name: "鍵A(メモ)" })).toBeInTheDocument();
    expect((screen.getByRole("option", { name: "鍵B" }) as HTMLOptionElement).selected).toBe(true);
  });

  it("使用中の鍵ペアが無ければ先頭の鍵ペアを初期値にする", () => {
    render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("保存済みの鍵ペアに切り替える"));
    expect((screen.getByRole("option", { name: "鍵A(メモ)" }) as HTMLOptionElement).selected).toBe(true);
  });

  it("新しい鍵ペアを生成すると公開鍵を示し、サイトキーをコメントに、秘密鍵を保存時に送る", async () => {
    const { container } = render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("新しい鍵ペアを生成する"));
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを新規生成" }));
    const textarea = (await screen.findByDisplayValue("ssh-ed25519 NEW")) as HTMLTextAreaElement;
    fireEvent.focus(textarea);
    expect(keygenBodies).toEqual([{ comment: "s-key" }]);
    submit(container);
    await screen.findByText("保存しました。");
    expect((updates[0].body.credentials as Record<string, string>).sshPrivateKeyPem).toBe("PEM-NEW");
  });

  it("変更しない方式へ戻せる", () => {
    render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("新しい鍵ペアを生成する"));
    fireEvent.click(screen.getByLabelText("変更しない"));
    expect(screen.queryByRole("button", { name: "SSH鍵ペアを新規生成" })).toBeNull();
  });

  it("鍵ペアの生成に失敗したら理由を示す", async () => {
    keygen = () => textResponse("生成できません", 500);
    render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("新しい鍵ペアを生成する"));
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを新規生成" }));
    expect(await screen.findByText("APIエラー (500): 生成できません")).toBeInTheDocument();
  });

  it("生成の応答に鍵が含まれないときは公開鍵を出さない", async () => {
    keygen = () => jsonResponse({});
    render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("新しい鍵ペアを生成する"));
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを新規生成" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "SSH鍵ペアを新規生成" })).toBeEnabled());
    expect(screen.queryByText(/以下の公開鍵/)).toBeNull();
  });

  it("生成中は生成ボタンを無効にする", async () => {
    let release: (r: Response) => void = () => {};
    keygen = () => new Promise<Response>((resolve) => (release = resolve));
    render(<SiteEditForm site={site()} sshKeyPairs={pairs} defaultAdminPath={null} />);
    fireEvent.click(screen.getByLabelText("新しい鍵ペアを生成する"));
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを新規生成" }));
    expect(await screen.findByRole("button", { name: "鍵ペアを生成中…" })).toBeDisabled();
    release(jsonResponse({ publicKeyLine: "ssh-ed25519 NEW", privateKeyPem: "P" }));
    expect(await screen.findByDisplayValue("ssh-ed25519 NEW")).toBeInTheDocument();
  });
});
