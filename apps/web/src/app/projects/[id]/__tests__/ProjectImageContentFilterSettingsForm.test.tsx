import { act, fireEvent, render, screen } from "@testing-library/react";
import { ProjectImageContentFilterSettingsForm } from "../ProjectImageContentFilterSettingsForm";
import { resetServerRuntime } from "@/lib/__tests__/testUtils/serverRuntime";
import { ADMIN_SESSION } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { bodies, failWith, installBackend, jsonResponse } from "./projectCoreRuntime";

/**
 * issue #1085 Requirement 8 / Acceptance Criteria 11:
 * プロジェクト設定画面のコンテンツフィルタ設定に、
 * 「ON のとき抑制語が自動付与される」「ComfyUI 経路のみ有効で、ChatGPT はプロバイダー側
 * モデレーションに依存する」「完全な防止はできない」旨を表示する。
 *
 * このフォーム自体は#532で既に存在し(単体テストは無かった)、issue #1085で説明文の追加が
 * 求められている。既存のキーワードブロックに関する説明文言(#532)はここでは変えない。
 *
 * 本ファイルを変更対象として`scripts/check-changed-coverage.py`がファイル単位でC1/C2分岐
 * カバレッジを見るため、#1085で新たに追加した説明文には分岐が無いものの、同じファイル内の
 * 既存の分岐(保存中表示・エラー表示・成功表示)も合わせてこの機会に押さえる
 * (#1051のComfyUiCheckpointTable.test.tsxと同じ理由)。
 *
 * 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` と `react`(`useActionState`)は差し替えず、
 * Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1837)。
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
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}));
jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }));

const UPDATE = "PUT /api/projects/1/image-content-filter-settings";

function pendingResponse(): { promise: Promise<Response>; release: (r: Response) => void } {
  let release: (r: Response) => void = () => {};
  const promise = new Promise<Response>((resolve) => (release = resolve));
  return { promise, release };
}

async function submit(form: HTMLElement | null) {
  await act(async () => {
    fireEvent.submit(form as HTMLFormElement);
  });
}

beforeEach(() => {
  resetServerRuntime(ADMIN_SESSION);
});

describe("ProjectImageContentFilterSettingsForm", () => {
  beforeEach(() => {
    installBackend({});
  });

  it("ONのとき安全側の抑制語が自動付与されることを説明する", () => {
    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={true}
        blockViolentContent={true}
        blockDiscriminatoryContent={true}
      />
    );

    expect(screen.getByText(/抑制語が自動的に付与されます/)).toBeInTheDocument();
  });

  it("抑制語の付与はComfyUI経路のみ有効であることを説明する", () => {
    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={true}
        blockViolentContent={true}
        blockDiscriminatoryContent={true}
      />
    );

    expect(screen.getByText(/ComfyUI経路にのみ有効/)).toBeInTheDocument();
  });

  it("ChatGPT経路はプロバイダー側モデレーションに依存することを説明する", () => {
    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={true}
        blockViolentContent={true}
        blockDiscriminatoryContent={true}
      />
    );

    expect(screen.getByText(/ChatGPT経路では抑制語は付与されず、プロバイダー側のモデレーションに依存します/)).toBeInTheDocument();
  });

  it("完全な防止はできないことを説明する", () => {
    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={true}
        blockViolentContent={true}
        blockDiscriminatoryContent={true}
      />
    );

    expect(screen.getByText(/完全に防止するものではありません/)).toBeInTheDocument();
  });

  it("nullの設定値ではチェックボックスは既定でONになる(未設定=禁止のまま)", () => {
    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={null}
        blockViolentContent={null}
        blockDiscriminatoryContent={null}
      />
    );

    expect((screen.getByRole("checkbox", { name: "性的な画像の生成を禁止する" }) as HTMLInputElement).checked).toBe(
      true
    );
  });

  it("falseの設定値ではチェックボックスがOFFになる", () => {
    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={false}
        blockViolentContent={false}
        blockDiscriminatoryContent={false}
      />
    );

    expect((screen.getByRole("checkbox", { name: "性的な画像の生成を禁止する" }) as HTMLInputElement).checked).toBe(
      false
    );
    expect((screen.getByRole("checkbox", { name: "暴力的な画像の生成を禁止する" }) as HTMLInputElement).checked).toBe(
      false
    );
    expect(
      (screen.getByRole("checkbox", { name: "差別的な画像の生成を禁止する" }) as HTMLInputElement).checked
    ).toBe(false);
  });

  const form = () => screen.getByRole("button", { name: "保存" }).closest("form");
  const props = { projectId: 1, blockSexualContent: true, blockViolentContent: true, blockDiscriminatoryContent: true };

  it("チェックを外した項目を禁止解除として送り、保存したことを示す", async () => {
    const fetchMock = installBackend({ [UPDATE]: () => jsonResponse({}) });
    render(<ProjectImageContentFilterSettingsForm {...props} />);

    fireEvent.click(screen.getByRole("checkbox", { name: "暴力的な画像の生成を禁止する" }));
    await submit(form());

    expect(await screen.findByText("保存しました。")).toBeInTheDocument();
    expect(bodies(fetchMock, UPDATE)).toEqual([
      { blockSexualContent: true, blockViolentContent: false, blockDiscriminatoryContent: true },
    ]);
  });

  it("保存中はボタンが「保存中…」と表示され無効化される", async () => {
    const { promise, release } = pendingResponse();
    installBackend({ [UPDATE]: () => promise });
    render(<ProjectImageContentFilterSettingsForm {...props} />);

    await submit(form());

    expect(await screen.findByRole("button", { name: "保存中…" })).toBeDisabled();
    await act(async () => {
      release(jsonResponse({}));
    });
    expect(await screen.findByRole("button", { name: "保存" })).toBeEnabled();
  });

  it("保存に失敗するとエラーメッセージを表示し、保存したとは示さない", async () => {
    installBackend({ [UPDATE]: failWith(500, "保存に失敗しました") });
    render(<ProjectImageContentFilterSettingsForm {...props} />);

    await submit(form());

    expect(await screen.findByText("APIエラー (500): 保存に失敗しました")).toBeInTheDocument();
    expect(screen.queryByText("保存しました。")).toBeNull();
  });
});
