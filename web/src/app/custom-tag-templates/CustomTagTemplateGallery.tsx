"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { CustomTagTemplate, Project } from "@/lib/apiClient";
import { cloneCustomTagTemplateAction } from "./actions";

interface TemplateEditorProps {
  initialHtml: string;
  initialCss: string;
}

function TemplateEditor({ initialHtml, initialCss }: TemplateEditorProps) {
  const [htmlTemplateValue, setHtmlTemplateValue] = useState(initialHtml);
  const [cssContentValue, setCssContentValue] = useState(initialCss);
  const [previewSrcDoc, setPreviewSrcDoc] = useState(() => {
    const html = htmlTemplateValue
      .replaceAll("{{content}}", "サンプルテキストです。ここに本文が入ります。")
      .replace(/\{\{attr:([a-zA-Z0-9_]+)\}\}/g, (_match, key: string) => `サンプル${key}`);
    return `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${cssContentValue}</style></head><body>${html}</body></html>`;
  });

  useEffect(() => {
    const timer = setTimeout(() => {
      const html = htmlTemplateValue
        .replaceAll("{{content}}", "サンプルテキストです。ここに本文が入ります。")
        .replace(/\{\{attr:([a-zA-Z0-9_]+)\}\}/g, (_match, key: string) => `サンプル${key}`);
      setPreviewSrcDoc(`<!DOCTYPE html><html><head><meta charset="utf-8"><style>${cssContentValue}</style></head><body>${html}</body></html>`);
    }, 300);
    return () => clearTimeout(timer);
  }, [htmlTemplateValue, cssContentValue]);

  return (
    <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
      <div className="space-y-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">HTMLテンプレート</span>
          <textarea
            name="htmlTemplate"
            value={htmlTemplateValue}
            onChange={(e) => setHtmlTemplateValue(e.target.value)}
            required
            rows={6}
            placeholder='<div class="alert">{{content}}</div>'
            className="rounded border border-neutral-300 px-3 py-2 font-mono text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">CSS(任意)</span>
          <textarea
            name="cssContent"
            value={cssContentValue}
            onChange={(e) => setCssContentValue(e.target.value)}
            rows={6}
            placeholder=".alert { color: red; border: 1px solid; padding: 0.5em; }"
            className="rounded border border-neutral-300 px-3 py-2 font-mono text-sm"
          />
        </label>
      </div>
      <div className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600">プレビュー(自動更新)</span>
        <iframe
          title="テンプレートプレビュー"
          srcDoc={previewSrcDoc}
          sandbox="allow-same-origin"
          className="h-[268px] rounded border border-neutral-300 bg-white"
        />
      </div>
    </div>
  );
}

interface TemplateDetailProps {
  template: CustomTagTemplate;
  projects: Project[];
  onClose: () => void;
  onClone: (template: CustomTagTemplate, clonedName: string) => void;
}

function TemplateDetailPanel({ template, projects, onClose, onClone }: TemplateDetailProps) {
  const [cloneName, setCloneName] = useState("");
  const projectNameById = new Map(projects.map((p) => [p.id, p.name]));

  const handleClone = async () => {
    if (!cloneName.trim()) {
      alert("新しいテンプレート名を入力してください");
      return;
    }
    if (!window.confirm(`${cloneName}として複製しますか?`)) {
      return;
    }
    try {
      const { error } = await cloneCustomTagTemplateAction(template.id, {
        newTemplateName: cloneName,
        description: template.description ?? undefined,
        category: template.category ?? undefined,
        projectId: template.projectId ?? undefined,
      });
      if (error) {
        alert(`複製に失敗しました: ${error}`);
      } else {
        onClone(template, cloneName);
        setCloneName("");
      }
    } catch (err) {
      alert(`複製に失敗しました: ${err}`);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white rounded-lg w-full max-w-2xl max-h-[90vh] overflow-auto p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">{template.templateName}</h2>
          <button
            onClick={onClose}
            className="text-neutral-500 hover:text-neutral-700 text-xl"
          >
            ✕
          </button>
        </div>

        <div className="space-y-3">
          <div>
            <span className="text-sm text-neutral-600">説明</span>
            <p className="text-sm">{template.description || "なし"}</p>
          </div>
          <div>
            <span className="text-sm text-neutral-600">カテゴリー</span>
            <p className="text-sm">{template.category || "なし"}</p>
          </div>
          <div>
            <span className="text-sm text-neutral-600">スコープ</span>
            <p className="text-sm">
              {template.projectId ? projectNameById.get(template.projectId) ?? `Project #${template.projectId}` : "グローバル"}
            </p>
          </div>
          <div>
            <span className="text-sm text-neutral-600">バージョン</span>
            <p className="text-sm">{template.version}</p>
          </div>
          <div>
            <span className="text-sm text-neutral-600">公開状態</span>
            <p className="text-sm">{template.isPublished ? "公開" : "非公開"}</p>
          </div>
        </div>

        <TemplateEditor initialHtml={template.htmlTemplate} initialCss={template.cssContent || ""} />

        <div className="space-y-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600">複製として新しい名前で保存</span>
            <input
              type="text"
              value={cloneName}
              onChange={(e) => setCloneName(e.target.value)}
              placeholder="新しいテンプレート名"
              className="rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </label>
          <button
            onClick={handleClone}
            disabled={!cloneName.trim()}
            className="w-full rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-300"
          >
            複製を作成
          </button>
        </div>

        <button
          onClick={onClose}
          className="w-full rounded border border-neutral-300 px-4 py-2 text-sm text-neutral-700 hover:bg-neutral-50"
        >
          閉じる
        </button>
      </div>
    </div>
  );
}

export function CustomTagTemplateGallery({
  templates,
  projects,
  currentProjectId,
  currentCategory,
  currentSearch,
  showAll,
}: {
  templates: CustomTagTemplate[];
  projects: Project[];
  currentProjectId: number | null;
  currentCategory?: string;
  currentSearch?: string;
  showAll: boolean;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [selectedTemplate, setSelectedTemplate] = useState<CustomTagTemplate | null>(null);
  const [categories, setCategories] = useState<string[]>([]);
  const [searchValue, setSearchValue] = useState(currentSearch || "");
  const [categoryValue, setCategoryValue] = useState(currentCategory || "");
  const [showAllValue, setShowAllValue] = useState(showAll);

  useEffect(() => {
    const uniqueCategories = Array.from(new Set(templates.map((t) => t.category).filter(Boolean) as string[]));
    setCategories(uniqueCategories.sort());
  }, [templates]);

  const updateSearchParams = (newSearch?: string, newCategory?: string, newShowAll?: boolean) => {
    const params = new URLSearchParams();
    if (currentProjectId) params.set("projectId", String(currentProjectId));
    if (newSearch) params.set("search", newSearch);
    if (newCategory) params.set("category", newCategory);
    if (newShowAll) params.set("showAll", "true");
    router.push(`/custom-tag-templates?${params.toString()}`);
  };

  const handleSearch = () => {
    updateSearchParams(searchValue, categoryValue, showAllValue);
  };

  const handleCategoryChange = (category: string) => {
    setCategoryValue(category);
    updateSearchParams(searchValue, category, showAllValue);
  };

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <label className="flex max-w-sm flex-col gap-1 text-sm">
            <span className="text-neutral-600">表示スコープ</span>
            <select
              value={currentProjectId ?? ""}
              onChange={(e) => {
                const value = e.target.value;
                router.push(value ? `/custom-tag-templates?projectId=${value}` : "/custom-tag-templates");
              }}
              className="rounded border border-neutral-300 px-3 py-2 text-sm"
            >
              <option value="">グローバル</option>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </label>
          <div className="flex items-center gap-2">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={showAllValue}
                onChange={(e) => {
                  setShowAllValue(e.target.checked);
                  updateSearchParams(searchValue, categoryValue, e.target.checked);
                }}
              />
              <span className="text-neutral-600">未公開を含める</span>
            </label>
          </div>
        </div>

        <div className="flex gap-3">
          <input
            type="text"
            value={searchValue}
            onChange={(e) => setSearchValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") handleSearch();
            }}
            placeholder="テンプレート名で検索"
            className="flex-1 rounded border border-neutral-300 px-3 py-2 text-sm"
          />
          <button
            onClick={handleSearch}
            className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
          >
            検索
          </button>
        </div>

        {categories.length > 0 && (
          <div className="flex flex-wrap gap-2">
            <button
              onClick={() => handleCategoryChange("")}
              className={`rounded px-3 py-1 text-sm ${
                categoryValue === ""
                  ? "bg-blue-600 text-white"
                  : "bg-neutral-100 text-neutral-700 hover:bg-neutral-200"
              }`}
            >
              すべて
            </button>
            {categories.map((category) => (
              <button
                key={category}
                onClick={() => handleCategoryChange(category)}
                className={`rounded px-3 py-1 text-sm ${
                  categoryValue === category
                    ? "bg-blue-600 text-white"
                    : "bg-neutral-100 text-neutral-700 hover:bg-neutral-200"
                }`}
              >
                {category}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        {templates.length === 0 ? (
          <div className="col-span-full rounded-lg border border-neutral-200 bg-neutral-50 p-8 text-center text-neutral-600">
            テンプレートがありません
          </div>
        ) : (
          templates.map((template) => (
            <div
              key={template.id}
              className="rounded-lg border border-neutral-200 bg-white p-4 hover:shadow-md transition cursor-pointer"
              onClick={() => setSelectedTemplate(template)}
            >
              <div className="space-y-2">
                <h3 className="font-semibold text-sm truncate">{template.templateName}</h3>
                <p className="text-xs text-neutral-600 line-clamp-2">{template.description || "説明なし"}</p>
                <div className="flex items-center justify-between gap-2 text-xs">
                  {template.category && <span className="bg-neutral-100 px-2 py-1 rounded">{template.category}</span>}
                  <span
                    className={`px-2 py-1 rounded font-medium ${
                      template.isPublished
                        ? "bg-green-100 text-green-700"
                        : "bg-yellow-100 text-yellow-700"
                    }`}
                  >
                    {template.isPublished ? "公開" : "非公開"}
                  </span>
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      {selectedTemplate && (
        <TemplateDetailPanel
          template={selectedTemplate}
          projects={projects}
          onClose={() => setSelectedTemplate(null)}
          onClone={() => {
            setSelectedTemplate(null);
            router.refresh();
          }}
        />
      )}
    </div>
  );
}
