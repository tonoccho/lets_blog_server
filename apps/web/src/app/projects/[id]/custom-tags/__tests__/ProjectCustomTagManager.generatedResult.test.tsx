import { render, screen } from "@testing-library/react";
import { ProjectCustomTagManager } from "../ProjectCustomTagManager";

jest.mock("@/app/custom-tags/CustomTagGenerationForm", () => ({
  CustomTagGenerationForm: ({ currentProjectId }: { currentProjectId: number | null }) => (
    <div>{`GENERATION_FORM[${currentProjectId}]`}</div>
  ),
}));
jest.mock("@/app/custom-tags/CustomTagGenerationResult", () => ({
  CustomTagGenerationResult: ({
    projectId,
    result,
    effectivePrefix,
  }: {
    projectId: number;
    result: { jobId: number; tagName: string };
    effectivePrefix?: string | null;
  }) => <div>{`GENERATION_RESULT[${projectId}|${result.jobId}|${result.tagName}|${effectivePrefix}]`}</div>,
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

const base = { projectId: 7, projectName: "案件", projectSlug: "slug", cssSelectorPrefix: null, tags: [] };

/** issue #1409: 「結果を見る」で開いたときだけ、生成結果(未保存)を生成フォームの近くに出す。 */
describe("ProjectCustomTagManager: 生成結果(issue #1409)", () => {
  it("生成結果が無ければ生成フォームだけを出す", () => {
    render(<ProjectCustomTagManager {...base} />);

    expect(screen.getByText("GENERATION_FORM[7]")).toBeInTheDocument();
    expect(screen.queryByText(/GENERATION_RESULT/)).not.toBeInTheDocument();
  });

  it("生成結果があれば、プロジェクトと接頭辞(未設定ならslug)を添えて出す", () => {
    const result = {
      jobId: 42,
      tagName: "blue",
      description: null,
      projectId: 7,
      htmlTemplate: "<b/>",
      cssContent: ".b{}",
    };

    render(<ProjectCustomTagManager {...base} generatedResult={result} />);

    expect(screen.getByText("GENERATION_RESULT[7|42|blue|slug]")).toBeInTheDocument();
    expect(screen.getByText("GENERATION_FORM[7]")).toBeInTheDocument();
  });

  it("接頭辞が設定されていればそれを使う", () => {
    const result = { jobId: 1, tagName: "t", description: null, projectId: 7, htmlTemplate: "<b/>", cssContent: "" };

    render(<ProjectCustomTagManager {...base} cssSelectorPrefix="custom" generatedResult={result} />);

    expect(screen.getByText("GENERATION_RESULT[7|1|t|custom]")).toBeInTheDocument();
  });
});
