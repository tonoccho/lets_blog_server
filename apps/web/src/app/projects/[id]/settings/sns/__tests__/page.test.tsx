/**
 * @jest-environment node
 */
/**
 * issue #1574: SNS 告知の設定ページ(サーバーコンポーネント)。接続状態の取得に失敗しても画面全体は落とさず、
 * 欄の側で「取得できない」と示す。
 * 自前の @/lib/session・@/lib/apiClient・Breadcrumb・各 SNS の欄は差し替えない。差し替えるのは
 * Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。欄へ何を渡したかは、描画された文言で確かめる。
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
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
import { renderToStaticMarkup } from "react-dom/server";
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import ProjectSnsSettingsPage from "../page";

const templatesView = {
  publishTemplate: "【新着】{title} {url}",
  pvTemplate: "",
  send: { state: "SENT", error: null, at: null },
};

const pvView = {
  addable: true,
  reason: null,
  rules: [{ id: "r1", period: "daily", threshold: 100 }],
  send: { state: "SENT", error: null, at: null },
};

const view = { connectable: true, reason: null, siteName: "本番", status: null, log: null };

type Respond = () => Response | Promise<Response>;
const DOWN = () => textResponse("Bad Gateway", 502);
let routes: Record<string, Respond>;
let facebookPagesRequests: string[];

function install() {
  resetServerRuntime(ADMIN_SESSION);
  facebookPagesRequests = [];
  routes = {
    project: () => jsonResponse({ id: 5, name: "テストプロジェクト" }),
    x: () => jsonResponse(view),
    threads: () => jsonResponse(view),
    facebook: () => jsonResponse(view),
    linkedin: () => jsonResponse(view),
    hatena: () => jsonResponse(view),
    pv: () => jsonResponse(pvView),
    templates: () => jsonResponse(templatesView),
    facebookPages: () => jsonResponse({ projectId: 5, pages: [] }),
  };
  installFetch({
    "GET /api/projects/:id": () => routes.project(),
    "GET /api/projects/:id/sns/x": () => routes.x(),
    "GET /api/projects/:id/sns/threads": () => routes.threads(),
    "GET /api/projects/:id/sns/facebook": () => routes.facebook(),
    "GET /api/projects/:id/sns/linkedin": () => routes.linkedin(),
    "GET /api/projects/:id/sns/hatena": () => routes.hatena(),
    "GET /api/projects/:id/sns/pv": () => routes.pv(),
    "GET /api/projects/:id/sns/templates": () => routes.templates(),
    "GET /api/projects/:id/sns/facebook/pages?state=:state": (url) => {
      facebookPagesRequests.push(new URL(url).searchParams.get("state") ?? "");
      return routes.facebookPages();
    },
  });
}

async function renderPage(searchParams: { connected?: string; error?: string; sns?: string; facebookState?: string } = {}) {
  return renderToStaticMarkup(
    await ProjectSnsSettingsPage({
      params: Promise.resolve({ id: "5" }),
      searchParams: Promise.resolve(searchParams),
    })
  );
}

const CONNECTED = {
  x: "X アカウントを接続しました。",
  threads: "Threads アカウントを接続しました。",
  facebook: "Facebook ページを接続しました。",
  linkedin: "LinkedIn アカウントを接続しました。",
  hatena: "はてなブックマーク アカウントを接続しました。",
};
const failure = (reason: string) => `接続に失敗しました: ${reason}`;
const count = (html: string, text: string) => html.split(text).length - 1;

describe("ProjectSnsSettingsPage", () => {
  const originalUrl = process.env.NEXTAUTH_URL;
  let errorSpy: jest.SpyInstance;

  beforeEach(() => {
    process.env.NEXTAUTH_URL = "https://localhost";
    errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
    install();
  });
  afterEach(() => errorSpy.mockRestore());
  afterAll(() => {
    process.env.NEXTAUTH_URL = originalUrl;
  });

  it("取得した接続状態と、各 SNS 用のコールバックURLを欄へ渡す", async () => {
    const html = await renderPage({ connected: "1" });

    expect(html).toContain("テストプロジェクト — SNS 告知設定");
    for (const sns of ["x", "threads", "facebook", "linkedin", "hatena"]) {
      expect(html).toContain(`https://localhost/connect/${sns}/callback`);
    }
    expect(html).toContain("本番サイト: 本番");
    expect(html).toContain(CONNECTED.x);
  });

  it("失敗の理由をバナーとして渡す", async () => {
    const html = await renderPage({ error: "invalid_state" });

    expect(count(html, failure("invalid_state"))).toBe(1);
    expect(html).not.toContain(CONNECTED.x);
  });

  it("接続状態を取得できなくても画面は描き、欄は「取得できない」と示す", async () => {
    routes.x = DOWN;

    const html = await renderPage();

    expect(html).toContain("SNS 告知");
    expect(html).toContain("X の接続状態:");
    expect(html).toContain("取得できない");
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it("取得した PV 達成ルールを PV 欄へ渡す(issue #1578)", async () => {
    const html = await renderPage();

    expect(html).toContain("100");
    expect(html).not.toContain("PV 達成ルールを取得できない");
  });

  it("PV 達成ルールを取得できなくても画面は描き、PV 欄は取得できないと示す(issue #1578)", async () => {
    routes.pv = DOWN;

    const html = await renderPage();

    expect(html).toContain("SNS 告知");
    expect(html).toContain("PV 達成ルールを取得できない");
  });

  it("取得した告知文テンプレートをテンプレート欄へ渡す(issue #1583)", async () => {
    const html = await renderPage();

    expect(html).toContain("【新着】{title} {url}");
    expect(html).not.toContain("告知文テンプレートを取得できない");
  });

  it("告知文テンプレートを取得できなくても画面は描き、テンプレート欄は取得できないと示す(issue #1583)", async () => {
    routes.templates = DOWN;

    const html = await renderPage();

    expect(html).toContain("SNS 告知");
    expect(html).toContain("告知文テンプレートを取得できない");
  });

  it("プロジェクトが取得できなければ notFound になる", async () => {
    routes.project = () => textResponse("not found", 404);

    await expect(renderPage()).rejects.toThrow("NEXT_NOT_FOUND");
  });

  it("管理者でなければ / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(renderPage()).rejects.toThrow("NEXT_REDIRECT:/");
  });

  describe.each([
    ["Threads", "threads", CONNECTED.threads, "access_denied", "issue #1579"],
    ["LinkedIn", "linkedin", CONNECTED.linkedin, "access_denied", "issue #1581"],
    ["はてなブックマーク", "hatena", CONNECTED.hatena, "user_refused", "issue #1582"],
  ] as const)("%s の欄(%s)", (label, key, banner, reason, issue) => {
    it(`${label} の接続完了(connected=${key})は ${label} 欄にだけバナーを出す(${issue})`, async () => {
      const html = await renderPage({ connected: key });

      expect(html).toContain(banner);
      for (const [other, text] of Object.entries(CONNECTED)) {
        if (other !== key) expect(html).not.toContain(text);
      }
    });

    it(`${label} の失敗(sns=${key})は ${label} 欄にだけ理由を出し、X 欄には出さない`, async () => {
      const html = await renderPage({ error: reason, sns: key });

      expect(count(html, failure(reason))).toBe(1);
    });

    it(`X の失敗は ${label} 欄には出さない`, async () => {
      const html = await renderPage({ error: "invalid_state" });

      expect(count(html, failure("invalid_state"))).toBe(1);
    });

    it(`${label} の接続状態を取得できなくても画面は描き、${label} 欄は取得できないと示す`, async () => {
      routes[key] = DOWN;

      const html = await renderPage();

      expect(html).toContain("SNS 告知");
      expect(html).toContain(`https://localhost/connect/${key}/callback`);
      expect(count(html, "取得できない")).toBeGreaterThan(0);
    });
  });

  describe("Facebook ページの欄(issue #1580)", () => {
    it("Facebook の接続完了(connected=facebook)は Facebook 欄にだけバナーを出す", async () => {
      const html = await renderPage({ connected: "facebook" });

      expect(html).toContain(CONNECTED.facebook);
      expect(html).not.toContain(CONNECTED.x);
      expect(html).not.toContain(CONNECTED.threads);
      expect(facebookPagesRequests).toEqual([]);
    });

    it("Facebook の失敗(sns=facebook)は Facebook 欄にだけ理由を出す", async () => {
      const html = await renderPage({ error: "access_denied", sns: "facebook" });

      expect(count(html, failure("access_denied"))).toBe(1);
    });

    it("認可から戻った(facebookState)ときは、選べるページを取得して Facebook 欄へ渡す", async () => {
      routes.facebookPages = () => jsonResponse({ projectId: 5, pages: [{ id: "100", name: "ページA" }] });

      const html = await renderPage({ facebookState: "5.abc" });

      expect(facebookPagesRequests).toEqual(["5.abc"]);
      expect(html).toContain("投稿先のページを選んでください");
      expect(html).toContain("ページA");
    });

    it("選べるページが 1 つも無ければ、その旨を示す", async () => {
      const html = await renderPage({ facebookState: "5.abc" });

      expect(html).toContain("管理しているページがありません。");
    });

    it("選べるページを取得できなければ(期限切れ等)、選択欄は出さず理由を Facebook 欄に出す", async () => {
      routes.facebookPages = () => textResponse("認可の有効期限が切れました", 400);

      const html = await renderPage({ facebookState: "5.abc" });

      expect(html).not.toContain("投稿先のページを選んでください");
      expect(html).toContain(failure("APIエラー (400): 認可の有効期限が切れました"));
    });

    it("Errorでない失敗も文字列にして Facebook 欄に出す", async () => {
      const inner = global.fetch as jest.Mock;
      global.fetch = jest.fn(async (input: unknown, init?: RequestInit) => {
        if (String(input).includes("/sns/facebook/pages")) throw "boom";
        return inner(input, init);
      }) as unknown as typeof fetch;

      const html = await renderPage({ facebookState: "5.abc" });

      expect(html).toContain(failure("boom"));
    });
  });
});
