import { execFileSync } from "child_process";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import {
  lastCreatedWebviewPanel,
  resetMocks,
  setWarningResponse,
  setWorkspaceFolders,
  shownInformations,
} from "../__mocks__/vscode";
import { PlanPanel } from "../planPanel";

jest.mock("../apiClient", () => ({
  getIssueDescription: jest.fn(async () => "本文"),
  assignIssue: jest.fn(async () => undefined),
}));
jest.mock("../config", () => ({
  requireAccessToken: jest.fn(async () => "token"),
  getActor: jest.fn(async () => ({
    id: 1,
    email: "a@example.test",
    name: "a",
  })),
  getProjectId: jest.fn(() => 42),
}));

import * as api from "../apiClient";

const assignIssueMock = api.assignIssue as unknown as jest.Mock;

/**
 * PlanPanelの `approveAndScaffold`(issue #1062)。
 *
 * webviews/plan.js 側の入口検証を迂回して不正なスラッグを直接送っても、
 * `createArticleScaffold` 自身の検証で例外が投げられ、ファイルは作られないことを確認する
 * (多層防御)。正常系(regression)も併せて確認する。
 */

const ISSUE = {
  number: 1,
  title: "テストIssue",
  htmlUrl: "https://example.test/issues/1",
  state: "open",
};

let workspaceRoot: string;

function createContext(): unknown {
  return { extensionUri: `file://${path.resolve(__dirname, "..", "..")}` };
}

async function send(message: unknown): Promise<void> {
  lastCreatedWebviewPanel?.webview.postMessageToExtension(message);
  // gitの子プロセスを待つため、結果メッセージ(成功か失敗)が出るまで待つ。
  const deadline = Date.now() + 10_000;
  while (
    Date.now() < deadline &&
    !posted().some(
      (m) => m.command === "error" || m.command === "scaffoldCreated",
    )
  ) {
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}

function posted(): { command: string; payload: unknown }[] {
  return (lastCreatedWebviewPanel?.webview.posted ?? []) as {
    command: string;
    payload: unknown;
  }[];
}

function articlesDir(): string {
  return path.join(workspaceRoot, "articles");
}

function git(...args: string[]): string {
  return execFileSync("git", args, {
    cwd: workspaceRoot,
    encoding: "utf-8",
  }).trim();
}

/** 記事の雛形はブランチを切ってコミットするため(issue #1335)、ワークスペースは初期コミット済みのgitリポジトリにする。 */
function initGitWorkspace(): void {
  git("init", "-q", "-b", "main");
  git("config", "user.name", "tester");
  git("config", "user.email", "tester@example.test");
  fs.writeFileSync(path.join(workspaceRoot, "README.md"), "hello");
  git("add", ".");
  git("commit", "-q", "-m", "init");
}

beforeEach(() => {
  workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), "letsblog-planpanel-"));
  initGitWorkspace();
  setWorkspaceFolders([{ uri: { fsPath: workspaceRoot } }]);
  assignIssueMock.mockClear();
  PlanPanel.createOrShow(createContext() as never);
});

afterEach(() => {
  lastCreatedWebviewPanel?.fireDispose();
  resetMocks();
  fs.rmSync(workspaceRoot, { recursive: true, force: true });
});

describe("approveAndScaffold", () => {
  it("入口検証を迂回した不正なスラッグは createArticleScaffold の例外で拒否され、何も作られない", async () => {
    await send({
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "../../../evil",
        categories: [],
        tags: [],
      },
    });

    expect(fs.existsSync(articlesDir())).toBe(false);
    const errors = posted().filter((m) => m.command === "error");
    expect(errors).toHaveLength(1);
    expect((errors[0].payload as { error: string }).error).toContain(
      "スラッグは半角英数字とハイフンのみで入力してください",
    );
    expect(assignIssueMock).not.toHaveBeenCalled();
  });

  it("正常なスラッグは従来どおりスキャフォールドを生成する(回帰なし)", async () => {
    await send({
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "my-article-01",
        categories: [],
        tags: [],
      },
    });

    const articleMd = path.join(articlesDir(), "my-article-01", "article.md");
    expect(fs.existsSync(articleMd)).toBe(true);
    expect(posted().some((m) => m.command === "scaffoldCreated")).toBe(true);
    expect(assignIssueMock).toHaveBeenCalledWith(
      "token",
      expect.anything(),
      42,
      1,
    );
  });

  it("雛形を記事用ブランチへコミットする(issue #1335)", async () => {
    await send({
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "my-article-01",
        categories: [],
        tags: [],
      },
    });

    expect(git("branch", "--show-current")).toBe("article/1-my-article-01");
    expect(git("status", "--porcelain")).toBe("");
  });

  it("gitリポジトリでないワークスペースでは理由を返し、何も作らない", async () => {
    fs.rmSync(path.join(workspaceRoot, ".git"), {
      recursive: true,
      force: true,
    });
    await send({
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "my-article-01",
        categories: [],
        tags: [],
      },
    });

    expect(fs.existsSync(articlesDir())).toBe(false);
    const errors = posted().filter((m) => m.command === "error");
    expect((errors[0].payload as { error: string }).error).toContain(
      "gitリポジトリではありません",
    );
    expect(assignIssueMock).not.toHaveBeenCalled();
  });

  it("同名ブランチが既にあり「切り替える」を選ぶと、そのブランチで続行する", async () => {
    git("branch", "article/1-my-article-01");
    setWarningResponse("切り替える");
    await send({
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "my-article-01",
        categories: [],
        tags: [],
      },
    });
    expect(git("branch", "--show-current")).toBe("article/1-my-article-01");
    expect(posted().some((m) => m.command === "scaffoldCreated")).toBe(true);
  });

  it("同名ブランチが既にあり「中断」を選ぶと、何も変化しない", async () => {
    git("branch", "article/1-my-article-01");
    setWarningResponse("中断");
    await send({
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "my-article-01",
        categories: [],
        tags: [],
      },
    });
    expect(git("branch", "--show-current")).toBe("main");
    expect(fs.existsSync(articlesDir())).toBe(false);
  });

  it("上書き確認で拒否された場合はキャンセルとして扱う", async () => {
    fs.mkdirSync(path.join(articlesDir(), "my-article-01"), {
      recursive: true,
    });
    fs.writeFileSync(
      path.join(articlesDir(), "my-article-01", "keep.txt"),
      "x",
    );
    git("add", ".");
    git("commit", "-q", "-m", "existing");
    setWarningResponse("No");
    await send({
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "my-article-01",
        categories: [],
        tags: [],
      },
    });
    const errors = posted().filter((m) => m.command === "error");
    expect((errors[0].payload as { error: string }).error).toContain(
      "キャンセル",
    );
    // #1510: 元のブランチへ戻り、空の記事用ブランチが残らない。
    expect(git("branch", "--show-current")).toBe("main");
    expect(git("branch", "--list", "article/*")).toBe("");
  });

  it("同一内容の再生成ではコミットが無くてもgitエラーを出さず、その旨を通知して続行する(#1510)", async () => {
    // front matter の公開予定日時は現在時刻から決まるため、Dateだけ固定して同一内容にする。
    jest.useFakeTimers({
      now: new Date("2030-01-01T00:00:00Z"),
      doNotFake: [
        "setTimeout",
        "clearTimeout",
        "setInterval",
        "clearInterval",
        "setImmediate",
        "clearImmediate",
        "nextTick",
        "queueMicrotask",
        "performance",
      ],
    });
    const message = {
      command: "approveAndScaffold",
      issue: ISSUE,
      metadata: {
        title: "タイトル",
        slug: "my-article-01",
        categories: [],
        tags: [],
      },
    };
    await send(message);
    // 生成済みの記事を main に取り込み、同じ内容で上書きできる状態にする。
    git("switch", "-q", "main");
    git("merge", "-q", "--ff-only", "article/1-my-article-01");
    git("branch", "-q", "-d", "article/1-my-article-01");
    (lastCreatedWebviewPanel?.webview.posted as unknown[]).length = 0;
    shownInformations.length = 0;
    setWarningResponse("Yes");
    await send(message);
    expect(posted().filter((m) => m.command === "error")).toEqual([]);
    expect(posted().some((m) => m.command === "scaffoldCreated")).toBe(true);
    expect(shownInformations.join("\n")).toContain(
      "コミットする変更はありません",
    );
    jest.useRealTimers();
  });
});
