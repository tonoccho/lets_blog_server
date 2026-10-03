/**
 * @jest-environment node
 */
jest.mock("server-only", () => ({}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));
const requireAdminSession = jest.fn();
jest.mock("@/lib/session", () => ({
  requireAdminSession: () => requireAdminSession(),
  requireSession: jest.fn(),
}));
const registerSite = jest.fn();
jest.mock("@/lib/apiClient", () => ({ registerSite: (...a: unknown[]) => registerSite(...a) }));
import { registerSiteAction } from "../actions";

function form(extra: Record<string, string> = {}): FormData {
  const fd = new FormData();
  const base: Record<string, string> = {
    name: "N",
    siteKey: "k",
    cmsType: "WORDPRESS",
    baseUrl: "https://e.example.com",
    sshHost: "h",
    sshUser: "u",
    wpPath: "/w",
    sshPrivateKeyPem: "PEM",
    ...extra,
  };
  for (const [k, v] of Object.entries(base)) fd.set(k, v);
  return fd;
}

describe("registerSiteAction の管理画面パス(issue #1533)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    registerSite.mockResolvedValue({ connectionCheckStatus: "SUCCESS" });
  });

  it("adminPath を前後の空白を除いて送る", async () => {
    await registerSiteAction({}, form({ adminPath: "  secret-login " }));
    expect(registerSite.mock.calls[0][0].adminPath).toBe("secret-login");
  });

  it("空欄の adminPath は送らない(グローバル既定を使う)", async () => {
    await registerSiteAction({}, form({ adminPath: "" }));
    expect(registerSite.mock.calls[0][0].adminPath).toBeUndefined();
  });

  it("adminPath のフィールドが無い従来の登録も成功し、送らない", async () => {
    const result = await registerSiteAction({}, form());
    expect(result.success).toBe(true);
    expect(registerSite.mock.calls[0][0].adminPath).toBeUndefined();
  });

  it("バックエンドの 400 メッセージを error として返し、成功にしない", async () => {
    registerSite.mockRejectedValue(new Error("APIエラー (400): adminPath には相対パスを指定してください"));
    const result = await registerSiteAction({}, form({ adminPath: "//evil.example.com" }));
    expect(result.error).toContain("相対パス");
    expect(result.success).toBeUndefined();
  });
});
