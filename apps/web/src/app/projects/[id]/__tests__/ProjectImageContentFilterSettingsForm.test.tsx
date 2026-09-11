import { render, screen } from "@testing-library/react";
import { ProjectImageContentFilterSettingsForm } from "../ProjectImageContentFilterSettingsForm";
import { updateImageContentFilterSettingsAction } from "../actions";

const actualReact = jest.requireActual("react");
const useActionStateMock = jest.fn(actualReact.useActionState);
jest.mock("react", () => ({
  ...jest.requireActual("react"),
  useActionState: (...args: unknown[]) => useActionStateMock(...args),
}));

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
 */
jest.mock("../actions", () => ({
  updateImageContentFilterSettingsAction: jest.fn(),
}));

describe("ProjectImageContentFilterSettingsForm", () => {
  beforeEach(() => {
    (updateImageContentFilterSettingsAction as jest.Mock).mockReset();
    useActionStateMock.mockClear();
    useActionStateMock.mockImplementation(actualReact.useActionState);
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

  it("保存中はボタンが「保存中…」と表示され無効化される", () => {
    useActionStateMock.mockReturnValue([{}, jest.fn(), true]);

    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={true}
        blockViolentContent={true}
        blockDiscriminatoryContent={true}
      />
    );

    const button = screen.getByRole("button", { name: "保存中…" });
    expect(button).toBeDisabled();
  });

  it("保存に失敗するとエラーメッセージを表示する", () => {
    useActionStateMock.mockReturnValue([{ error: "保存に失敗しました" }, jest.fn(), false]);

    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={true}
        blockViolentContent={true}
        blockDiscriminatoryContent={true}
      />
    );

    expect(screen.getByText("保存に失敗しました")).toBeInTheDocument();
  });

  it("保存に成功すると成功メッセージを表示する", () => {
    useActionStateMock.mockReturnValue([{ success: true }, jest.fn(), false]);

    render(
      <ProjectImageContentFilterSettingsForm
        projectId={1}
        blockSexualContent={true}
        blockViolentContent={true}
        blockDiscriminatoryContent={true}
      />
    );

    expect(screen.getByText("保存しました。")).toBeInTheDocument();
  });
});
