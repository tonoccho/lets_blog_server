/**
 * @jest-environment node
 */
// 自前の @/lib/apiClient・@/lib/session・Server Action・コンポーネントは差し替えない。差し替えるのは Next.js の実行基盤と
// 外部との通信(fetch)だけ(#1833)。
jest.mock("server-only", () => ({}));
jest.mock("next/server", () => ({
  ...jest.requireActual("next/server"),
  ...require("@/lib/__tests__/testUtils/serverRuntime").nextServerModule,
}));
jest.mock("next/headers", () => require("@/lib/__tests__/testUtils/serverRuntime").nextHeadersModule);
jest.mock("next-auth/jwt", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthJwtModule);
jest.mock("next-auth", () => require("@/lib/__tests__/testUtils/serverRuntime").nextAuthModule);
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
jest.mock("next/navigation", () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`);
  },
}));

import { restoreBackupAction } from "../actions";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION, USER_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";

const RESTORE = "POST /api/backup/restore";
let fetchMock: jest.Mock;
/** backup-service へ送られたリストアの本文(FormData)。 */
const restores = () =>
  fetchMock.mock.calls
    .filter(([url, init]) => String(url).endsWith("/api/backup/restore") && init?.method === "POST")
    .map(([, init]) => init.body as FormData);

function form(fields: Record<string, string | File>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

const zip = () => new File([new ArrayBuffer(8)], "backup.zip", { type: "application/zip" });

describe("restoreBackupAction", () => {
  beforeEach(() => {
    resetServerRuntime(ADMIN_SESSION);
    fetchMock = installFetch({ [RESTORE]: () => jsonResponse(null) });
  });

  it("ファイルを確認付きで送り、成功を返す。キー不一致の了承が無ければ false を送る", async () => {
    const result = await restoreBackupAction({}, form({ file: zip() }));

    expect(result).toEqual({ success: true });
    const body = restores()[0];
    expect((body.get("file") as File).name).toBe("backup.zip");
    expect(body.get("confirm")).toBe("true");
    expect(body.get("acknowledgeKeyMismatch")).toBe("false");
  });

  it("キー不一致を了承したチェックがあれば true を送る", async () => {
    await restoreBackupAction({}, form({ file: zip(), acknowledgeKeyMismatch: "on" }));

    expect(restores()[0].get("acknowledgeKeyMismatch")).toBe("true");
  });

  it("ファイルが無い・空ならエラーを返し、送らない", async () => {
    expect(await restoreBackupAction({}, new FormData())).toEqual({
      error: "バックアップファイルを選択してください。",
    });
    expect(await restoreBackupAction({}, form({ file: new File([], "empty.zip") }))).toEqual({
      error: "バックアップファイルを選択してください。",
    });
    expect(restores()).toHaveLength(0);
  });

  it("サーバーが拒否したら理由を返す", async () => {
    installFetch({ [RESTORE]: () => textResponse("キーが一致しません", 409) });

    const result = await restoreBackupAction({}, form({ file: zip() }));

    expect(result).toEqual({ error: "APIエラー (409): キーが一致しません" });
  });

  it("通信そのものが失敗してError以外が投げられた場合は、文字列にして返す", async () => {
    installFetch({
      [RESTORE]: () => {
        throw "network down";
      },
    });

    const result = await restoreBackupAction({}, form({ file: zip() }));

    expect(result).toEqual({ error: "network down" });
  });

  it("管理者でなければトップへ送り、リストアしない", async () => {
    resetServerRuntime(USER_SESSION);

    await expect(restoreBackupAction({}, form({ file: zip() }))).rejects.toThrow("NEXT_REDIRECT:/");
    expect(restores()).toHaveLength(0);
  });
});
