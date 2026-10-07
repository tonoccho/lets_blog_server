import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { renderToString } from "react-dom/server";
import { hydrateRoot } from "react-dom/client";
import { TagDesignSettingsPanel } from "../TagDesignSettingsPanel";
import { generateTagDesignAction, saveTagDesignSettingAction } from "../actions";
import type { TagDesignPreset, TagDesignSetting } from "@/lib/apiClient";

/**
 * issue #1144: タグデザイン編集画面(TagDesignEditor)のCSS欄は、SSRされたHTMLが届いた時点で
 * 既に標準(または保存済み)CSSが表示された、見た目上は操作可能な<textarea>になっている。
 * ところがReactのイベントハンドラは、クライアントバンドルの読み込み・実行が終わり
 * hydrateRoot()が実際に走るまでアタッチされない。そのわずかな窓の間にユーザーが
 * (ページ遷移直後で素早く)タイプすると、まだ何のリスナーも無い素のDOMへブラウザが
 * ネイティブに書き込みを行う。その後ハイドレーションが完了して初めてonChangeが有効になった
 * 瞬間に読み取られるDOM値は、そのタイミング次第で「標準CSSの残骸 + 直後にタイプした文字」が
 * 混ざったものになり得る(#940のQA中に発見。--trace onで再現しにくくなるほどタイミングに
 * 敏感)。#1143(AI生成フォームがロード直後の入力を取りこぼす)と同種の、
 * 「ハイドレーション完了前に入力を受け付けてしまう」ことが根本原因のハイドレーション競合。
 *
 * このファイルではこの既存の競合クラスに対する既知の対策である「mountedガード」
 * (apps/web/src/app/ThemeSwitcher.tsx, apps/web/src/app/LanguageSwitcher.tsx)を
 * CSS欄に適用したことを検証する。マウント完了まで欄を`disabled`にしておけば、
 * ブラウザは無効化されたフォーム部品にキー入力を配送しないため、そもそも競合の入力口
 * 自体が物理的に存在しなくなる。
 */
jest.mock("../actions", () => ({
  saveTagDesignSettingAction: jest.fn(),
  generateTagDesignAction: jest.fn(),
}));

const presets: TagDesignPreset[] = [
  {
    id: "default",
    label: "標準",
    backgroundColor: "#ffffff",
    textColor: "#111111",
    accentColor: "#2563eb",
  },
];

function buildSettings(): TagDesignSetting[] {
  return [
    {
      tagType: "TOC",
      presetId: "default",
      backgroundColor: "#ffffff",
      textColor: "#111111",
      accentColor: "#2563eb",
      customCss: null,
      htmlTemplate: null,
    },
  ];
}

function renderServerHtml(): string {
  return renderToString(
    <TagDesignSettingsPanel projectId={1} presets={presets} settings={buildSettings()} />
  );
}

function hydrate(container: HTMLElement) {
  hydrateRoot(
    container,
    <TagDesignSettingsPanel projectId={1} presets={presets} settings={buildSettings()} />
  );
}

describe("TagDesignEditor のハイドレーション競合対策 (issue #1144)", () => {
  it("ハイドレーション完了前(SSR直後)のCSS欄はdisabledで、ネイティブな入力を受け付けない", () => {
    const html = renderServerHtml();
    const container = document.createElement("div");
    container.innerHTML = html;

    const textarea = container.querySelector('textarea[name="customCss"]') as HTMLTextAreaElement;
    expect(textarea).not.toBeNull();
    expect(textarea.disabled).toBe(true);
  });

  it("マウント完了後はCSS欄が操作可能になり、標準CSSと混ざらず入力値だけが反映される", () => {
    render(<TagDesignSettingsPanel projectId={1} presets={presets} settings={buildSettings()} />);

    const textarea = screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement;
    expect(textarea.disabled).toBe(false);

    fireEvent.change(textarea, { target: { value: ".typed{color:blue}" } });

    expect(textarea.value).toBe(".typed{color:blue}");
  });

  it("ハイドレーション完了前(SSR直後)のHTMLテンプレート欄もdisabledで、ネイティブな入力を受け付けない", () => {
    // customCssと同じ<form>内・同じSSR/ハイドレーション構造を持つ隣のtextarea。
    // TagDesignEditor全体がこのIssueのスコープなので、customCssだけでなくこちらも守る必要がある。
    const html = renderServerHtml();
    const container = document.createElement("div");
    container.innerHTML = html;

    const textarea = container.querySelector('textarea[name="htmlTemplate"]') as HTMLTextAreaElement;
    expect(textarea).not.toBeNull();
    expect(textarea.disabled).toBe(true);
  });

  it("SSR→ハイドレーションを10回連続で行っても、毎回ハイドレーション完了前はdisabledで守られている", () => {
    for (let i = 0; i < 10; i++) {
      const html = renderServerHtml();
      const container = document.createElement("div");
      container.innerHTML = html;
      document.body.appendChild(container);

      const textarea = container.querySelector('textarea[name="customCss"]') as HTMLTextAreaElement;
      expect(textarea.disabled).toBe(true);

      const errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
      act(() => {
        hydrate(container);
      });
      errorSpy.mockRestore();

      expect(textarea.disabled).toBe(false);

      act(() => {
        fireEvent.change(textarea, { target: { value: `.typed-${i}{color:blue}` } });
      });
      expect(textarea.value).toBe(`.typed-${i}{color:blue}`);

      document.body.removeChild(container);
    }
  });
});

/**
 * issue #1143: AI生成フォーム(TagDesignGenerationForm)のプロンプト欄も、#1144のCSS欄と同じく
 * SSR直後(ハイドレーション完了前)に入力を受け付けてしまい、入力がReactのstateに入らず
 * 「生成」ボタンが無効のままになる競合があった。CSS欄と同じmountedガードで守る。
 */
describe("AI生成フォームのハイドレーション競合対策 (issue #1143)", () => {
  const PROMPT_PLACEHOLDER = "背景を淡いグレーにして";

  function findPrompt(container: HTMLElement): HTMLTextAreaElement {
    const textarea = Array.from(container.querySelectorAll("textarea")).find((el) =>
      (el.getAttribute("placeholder") ?? "").includes(PROMPT_PLACEHOLDER)
    );
    expect(textarea).toBeDefined();
    return textarea as HTMLTextAreaElement;
  }

  function findGenerateButton(container: HTMLElement): HTMLButtonElement {
    const button = Array.from(container.querySelectorAll("button")).find(
      (el) => el.textContent === "生成"
    );
    expect(button).toBeDefined();
    return button as HTMLButtonElement;
  }

  it("ハイドレーション完了前(SSR直後)のプロンプト欄と「生成」ボタンはdisabledである", () => {
    const container = document.createElement("div");
    container.innerHTML = renderServerHtml();

    expect(findPrompt(container).disabled).toBe(true);
    expect(findGenerateButton(container).disabled).toBe(true);
  });

  it("SSR→ハイドレーションを10回連続で行っても、毎回ハイドレーション後はプロンプト欄が有効で入力値が保持され、「生成」ボタンが有効になる", () => {
    for (let i = 0; i < 10; i++) {
      const container = document.createElement("div");
      container.innerHTML = renderServerHtml();
      document.body.appendChild(container);

      const textarea = findPrompt(container);
      const button = findGenerateButton(container);
      expect(textarea.disabled).toBe(true);
      expect(button.disabled).toBe(true);

      const errorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
      act(() => {
        hydrate(container);
      });
      errorSpy.mockRestore();

      // ハイドレーション直後: 欄は有効になるが、プロンプトが空の間は「生成」は無効のまま
      expect(textarea.disabled).toBe(false);
      expect(button.disabled).toBe(true);

      act(() => {
        fireEvent.change(textarea, { target: { value: `緑にして-${i}` } });
      });
      expect(textarea.value).toBe(`緑にして-${i}`);
      expect(button.disabled).toBe(false);

      document.body.removeChild(container);
    }
  });
});

/**
 * 以下は#1144自体のスコープ外だが、scripts/check-changed-coverage.pyがファイル単位で
 * C1/C2分岐カバレッジを見るため、本ファイルを変更対象とした本Issueの機会に合わせて、
 * 既存の分岐(タグ種別ごとの標準CSS/プレビュー生成、プリセット選択、AI生成フォーム、
 * 一覧テーブルの表示切り替え)も押さえる
 * (apps/web/src/app/projects/[id]/__tests__/ProjectImageContentFilterSettingsForm.test.tsx
 * の#1051/#1085と同じ理由)。
 */
const twoPresets: TagDesignPreset[] = [
  {
    id: "default",
    label: "標準",
    backgroundColor: "#ffffff",
    textColor: "#111111",
    accentColor: "#2563eb",
  },
  {
    id: "dark",
    label: "ダーク",
    backgroundColor: "#111111",
    textColor: "#ffffff",
    accentColor: "#f59e0b",
  },
];

function buildMultiTagSettings(): TagDesignSetting[] {
  return [
    {
      tagType: "TOC",
      presetId: "default",
      backgroundColor: "#ffffff",
      textColor: "#111111",
      accentColor: "#2563eb",
      customCss: ".lb-toc-list{background:#eee;}",
      htmlTemplate: null,
    },
    {
      tagType: "BLOGCARD",
      presetId: "default",
      backgroundColor: "#ffffff",
      textColor: "#111111",
      accentColor: "#2563eb",
      customCss: null,
      htmlTemplate: "<div>{{title}}</div>",
    },
    {
      tagType: "AMAZON",
      presetId: "default",
      backgroundColor: "#ffffff",
      textColor: "#111111",
      accentColor: "#2563eb",
      customCss: null,
      htmlTemplate: null,
    },
  ];
}

describe("TagDesignSettingsPanel の一覧テーブル", () => {
  it("保存済みCSS/HTMLがあれば内容を、無ければ「標準」を表示する", () => {
    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    const rows = screen.getAllByRole("row");
    const tocRow = rows.find((row) => within(row).queryByText("目次"));
    const blogcardRow = rows.find((row) => within(row).queryByText("ブログカード"));
    expect(tocRow).toBeDefined();
    expect(blogcardRow).toBeDefined();

    expect(within(tocRow!).getByText(".lb-toc-list{background:#eee;}")).toBeInTheDocument();
    expect(within(blogcardRow!).getByText("<div>{{title}}</div>")).toBeInTheDocument();
    // BLOGCARD行はCSS未保存、TOC行はHTMLテンプレート未保存 -> それぞれ「標準」表示になる
    expect(within(blogcardRow!).getAllByText("標準").length).toBeGreaterThan(0);
    expect(within(tocRow!).getAllByText("標準").length).toBeGreaterThan(0);
  });
});

describe("TagDesignEditor のタグ種別切り替えとプリセット・色", () => {
  it("編集対象タグを切り替えると、そのタグ種別の標準CSSでCSS欄が初期化される", () => {
    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    // 初期表示はsettings[0](TOC)
    expect(screen.getByText(/目次のデザインを編集/)).toBeInTheDocument();

    const rows = screen.getAllByRole("row");
    const blogcardRow = rows.find((row) => within(row).queryByText("ブログカード"))!;
    fireEvent.click(within(blogcardRow).getByRole("button", { name: "編集" }));
    expect(screen.getByText(/ブログカードのデザインを編集/)).toBeInTheDocument();
    const blogcardCss = screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement;
    expect(blogcardCss.value).toContain(".lb-blogcard{");

    const amazonRow = screen.getAllByRole("row").find((row) => within(row).queryByText("Amazon商品カード"))!;
    fireEvent.click(within(amazonRow).getByRole("button", { name: "編集" }));
    expect(screen.getByText(/Amazon商品カードのデザインを編集/)).toBeInTheDocument();
    const amazonCss = screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement;
    expect(amazonCss.value).toContain(".lb-amazon-card{");
  });

  it("ブログカード・Amazon の標準HTMLテンプレートは <a> の中にブロック要素(div)を含まない", () => {
    const unsaved = buildMultiTagSettings().map((setting) => ({ ...setting, htmlTemplate: null }));
    render(<TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={unsaved} />);

    for (const label of ["ブログカード", "Amazon商品カード"]) {
      const row = screen.getAllByRole("row").find((r) => within(r).queryByText(label))!;
      fireEvent.click(within(row).getByRole("button", { name: "編集" }));
      const html = screen.getByRole("textbox", { name: /^HTMLテンプレート/ }) as HTMLTextAreaElement;
      expect(html.value).toContain("<a ");
      expect(html.value).not.toMatch(/<div/i);
    }
  });

  it("プリセットボタンを押すと選択状態の見た目が切り替わり、色が自動反映される(CSSを手で編集するまでの間)", () => {
    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    const darkButton = screen.getByRole("button", { name: "ダーク" });
    fireEvent.click(darkButton);
    expect(darkButton.className).toContain("border-neutral-900");

    const defaultButton = screen.getByRole("button", { name: "標準" });
    expect(defaultButton.className).not.toContain("border-neutral-900");
  });

  it("CSS欄を手で編集した後は、色を変えても自動再生成されない", () => {
    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    // settings[0](TOC)は保存済みCSSがあるため、この時点で既にcssAutoGenerated=false
    const cssTextarea = screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement;
    const before = cssTextarea.value;

    const backgroundColorInput = screen.getByLabelText(/^背景色/) as HTMLInputElement;
    fireEvent.change(backgroundColorInput, { target: { value: "#123456" } });

    expect(cssTextarea.value).toBe(before);
  });

  it("CSS欄が未編集(自動生成のまま)の状態で色を変えると、標準CSSが色の変更を反映して再生成される", () => {
    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    // BLOGCARD行はcustomCssが未保存 = cssAutoGenerated初期値true
    const rows = screen.getAllByRole("row");
    const blogcardRow = rows.find((row) => within(row).queryByText("ブログカード"))!;
    fireEvent.click(within(blogcardRow).getByRole("button", { name: "編集" }));

    const backgroundColorInput = screen.getByLabelText(/^背景色/) as HTMLInputElement;
    fireEvent.change(backgroundColorInput, { target: { value: "#123456" } });

    const cssTextarea = screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement;
    expect(cssTextarea.value).toContain("#123456");
  });
});

describe("AIでデザインを生成するフォーム", () => {
  beforeEach(() => {
    (generateTagDesignAction as jest.Mock).mockReset();
  });

  it("生成に失敗するとエラーメッセージを表示する", async () => {
    (generateTagDesignAction as jest.Mock).mockResolvedValueOnce({ error: "生成に失敗しました。" });

    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    const promptTextarea = screen.getByPlaceholderText(/背景を淡いグレーにして/);
    fireEvent.change(promptTextarea, { target: { value: "カードっぽくして" } });

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "生成" }));
    });

    expect(screen.getByText("生成に失敗しました。")).toBeInTheDocument();
  });

  it("生成の要求が受理されたら、保存済みとは言わず処理キューに追加された旨を示し、プロンプト欄を空にする(issue #1409)", async () => {
    (generateTagDesignAction as jest.Mock).mockResolvedValueOnce({ jobId: 23, status: "running" });

    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    const promptTextarea = screen.getByPlaceholderText(/背景を淡いグレーにして/);
    fireEvent.change(promptTextarea, { target: { value: "緑にして" } });

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "生成" }));
    });

    expect(generateTagDesignAction).toHaveBeenCalledWith(1, "TOC", "緑にして");
    const notice = screen.getByTestId("tag-design-queued");
    expect(notice).toHaveAttribute("data-job-id", "23");
    expect(notice).toHaveTextContent("処理キューに追加されました");
    expect(notice).toHaveTextContent("結果を見る");
    expect(notice).toHaveTextContent("保存");
    expect(promptTextarea).toHaveValue("");
    // 要求しただけでは、編集欄にも結果の表示にも何も現れない
    expect(screen.queryByTestId("tag-design-generated")).not.toBeInTheDocument();
    expect((screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement).value).not.toContain("green");
  });

  it("グローバル(projectId null)でも同じく projectId null のまま要求する(issue #1409)", async () => {
    (generateTagDesignAction as jest.Mock).mockResolvedValueOnce({ jobId: 24, status: "running" });

    render(
      <TagDesignSettingsPanel projectId={null} presets={twoPresets} settings={buildMultiTagSettings()} />
    );
    fireEvent.change(screen.getByPlaceholderText(/背景を淡いグレーにして/), { target: { value: "p" } });

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "生成" }));
    });

    expect(generateTagDesignAction).toHaveBeenCalledWith(null, "TOC", "p");
  });

  it("ジョブが満杯で failed として返ったら、待ち行列が満杯である旨を表示する(issue #1409)", async () => {
    (generateTagDesignAction as jest.Mock).mockResolvedValueOnce({ jobId: 25, status: "failed" });

    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );
    fireEvent.change(screen.getByPlaceholderText(/背景を淡いグレーにして/), { target: { value: "p" } });

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "生成" }));
    });

    expect(screen.getByText(/待ち行列が満杯/)).toBeInTheDocument();
    expect(screen.queryByTestId("tag-design-queued")).not.toBeInTheDocument();
  });
});

describe("処理キューの「結果を見る」から開いた生成結果と「保存」(issue #1409)", () => {
  const jobResult = {
    jobId: 23,
    projectId: 1,
    tagType: "BLOGCARD" as const,
    htmlTemplate: "<div>generated</div>",
    cssContent: ".generated{color:green;}",
  };

  beforeEach(() => {
    (saveTagDesignSettingAction as jest.Mock).mockReset();
  });

  function renderWithResult(result = jobResult, projectId: number | null = 1) {
    return render(
      <TagDesignSettingsPanel
        projectId={projectId}
        presets={twoPresets}
        settings={buildMultiTagSettings()}
        jobResult={result}
      />
    );
  }

  it("生成されたタグ種別の編集画面を開き、生成結果を未保存として表示する", () => {
    renderWithResult();

    // 先頭(TOC)ではなく、結果のタグ種別(ブログカード)の編集画面が開く
    expect(screen.getByText(/ブログカードのデザインを編集/)).toBeInTheDocument();
    const block = screen.getByTestId("tag-design-generated");
    expect(block).toHaveAttribute("data-job-id", "23");
    expect(block).toHaveTextContent("保存されていません");
    expect(block).toHaveTextContent(".generated{color:green;}");
    expect(block).toHaveTextContent("<div>generated</div>");
    // 保存するまで編集欄は変わらない
    expect((screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement).value).not.toContain("generated");
    expect(saveTagDesignSettingAction).not.toHaveBeenCalled();
  });

  it("別のタグ種別の編集画面に切り替えると、そのタグ種別の結果ではないので表示しない", () => {
    renderWithResult();

    const tocRow = screen.getAllByRole("row").find((row) => within(row).queryByText("目次"))!;
    fireEvent.click(within(tocRow).getByRole("button", { name: "編集" }));

    expect(screen.getByText(/目次のデザインを編集/)).toBeInTheDocument();
    expect(screen.queryByTestId("tag-design-generated")).not.toBeInTheDocument();
  });

  it("「保存」は現在のプリセット・色に生成したCSS/HTMLを添えて既存の保存アクションへ渡し、編集欄へも反映する", async () => {
    (saveTagDesignSettingAction as jest.Mock).mockResolvedValue({ success: true });
    renderWithResult();

    await act(async () => {
      fireEvent.click(within(screen.getByTestId("tag-design-generated")).getByRole("button", { name: "保存" }));
    });

    const [, formData] = (saveTagDesignSettingAction as jest.Mock).mock.calls[0] as [unknown, FormData];
    expect(Object.fromEntries(formData.entries())).toEqual({
      projectId: "1",
      tagType: "BLOGCARD",
      presetId: "default",
      backgroundColor: "#ffffff",
      textColor: "#111111",
      accentColor: "#2563eb",
      customCss: ".generated{color:green;}",
      htmlTemplate: "<div>generated</div>",
    });
    expect((screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement).value).toBe(".generated{color:green;}");
    expect((screen.getByRole("textbox", { name: /^HTMLテンプレート/ }) as HTMLTextAreaElement).value).toBe(
      "<div>generated</div>"
    );
    expect(screen.queryByTestId("tag-design-generated")).not.toBeInTheDocument();
    expect(screen.getByText("保存しました。")).toBeInTheDocument();
  });

  it("グローバル(projectId null)の結果は projectId を空にして保存する", async () => {
    (saveTagDesignSettingAction as jest.Mock).mockResolvedValue({ success: true });
    renderWithResult(jobResult, null);

    await act(async () => {
      fireEvent.click(within(screen.getByTestId("tag-design-generated")).getByRole("button", { name: "保存" }));
    });

    const [, formData] = (saveTagDesignSettingAction as jest.Mock).mock.calls[0] as [unknown, FormData];
    expect(formData.get("projectId")).toBe("");
  });

  it("HTMLテンプレートが空の結果(構造変更なし)は、現在のHTMLテンプレートを保つ", async () => {
    (saveTagDesignSettingAction as jest.Mock).mockResolvedValue({ success: true });
    renderWithResult({ ...jobResult, htmlTemplate: "" });

    await act(async () => {
      fireEvent.click(within(screen.getByTestId("tag-design-generated")).getByRole("button", { name: "保存" }));
    });

    const [, formData] = (saveTagDesignSettingAction as jest.Mock).mock.calls[0] as [unknown, FormData];
    expect(formData.get("htmlTemplate")).toBe("<div>{{title}}</div>");
    expect(formData.get("customCss")).toBe(".generated{color:green;}");
  });

  it("保存に失敗したら理由を表示し、結果を残して再試行できる", async () => {
    (saveTagDesignSettingAction as jest.Mock).mockResolvedValue({ error: "APIエラー (403): 権限がありません" });
    renderWithResult();

    await act(async () => {
      fireEvent.click(within(screen.getByTestId("tag-design-generated")).getByRole("button", { name: "保存" }));
    });

    expect(screen.getByText("APIエラー (403): 権限がありません")).toBeInTheDocument();
    expect(screen.getByTestId("tag-design-generated")).toBeInTheDocument();
    expect((screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement).value).not.toContain("generated");
  });

  it("「この結果を適用」は保存せずに編集欄へ反映する(保存前に手直しできる)", () => {
    renderWithResult();

    fireEvent.click(screen.getByRole("button", { name: "この結果を適用" }));

    expect((screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement).value).toBe(".generated{color:green;}");
    expect(saveTagDesignSettingAction).not.toHaveBeenCalled();
    expect(screen.queryByTestId("tag-design-generated")).not.toBeInTheDocument();
  });

  it("結果のタグ種別が一覧に無ければ先頭のタグ種別を開く", () => {
    render(
      <TagDesignSettingsPanel
        projectId={1}
        presets={twoPresets}
        settings={[buildMultiTagSettings()[0]]}
        jobResult={jobResult}
      />
    );

    expect(screen.getByText(/目次のデザインを編集/)).toBeInTheDocument();
    expect(screen.queryByTestId("tag-design-generated")).not.toBeInTheDocument();
  });
});

describe("プレビューの自動更新(300msデバウンス)", () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("CSS欄・HTMLテンプレート欄を空にすると、デバウンス後にプレビューが標準構成へ戻る", () => {
    render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={buildMultiTagSettings()} />
    );

    const cssTextarea = screen.getByRole("textbox", { name: /^CSS/ }) as HTMLTextAreaElement;
    const htmlTextarea = screen.getByRole("textbox", { name: /^HTMLテンプレート/ }) as HTMLTextAreaElement;

    act(() => {
      fireEvent.change(cssTextarea, { target: { value: "" } });
      fireEvent.change(htmlTextarea, { target: { value: "   " } });
    });

    act(() => {
      jest.advanceTimersByTime(300);
    });

    const iframe = screen.getByTitle(/プレビュー/) as HTMLIFrameElement;
    // customCssが空 -> <style>タグ無し、htmlTemplateが空相当 -> 標準テンプレートが使われる
    expect(iframe.srcdoc).not.toContain("<style>");
    expect(iframe.srcdoc).toContain('class="lb-toc-list"');
  });
});

describe("TagDesignSettingsPanel / TagDesignEditor の残りの分岐(#1144の機会に合わせて押さえる)", () => {
  beforeEach(() => {
    (generateTagDesignAction as jest.Mock).mockReset();
    (saveTagDesignSettingAction as jest.Mock).mockReset();
  });

  it("HTMLテンプレートに未知のプレースホルダがあっても空文字に置き換わる(置換不能キーのフォールバック)", () => {
    const settings: TagDesignSetting[] = [
      {
        tagType: "TOC",
        presetId: "default",
        backgroundColor: "#ffffff",
        textColor: "#111111",
        accentColor: "#2563eb",
        customCss: null,
        htmlTemplate: "{{toc}}<span>{{unknown}}</span>",
      },
    ];
    render(<TagDesignSettingsPanel projectId={1} presets={presets} settings={settings} />);

    const iframe = screen.getByTitle(/プレビュー/) as HTMLIFrameElement;
    expect(iframe.srcdoc).toContain("<span></span>");
  });

  it("保存済みプリセットIDがpresets一覧に無い場合、一覧テーブルはプリセットIDをそのまま表示する", () => {
    const settings: TagDesignSetting[] = [
      {
        tagType: "TOC",
        presetId: "custom-unknown-preset",
        backgroundColor: "#ffffff",
        textColor: "#111111",
        accentColor: "#2563eb",
        customCss: null,
        htmlTemplate: null,
      },
    ];
    render(<TagDesignSettingsPanel projectId={1} presets={presets} settings={settings} />);

    expect(screen.getByText("custom-unknown-preset")).toBeInTheDocument();
  });

  it("AI生成がエラーでもデータでもない結果を返した場合、生成結果は表示されない(dataの??nullフォールバック)", async () => {
    (generateTagDesignAction as jest.Mock).mockResolvedValueOnce({});

    render(<TagDesignSettingsPanel projectId={1} presets={presets} settings={buildMultiTagSettings()} />);

    const promptTextarea = screen.getByPlaceholderText(/背景を淡いグレーにして/);
    fireEvent.change(promptTextarea, { target: { value: "何か生成して" } });

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "生成" }));
    });

    expect(screen.queryByRole("button", { name: "この結果を適用" })).not.toBeInTheDocument();
  });

  it("projectIdがnull(グローバル既定)のときhidden inputは空文字を送る", () => {
    render(<TagDesignSettingsPanel projectId={null} presets={presets} settings={buildSettings()} />);

    const hiddenProjectId = document.querySelector('input[name="projectId"]') as HTMLInputElement;
    expect(hiddenProjectId.value).toBe("");
  });

  it("保存中はボタンが「保存中…」表示になる", async () => {
    let resolveSave: (value: { success: boolean }) => void = () => {};
    (saveTagDesignSettingAction as jest.Mock).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSave = resolve;
        })
    );

    render(<TagDesignSettingsPanel projectId={1} presets={presets} settings={buildSettings()} />);

    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByRole("button", { name: "保存中…" })).toBeInTheDocument();

    await act(async () => {
      resolveSave({ success: true });
    });
  });

  it("settingsが空配列の場合、既定のタグ種別(TOC)にフォールバックし編集フォームは表示されない", () => {
    render(<TagDesignSettingsPanel projectId={1} presets={presets} settings={[]} />);

    expect(screen.queryByRole("textbox", { name: /^CSS/ })).not.toBeInTheDocument();
    // ヘッダー行のみで、データ行(tbody内のtr)は無い
    expect(document.querySelectorAll("tbody tr")).toHaveLength(0);
  });

  it("編集中のタグ種別がsettingsから消えた場合、settings[0]へフォールバックして表示する", () => {
    const initialSettings = buildMultiTagSettings(); // TOC, BLOGCARD, AMAZON
    const { rerender } = render(
      <TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={initialSettings} />
    );

    const rows = screen.getAllByRole("row");
    const blogcardRow = rows.find((row) => within(row).queryByText("ブログカード"))!;
    fireEvent.click(within(blogcardRow).getByRole("button", { name: "編集" }));
    expect(screen.getByText(/ブログカードのデザインを編集/)).toBeInTheDocument();

    // BLOGCARDが無くなったsettingsへ更新される(例: 他タブでの削除相当) -> settings[0](TOC)へフォールバック
    const withoutBlogcard = initialSettings.filter((setting) => setting.tagType !== "BLOGCARD");
    rerender(<TagDesignSettingsPanel projectId={1} presets={twoPresets} settings={withoutBlogcard} />);

    expect(screen.getByText(/目次のデザインを編集/)).toBeInTheDocument();
  });
});
