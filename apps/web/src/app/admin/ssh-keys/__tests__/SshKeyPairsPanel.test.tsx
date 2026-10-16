import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { SshKeyPairsPanel } from "../SshKeyPairsPanel";
import type { SavedSshKeyPair } from "@/lib/apiClient";

/**
 * issue #1236: `new Date(keyPair.createdAt).toLocaleString("ja-JP")` を直接呼んでいたため、
 * オフセット無しの日時文字列(バックエンドのLocalDateTime由来)が実行環境のTZでパースされ、
 * SSRとブラウザで表示がずれ得た。共有ヘルパ`formatDateTime`を経由するよう変更する。
 *
 * issue #1362: 個人設定TZ(`personalTimeZone`)を受け取る経路を追加した。個人設定TZが
 * あるときはSSR/クライアントで同じ文字列になるためgate不要。無いときは、マウント後に
 * しか解決できないブラウザTZを使うため、マウント前は固定プレースホルダーを描く
 * (前例: ThemeSwitcher.tsx:23-58のmountedフラグ方式)。
 *
 * issue #1051: 生成フォームの<form>にmethod="post"を明示した(JS無効時のネイティブGET
 * フォールバックで名前・コメントがURLへ漏れるのを防ぐ)。それ以外の挙動(生成・削除)は
 * 既存のまま変わっていないため、このテストは変更点(method="post")の確認と、
 * 主要な分岐(生成成功/失敗、削除確認ダイアログのキャンセル/削除)を最小限で押さえる。
 *
 * 自前の ../actions・@/components/ViewerDateTime・@/lib/* は差し替えない(#1833)。
 */
// 自前の @/lib/apiClient・@/lib/session・ViewerDateTimeは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
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

const CREATE = "POST /api/ssh-key-pairs";
let fetchMock: jest.Mock;
const requests = (method: string) =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).includes("/api/ssh-key-pairs") && init?.method === method)
    .map(([url, init]) => ({ url: String(url), body: init.body ? JSON.parse(String(init.body)) : null }));

function keyPair(overrides: Partial<SavedSshKeyPair> = {}): SavedSshKeyPair {
  return {
    id: 1,
    name: "production-deploy",
    comment: null,
    publicKeyLine: "ssh-ed25519 AAAA... production-deploy",
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("SshKeyPairsPanel", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [CREATE]: () => jsonResponse(null), "DELETE /api/ssh-key-pairs/:id": () => jsonResponse(null) });
    window.confirm = jest.fn();
  });

  it("生成フォームはmethod=\"post\"を持つ", () => {
    const { container } = render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    const form = container.querySelector("form");
    expect(form?.getAttribute("method")).toBe("post");
  });

  it("保存済みの鍵ペアが無ければその旨を表示する", () => {
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    expect(screen.getByText("保存済みのSSH鍵ペアはありません。")).toBeInTheDocument();
  });

  it("生成に失敗するとエラーを表示する", async () => {
    installFetch({ [CREATE]: () => textResponse("名前が既に使用されています", 409) });
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    fireEvent.change(screen.getByPlaceholderText("production-deploy"), { target: { value: "dup" } });
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));

    await waitFor(() => {
      expect(screen.getByText("APIエラー (409): 名前が既に使用されています")).toBeInTheDocument();
    });
  });

  it("生成に成功すると秘密鍵の表示欄が現れる", async () => {
    installFetch({
      [CREATE]: () =>
        jsonResponse({
          ...keyPair({ name: "new-pair" }),
          privateKeyPem: "-----BEGIN OPENSSH PRIVATE KEY-----",
        }),
    });
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    fireEvent.change(screen.getByPlaceholderText("production-deploy"), { target: { value: "new-pair" } });
    fireEvent.change(screen.getByPlaceholderText("本番サーバーデプロイ用"), { target: { value: "メモ" } });
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));

    await waitFor(() => {
      expect(screen.getByText(/を生成しました。/)).toBeInTheDocument();
    });
    expect(screen.getByDisplayValue("-----BEGIN OPENSSH PRIVATE KEY-----")).toBeInTheDocument();
    expect(screen.getByDisplayValue("ssh-ed25519 AAAA... production-deploy")).toBeInTheDocument();
    // 生成後は入力欄を空に戻す。
    expect((screen.getByPlaceholderText("production-deploy") as HTMLInputElement).value).toBe("");
    expect((screen.getByPlaceholderText("本番サーバーデプロイ用") as HTMLInputElement).value).toBe("");

    fireEvent.click(screen.getByRole("button", { name: "閉じる" }));
    expect(screen.queryByText(/を生成しました。/)).not.toBeInTheDocument();
  });

  it("削除確認をキャンセルすると削除アクションを呼ばない", () => {
    (window.confirm as jest.Mock).mockReturnValue(false);
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    expect(requests("DELETE")).toHaveLength(0);
  });

  it("削除確認を承認すると削除アクションを呼び、失敗時は行が残りエラーを表示する(issue #1361 AC3)", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    installFetch({ "DELETE /api/ssh-key-pairs/1": () => textResponse("削除に失敗しました", 500) });
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(screen.getByText("APIエラー (500): 削除に失敗しました")).toBeInTheDocument();
    });
    expect(screen.getByText("production-deploy")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "削除" })).toBeInTheDocument();
  });

  /**
   * issue #1361: サーバコンポーネントのprops(`keyPairs`)のみに一覧が依存しており、
   * `deleteSshKeyPairAction`が呼び出す`revalidatePath`によるサーバ再描画がマウント済みの
   * クライアントコンポーネントへ届かない(あるいは届くタイミングが不定)ケースがあった。
   * サーバの再描画を待たずに、削除成功が確定した時点でローカル状態から行を消す。
   */
  it("削除に成功すると手動リロードなしで一覧から行が消える(issue #1361 AC1)", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);

    expect(screen.getByText("production-deploy")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    await waitFor(() => {
      expect(requests("DELETE").map((r) => r.url)).toEqual([expect.stringMatching(/\/api\/ssh-key-pairs\/1$/)]);
      expect(screen.queryByText("production-deploy")).not.toBeInTheDocument();
    });
    expect(screen.getByText("保存済みのSSH鍵ペアはありません。")).toBeInTheDocument();
  });

  it("削除に成功した鍵ペアだけが一覧から消え、他の行は残る(issue #1361)", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    const other = keyPair({ id: 2, name: "other-pair" });
    render(<SshKeyPairsPanel keyPairs={[keyPair(), other]} personalTimeZone={null} />);

    fireEvent.click(screen.getAllByRole("button", { name: "削除" })[0]);

    await waitFor(() => {
      expect(requests("DELETE").map((r) => r.url)).toEqual([expect.stringMatching(/\/api\/ssh-key-pairs\/1$/)]);
      expect(screen.queryByText("production-deploy")).not.toBeInTheDocument();
    });
    expect(screen.getByText("other-pair")).toBeInTheDocument();
  });

  it("個人設定TZが設定されているとき、作成日時を個人設定TZで表示する(issue #1362、#1367)", () => {
    const pair = keyPair({ createdAt: "2026-09-08T20:03:35" });
    render(<SshKeyPairsPanel keyPairs={[pair]} personalTimeZone="Asia/Tokyo" />);

    // 個人設定TZ(Asia/Tokyo)で整形する。日時はUTCとして読むので 9 時間進む。
    expect(screen.getByText("2026/9/9 5:03:35")).toBeInTheDocument();
  });

  // 「マウント前は固定プレースホルダーを表示する」は SshKeyPairsPanel.mountGate.test.tsx で
  // 検証する(このファイルで検証しない理由は同ファイルの先頭コメント参照)。

  it("個人設定TZが未設定のとき、作成日時をブラウザのTZで表示する(issue #1362、#1367)", () => {
    const pair = keyPair({ createdAt: "2026-09-08T20:03:35" });
    render(<SshKeyPairsPanel keyPairs={[pair]} personalTimeZone={null} />);

    // マウント後に解決したブラウザのTZ(テストはUTC)で整形する。
    expect(screen.getByText("2026/9/8 20:03:35")).toBeInTheDocument();
  });

  /**
   * issue #1413 の対の観点。マウント前はボタンを `disabled` にするが、
   * マウント後(=ハイドレーション完了後)は従来どおり押せなければならない。
   * この2件が揃って初めて「押せない時間帯だけを塞いだ」と言える
   * (マウント前の確認は `SshKeyPairsPanel.mountGate.test.tsx` にある)。
   */
  it("マウント後は生成ボタンを押せる(issue #1413で塞ぐのはハイドレーション前だけ)", () => {
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    expect(screen.getByRole("button", { name: "SSH鍵ペアを生成" })).toBeEnabled();
  });

  it("生成の応答が空(本文なし)でも、秘密鍵の欄は出さずに入力欄を空に戻す", async () => {
    fetchMock = installFetch({ [CREATE]: () => ({ ...jsonResponse(null), status: 204, text: async () => "" }) as unknown as Response });
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    fireEvent.change(screen.getByPlaceholderText("production-deploy"), { target: { value: "new-pair" } });
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));

    await waitFor(() => {
      expect((screen.getByPlaceholderText("production-deploy") as HTMLInputElement).value).toBe("");
    });
    expect(screen.queryByText(/を生成しました。/)).not.toBeInTheDocument();
    expect(requests("POST")[0].body).toEqual({ name: "new-pair", comment: null });
  });

  it("生成中はボタンを無効にして「生成中…」と表示する", async () => {
    let release: (response: Response) => void = () => {};
    installFetch({ [CREATE]: () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response });
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);

    fireEvent.change(screen.getByPlaceholderText("production-deploy"), { target: { value: "new-pair" } });
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));

    const pending = await screen.findByRole("button", { name: "生成中…" });
    expect(pending).toBeDisabled();
    release(jsonResponse(keyPair({ name: "new-pair" })));
    await screen.findByRole("button", { name: "SSH鍵ペアを生成" });
  });

  it("削除中は、その行のボタンを無効にして「削除中…」と表示する。コメントがあれば名前の下に出す", async () => {
    (window.confirm as jest.Mock).mockReturnValue(true);
    let release: (response: Response) => void = () => {};
    installFetch({ "DELETE /api/ssh-key-pairs/1": () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response });
    render(<SshKeyPairsPanel keyPairs={[keyPair({ comment: "本番用" })]} personalTimeZone={null} />);
    expect(screen.getByText("本番用")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "削除" }));

    const pending = await screen.findByRole("button", { name: "削除中…" });
    expect(pending).toBeDisabled();
    release(jsonResponse(null));
    await waitFor(() => expect(screen.queryByText("production-deploy")).not.toBeInTheDocument());
  });

  it("公開鍵の欄にフォーカスすると全体を選択する(コピーしやすくする)", () => {
    render(<SshKeyPairsPanel keyPairs={[keyPair()]} personalTimeZone={null} />);
    const textarea = screen.getByDisplayValue("ssh-ed25519 AAAA... production-deploy") as HTMLTextAreaElement;

    fireEvent.focus(textarea);

    expect(textarea.selectionStart).toBe(0);
    expect(textarea.selectionEnd).toBe(textarea.value.length);
  });

  it("生成直後の公開鍵・秘密鍵の欄にフォーカスしても全体を選択する", async () => {
    installFetch({
      [CREATE]: () => jsonResponse({ ...keyPair({ name: "new-pair", publicKeyLine: "ssh-ed25519 NEW" }), privateKeyPem: "PRIVATE-KEY" }),
    });
    render(<SshKeyPairsPanel keyPairs={[]} personalTimeZone={null} />);
    fireEvent.change(screen.getByPlaceholderText("production-deploy"), { target: { value: "new-pair" } });
    fireEvent.click(screen.getByRole("button", { name: "SSH鍵ペアを生成" }));
    const publicKey = (await screen.findByDisplayValue("ssh-ed25519 NEW")) as HTMLTextAreaElement;
    const privateKey = screen.getByDisplayValue("PRIVATE-KEY") as HTMLTextAreaElement;

    fireEvent.focus(publicKey);
    fireEvent.focus(privateKey);

    expect(publicKey.selectionEnd).toBe(publicKey.value.length);
    expect(privateKey.selectionEnd).toBe(privateKey.value.length);
  });
});
