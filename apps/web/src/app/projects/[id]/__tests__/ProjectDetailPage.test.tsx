/**
 * @jest-environment node
 */
// プロジェクト詳細ページ `(detail)/page.tsx`。自前の @/lib/session・@/lib/apiClient・@/components/*(Breadcrumb・Tabs)・
// 子コンポーネントはどれも差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1835)。
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
  notFound: () => {
    throw new Error("NEXT_NOT_FOUND");
  },
}));
import { renderToStaticMarkup } from "react-dom/server";
import { jsonResponse, resetServerRuntime, textResponse } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { installDetailBackend, PROJECT, SITE } from "./projectDetailBackend";
import ProjectDetailPage from "../(detail)/page";

type SearchParams = { tab?: string; imageJob?: string; bulkJob?: string; assetUploadJob?: string };

const render = async (searchParams?: SearchParams) =>
  renderToStaticMarkup(
    await ProjectDetailPage({
      params: Promise.resolve({ id: "7" }),
      searchParams: searchParams ? Promise.resolve(searchParams) : undefined,
    }),
  );

/** 各タブにだけ出る見出し。どのタブが開いているかを、描画された中身で確かめる。 */
const TAB_MARKER = {
  overview: "マスター環境",
  settings: "GitHub リポジトリ設定",
  "ai-models": "AIモデル管理",
  members: "ユーザーを追加",
  maintenance: "ガベージコレクション",
} as const;
const markersIn = (html: string) => Object.entries(TAB_MARKER).filter(([, m]) => html.includes(m)).map(([id]) => id);

const member = { userId: 1, email: "a@example.com", displayName: "A", wpRole: "author" };

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
  installDetailBackend();
});

describe("プロジェクト詳細画面 page.tsx(issue #1475: (detail) ルートグループへ移しても最終表示は同じ)", () => {
  it("取得したプロジェクトの名前・slug・パンくず・セクションナビ・タブの見出しを描く", async () => {
    const html = await render();
    expect(html).toContain("<h1");
    expect(html).toContain("サンプル案件");
    expect(html).toContain("sample");
    expect(html).toContain('href="/projects"');
    expect(html).toContain('aria-current="page"');
    expect(html).toContain('href="/projects/7/dashboard"');
  });

  describe("タブの再編(issue #1669)", () => {
    it("タブは「概要」「設定」「AI・アセット」「メンバー」「メンテナンス」の順で並ぶ", async () => {
      const html = await render();
      const labels = ["概要", "設定", "AI・アセット", "メンバー", "メンテナンス"];
      const buttons = [...html.matchAll(/<button[^>]*>([^<]*)<\/button>/g)].map((m) => m[1]).slice(0, 5);
      expect(buttons).toEqual(labels);
    });

    it("既定で開くのは先頭タブ(概要)だけ", async () => {
      expect(markersIn(await render())).toEqual(["overview"]);
    });

    it("メンテナンスタブに一括管理・ガベージコレクション・プロジェクト削除を置き、他のタブには削除ボタンが無い", async () => {
      const maintenance = await render({ tab: "maintenance" });
      expect(maintenance).toContain("一括管理");
      expect(maintenance).toContain("ガベージコレクション");
      expect(maintenance).toContain("プロジェクトを削除");
      for (const tab of ["overview", "settings", "ai-models", "members"]) {
        expect(await render({ tab })).not.toContain("プロジェクトを削除");
      }
    });

    it("設定タブに全般(プロジェクト名・環境同期)・GitHub/APIキー・4つのAI接続を置き、概要には置かない", async () => {
      const settings = await render({ tab: "settings" });
      expect(settings).toContain("名前を保存");
      expect(settings).toContain("環境同期");
      for (const name of ["Ollama", "ComfyUI", "ChatGPT", "Claude"]) {
        expect(settings).toContain(`${name}の接続情報`);
      }
      const overview = await render();
      expect(overview).not.toContain("名前を保存");
      expect(overview).not.toContain("環境同期");
    });

    it("AI・アセットタブにはモデル管理とアセット生成を置き、AI接続は置かない", async () => {
      const ai = await render({ tab: "ai-models" });
      expect(ai).toContain("AIモデル管理");
      expect(ai).toContain("アセット画像生成");
      expect(ai).not.toContain("の接続情報");
    });

    it("設定タブに「モデル設定」の案内カードを置かない(issue #1670)", async () => {
      const settings = await render({ tab: "settings" });
      expect(settings).not.toContain("モデル設定");
      expect(settings).not.toContain("AI・アセット」タブから切り替えられます");
    });

    it("設定タブに SNS 告知・Google Analytics・Google AdSense の設定ページへのリンクを置き、概要タブには置かない(issue #1574 の移動)", async () => {
      const settings = await render({ tab: "settings" });
      expect(settings).toMatch(/href="\/projects\/7\/settings\/sns"[^>]*>SNS 告知の設定</);
      expect(settings).toMatch(/href="\/projects\/7\/settings\/google-analytics"[^>]*>Google Analytics の設定</);
      expect(settings).toMatch(/href="\/projects\/7\/settings\/adsense"[^>]*>Google AdSense の設定</);
      expect(await render()).not.toContain("SNS 告知の設定");
    });

    it.each([
      ["overview", "overview"],
      ["settings", "settings"],
      ["ai-models", "ai-models"],
      ["members", "members"],
      ["maintenance", "maintenance"],
      ["bulk-management", "maintenance"],
      ["garbage-collection", "maintenance"],
      ["unknown-tab", "overview"],
    ])("?tab=%s のとき初期選択のタブは「%s」", async (query, expected) => {
      const html = await render({ tab: query });
      expect(markersIn(html)).toEqual([expected]);
      expect(html).toMatch(/aria-pressed="true"/);
    });
  });

  it("プロジェクトが取得できなければ notFound() になる(loading UI のまま止まらない)", async () => {
    const errorSpy = jest.spyOn(console, "error").mockImplementation(() => undefined);
    installDetailBackend({ "GET /api/projects/:id": () => textResponse("not found", 404) });
    await expect(render()).rejects.toThrow("NEXT_NOT_FOUND");
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it("管理者でなければ表示せず / へ戻す", async () => {
    resetServerRuntime({ user: { id: "u-1", role: "user" } });
    await expect(render()).rejects.toThrow("NEXT_REDIRECT:/");
  });

  describe("環境スロット", () => {
    it("紐付いたサイトの管理画面リンクは、システム設定の管理画面パスで作る(サイト個別の上書きがあればそれを使う)", async () => {
      installDetailBackend(
        { "GET /api/system-settings/site-admin-path": () => jsonResponse({ path: "secret-admin" }) },
        { ...PROJECT, testSite: SITE, productionSite: { ...SITE, id: 2, siteKey: "prod", adminPath: "own-admin" } },
      );
      const html = await render();
      expect(html).toContain("https://s.example.com/secret-admin");
      expect(html).toContain("https://s.example.com/own-admin");
    });
  });

  describe("概要タブのメンバー追加の案内(issue #1069)", () => {
    beforeEach(() => {
      jest.spyOn(console, "error").mockImplementation(() => undefined);
    });
    afterEach(() => jest.restoreAllMocks());

    it.each([
      ["localSite", { localSite: SITE }],
      ["testSite", { testSite: SITE }],
      ["productionSite", { productionSite: SITE }],
    ])("%s が紐付いていてメンバーが0人なら、メンバーが居ない旨とメンバータブへのリンクを示す(issue #1670)", async (_name, bound) => {
      installDetailBackend({}, { ...PROJECT, ...bound });
      const html = await render();
      expect(html).toContain("メンバーがいません");
      expect(html).toMatch(/href="\/projects\/7\?tab=members"[^>]*>メンバータブ</);
      expect(html).not.toContain("先にサイトを紐付け");
    });

    it("メンバータブにはメンバー追加のフォームを置く", async () => {
      installDetailBackend({}, { ...PROJECT, testSite: SITE });
      const html = await render({ tab: "members" });
      expect(html).toContain("ユーザーを追加");
      expect(html).not.toContain("メンバー情報を取得できませんでした");
    });

    it("サイトが1つも紐付いていなければ、先にサイトを紐付けるよう促し、メンバー追加は促さない", async () => {
      const html = await render();
      expect(html).toContain("先にサイトを紐付け");
      expect(html).not.toContain("メンバーがいません");
    });

    it("メンバーが1人以上居れば、どの案内も出さない", async () => {
      installDetailBackend({ "GET /api/projects/:id/users": () => jsonResponse([member]) }, { ...PROJECT, testSite: SITE });
      const html = await render();
      expect(html).not.toContain("メンバーがいません");
      expect(html).not.toContain("先にサイトを紐付け");
      expect(html).not.toContain("取得できませんでした");
    });

    it("サイト未紐付けでもメンバーが居れば、サイト紐付けの案内は出さない", async () => {
      installDetailBackend({ "GET /api/projects/:id/users": () => jsonResponse([member]) });
      expect(await render()).not.toContain("先にサイトを紐付け");
    });

    it("メンバーが居れば、メンバータブの候補ユーザーからは参加済みのユーザーを除く", async () => {
      installDetailBackend({
        "GET /api/projects/:id/users": () => jsonResponse([member]),
        "GET /api/users": () =>
          jsonResponse([
            { id: 1, email: "a@example.com", role: "user", roleNames: [] },
            { id: 2, email: "b@example.com", role: "user", roleNames: [] },
          ]),
      });
      const html = await render({ tab: "members" });
      expect(html).toContain("b@example.com");
      expect(html.match(/a@example\.com/g)).toHaveLength(1); // 一覧に 1 回(候補の選択肢には出ない)
    });

    it.each([
      ["サイト紐付け済み", { testSite: SITE }],
      ["サイト未紐付け", {}],
    ])("メンバー一覧の取得に失敗したら(%s)、0人の案内ではなく取得できなかった旨を出す", async (_name, bound) => {
      installDetailBackend({ "GET /api/projects/:id/users": () => textResponse("down", 503) }, { ...PROJECT, ...bound });
      const overview = await render();
      expect(overview).toContain("メンバー情報を取得できませんでした");
      expect(overview).not.toContain("メンバーがいません");
      expect(overview).not.toContain("先にサイトを紐付け");
      const members = await render({ tab: "members" });
      expect(members).toContain("メンバー情報を取得できませんでした");
      expect(members).not.toContain("ユーザーを追加");
      expect(console.error).toHaveBeenCalled();
    });
  });

  describe("?imageJob (issue #1408)", () => {
    it.each([
      ["42", true],
      ["0", false],
      ["-3", false],
      ["abc", false],
      ["4.5", false],
      ["", false],
    ])("imageJob=%s のとき、アセット画像パネルがジョブを開いた状態か: %s", async (value, opened) => {
      const html = await render({ tab: "ai-models", imageJob: value });
      expect(html.includes("閉じる")).toBe(opened);
    });

    it("imageJob の指定がなければパネルは閉じたまま", async () => {
      expect(await render({ tab: "ai-models" })).not.toContain("閉じる");
    });
  });

  describe("?bulkJob (issue #1702)", () => {
    const managed = { ...PROJECT, testSite: { ...SITE, managedWordpress: true } };
    beforeEach(() => installDetailBackend({}, managed));

    it.each([
      ["42", "ジョブ #42"],
      ["0", null],
      ["-3", null],
      ["abc", null],
      ["4.5", null],
      ["", null],
    ])("bulkJob=%s のとき、一括管理パネルが出すジョブの表示は %s", async (value, expected) => {
      const html = await render({ tab: "maintenance", bulkJob: value });
      if (expected) expect(html).toContain(expected);
      else expect(html).not.toContain("全環境インストールの結果");
    });

    it("bulkJob の指定がなければジョブの結果を出さない", async () => {
      expect(await render({ tab: "maintenance" })).not.toContain("全環境インストールの結果");
    });
  });

  describe("?assetUploadJob (issue #1764)", () => {
    it.each([
      ["42", true],
      ["0", false],
      ["-3", false],
      ["abc", false],
      ["4.5", false],
      ["", false],
    ])("assetUploadJob=%s のとき、アセット画像パネルがジョブを開いた状態か: %s", async (value, opened) => {
      const html = await render({ tab: "ai-models", assetUploadJob: value });
      expect(html.includes("閉じる")).toBe(opened);
    });
  });
});
