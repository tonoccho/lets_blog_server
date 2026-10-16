import { fireEvent, render, screen } from "@testing-library/react";
import { PersonalPreferencesForm } from "../PersonalPreferencesForm";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

// 自前の ../actions・@/lib/session・@/lib/apiClient・子コンポーネントは差し替えない。差し替えるのは
// Next.js の実行基盤と外部との通信(fetch)だけ(#1833)。
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

const PREFERENCES = "PATCH /api/identity/me/preferences";
let fetchMock: jest.Mock;
const sentBodies = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith("/api/identity/me/preferences") && init?.method === "PATCH")
    .map(([, init]) => JSON.parse(String(init.body)));

beforeEach(() => {
  resetServerRuntime(USER_SESSION);
  fetchMock = installFetch({ [PREFERENCES]: () => jsonResponse({ id: 7 }) });
});

/**
 * issue #1259: 個人設定のタイムゾーンは任意の上書き。timezoneが`null`(未設定)のとき、
 * 「ブラウザに従う(未設定)」が選択された状態で表示されなければならない
 * (従来は`page.tsx`側で"Asia/Tokyo"にフォールバックしていたため、この状態を表現できなかった)。
 */
describe("PersonalPreferencesForm のタイムゾーン欄", () => {
  it("timezoneがnullなら「ブラウザに従う(未設定)」が選択されている", () => {
    render(
      <PersonalPreferencesForm
        locale="ja_JP"
        timezone={null}
        timezoneOptions={["Asia/Tokyo", "America/New_York"]}
      />
    );

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("");
  });

  it("timezoneに値があればその値が選択されている", () => {
    render(
      <PersonalPreferencesForm
        locale="ja_JP"
        timezone="America/New_York"
        timezoneOptions={["Asia/Tokyo", "America/New_York"]}
      />
    );

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.value).toBe("America/New_York");
  });

  it("「ブラウザに従う(未設定)」の選択肢が一覧の先頭にある", () => {
    render(
      <PersonalPreferencesForm
        locale="ja_JP"
        timezone={null}
        timezoneOptions={["Asia/Tokyo"]}
      />
    );

    const select = screen.getByTestId("timezone-select") as HTMLSelectElement;
    expect(select.options[0].value).toBe("");
    expect(select.options[0].textContent).toContain("ブラウザに従う");
  });
});

describe("PersonalPreferencesForm の保存", () => {
  it("送信前は保存ボタンが押せ、結果のメッセージは出ない", () => {
    render(<PersonalPreferencesForm locale="ja_JP" timezone={null} timezoneOptions={["Asia/Tokyo"]} />);

    expect(screen.getByTestId("preferences-save")).toBeEnabled();
    expect(screen.queryByTestId("preferences-success")).not.toBeInTheDocument();
  });

  it("選んだ言語とタイムゾーンを送り、保存できたら成功を表示する", async () => {
    render(<PersonalPreferencesForm locale="ja_JP" timezone={null} timezoneOptions={["Asia/Tokyo", "America/New_York"]} />);

    fireEvent.change(screen.getByLabelText("言語"), { target: { value: "en_US" } });
    fireEvent.change(screen.getByTestId("timezone-select"), { target: { value: "America/New_York" } });
    fireEvent.click(screen.getByTestId("preferences-save"));

    expect(await screen.findByTestId("preferences-success")).toHaveTextContent("保存しました。");
    expect(sentBodies()).toEqual([{ locale: "en_US", timezone: "America/New_York" }]);
  });

  it("保存に失敗したら理由を表示し、成功は出さない", async () => {
    installFetch({ [PREFERENCES]: () => textResponse("不正なタイムゾーンです", 400) });
    render(<PersonalPreferencesForm locale="ja_JP" timezone={null} timezoneOptions={["Asia/Tokyo"]} />);

    fireEvent.click(screen.getByTestId("preferences-save"));

    expect(await screen.findByText("APIエラー (400): 不正なタイムゾーンです")).toBeInTheDocument();
    expect(screen.queryByTestId("preferences-success")).not.toBeInTheDocument();
  });

  it("保存中はボタンを無効にして「保存中…」と表示する", async () => {
    let release: (response: Response) => void = () => {};
    installFetch({ [PREFERENCES]: () => new Promise<Response>((resolve) => (release = resolve)) as unknown as Response });
    render(<PersonalPreferencesForm locale="ja_JP" timezone={null} timezoneOptions={["Asia/Tokyo"]} />);

    fireEvent.click(screen.getByTestId("preferences-save"));

    const pending = await screen.findByRole("button", { name: "保存中…" });
    expect(pending).toBeDisabled();
    release(jsonResponse({ id: 7 }));
    await screen.findByTestId("preferences-success");
  });
});
