import { render, screen } from "@testing-library/react";
import type { CustomTag } from "@/lib/apiClient";
import { ProjectCustomTagManager } from "../ProjectCustomTagManager";

jest.mock("@/app/custom-tags/CustomTagGenerationForm", () => ({
  CustomTagGenerationForm: () => <div>GENERATION_FORM</div>,
}));
jest.mock("@/app/custom-tags/CustomTagGenerationResult", () => ({
  CustomTagGenerationResult: () => <div>GENERATION_RESULT</div>,
}));
jest.mock("@/app/custom-tags/CustomTagManager", () => ({
  SAMPLE_CONTENT: "",
  TemplateEditor: () => <div>TEMPLATE_EDITOR</div>,
  buildPreviewSrcDoc: () => "",
  fetchPreview: jest.fn().mockResolvedValue({ html: "", css: "" }),
}));
jest.mock("../CssSelectorPrefixForm", () => ({ CssSelectorPrefixForm: () => <div>PREFIX_FORM</div> }));
jest.mock("../actions", () => ({
  upsertProjectCustomTagAction: jest.fn(),
  deleteProjectCustomTagAction: jest.fn(),
}));

const base = { projectId: 7, projectName: "案件", projectSlug: "slug", cssSelectorPrefix: null };

const tag: CustomTag = {
  id: 1,
  tagName: "note",
  htmlTemplate: "<div>{{content}}</div>",
  description: "注意書き",
  cssContent: ".note{}",
  tagFormat: "BLOCK",
  projectId: 7,
  createdAt: "2026-10-12T00:00:00Z",
  updatedAt: "2026-10-12T00:00:00Z",
};

/** issue #1750: Penpotのデザインファイル連携を削除したので、一覧に Penpot の列は無い。 */
describe("ProjectCustomTagManager: タグ一覧の列(issue #1750)", () => {
  it("列見出しに Penpot は無い", () => {
    render(<ProjectCustomTagManager {...base} tags={[tag]} />);

    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(["タグ名", "形式", "説明", "表示サンプル", ""]);
    expect(screen.queryByText("Penpot")).not.toBeInTheDocument();
  });

  it("古い応答に Penpot のURLが残っていても、リンクは出さない", () => {
    const legacy = { ...tag, ["penpot" + "FileUrl"]: "https://penpot.example/file-1" } as CustomTag;

    render(<ProjectCustomTagManager {...base} tags={[legacy]} />);

    expect(screen.queryByRole("link", { name: /開く/ })).not.toBeInTheDocument();
  });

  it("タグが無いときの案内行は、全ての列にまたがる", () => {
    render(<ProjectCustomTagManager {...base} tags={[]} />);

    const cell = screen.getByText("登録済みカスタムタグはありません").closest("td");
    expect(cell).toHaveAttribute("colspan", "5");
  });
});
