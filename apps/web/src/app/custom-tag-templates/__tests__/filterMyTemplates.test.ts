import type { CustomTagTemplate } from "@/lib/apiClient";
import { filterMyTemplates } from "../filterMyTemplates";

function template(overrides: Partial<CustomTagTemplate>): CustomTagTemplate {
  return {
    id: 1,
    templateName: "アラート",
    description: null,
    category: null,
    htmlTemplate: "<div>{{content}}</div>",
    cssContent: null,
    version: 1,
    isPublished: false,
    originalTagId: null,
    projectId: null,
    createdBy: 1,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("filterMyTemplates", () => {
  const alert = template({ id: 1, templateName: "アラート", category: "装飾" });
  const quote = template({ id: 2, templateName: "Quote Box", category: "引用" });
  const plain = template({ id: 3, templateName: "無印", category: null });
  const all = [alert, quote, plain];

  it("条件が無ければ全件をそのまま返す", () => {
    expect(filterMyTemplates(all, {})).toEqual(all);
  });

  it("空文字の条件は無指定として扱う", () => {
    expect(filterMyTemplates(all, { search: "", category: "" })).toEqual(all);
  });

  it("テンプレート名の部分一致で絞る(大文字小文字を区別しない)", () => {
    expect(filterMyTemplates(all, { search: "quote" })).toEqual([quote]);
  });

  it("カテゴリーの完全一致で絞る", () => {
    expect(filterMyTemplates(all, { category: "装飾" })).toEqual([alert]);
  });

  it("検索語とカテゴリーは両方を満たすものだけ残す", () => {
    expect(filterMyTemplates(all, { search: "アラート", category: "引用" })).toEqual([]);
  });
});
