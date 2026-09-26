"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import type { CustomTagTemplate, Project } from "@/lib/apiClient";
import {
  applyCustomTagTemplateAction,
  cloneCustomTagTemplateAction,
  publishCustomTagTemplateAction,
  unpublishCustomTagTemplateAction,
} from "./actions";

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
          <span className="text-neutral-600 dark:text-neutral-400">HTMLテンプレート</span>
          <textarea
            name="htmlTemplate"
            value={htmlTemplateValue}
            onChange={(e) => setHtmlTemplateValue(e.target.value)}
            required
            rows={6}
            placeholder='<div class="alert">{{content}}</div>'
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">CSS(任意)</span>
          <textarea
            name="cssContent"
            value={cssContentValue}
            onChange={(e) => setCssContentValue(e.target.value)}
            rows={6}
            placeholder=".alert { color: red; border: 1px solid; padding: 0.5em; }"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
          />
        </label>
      </div>
      <div className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">プレビュー(自動更新)</span>
        <iframe
          title="テンプレートプレビュー"
          srcDoc={previewSrcDoc}
          sandbox="allow-same-origin"
          className="h-[268px] rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900"
        />
      </div>
    </div>
  );
}

interface TemplateDetailProps {
  template: CustomTagTemplate;
  projects: Project[];
  currentProjectId: number | null;
  onClose: () => void;
  onClone: (template: CustomTagTemplate, clonedName: string) => void;
  onPublishChange: () => void;
}

function TemplateDetailPanel({ template, projects, currentProjectId, onClose, onClone, onPublishChange }: TemplateDetailProps) {
  const [cloneName, setCloneName] = useState("");
  const [togglingPublish, setTogglingPublish] = useState(false);
  const projectNameById = new Map(projects.map((p) => [p.id, p.name]));
  const [applyProjectId, setApplyProjectId] = useState(String(currentProjectId ?? template.projectId ?? ""));
  const [applyTagName, setApplyTagName] = useState("");
  const [applying, setApplying] = useState(false);
  const [applyMessage, setApplyMessage] = useState<string | null>(null);

  // 複製(テンプレート間)とは別に、記事で [tagname] として使える custom_tags 行を対象プロジェクトに作る(issue #1131)。
  const handleApply = async () => {
    const tagName = applyTagName.trim();
    const projectId = Number(applyProjectId);
    setApplying(true);
    setApplyMessage(null);
    try {
      const { error } = await applyCustomTagTemplateAction(template.id, { projectId, tagName });
      if (error) {
        alert(`プロジェクトでの利用に失敗しました: ${error}`);
      } else {
        setApplyMessage(`[${tagName}] を${projectNameById.get(projectId) ?? `Project #${projectId}`}のカスタムタグとして作成しました`);
        setApplyTagName("");
      }
    } catch (err) {
      alert(`プロジェクトでの利用に失敗しました: ${err}`);
    } finally {
      setApplying(false);
    }
  };

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

  // ギャラリー自体が requireAdminSession() で保護されているため、この操作は admin だけが到達する。
  const handleTogglePublish = async () => {
    setTogglingPublish(true);
    try {
      const { error } = template.isPublished
        ? await unpublishCustomTagTemplateAction(template.id)
        : await publishCustomTagTemplateAction(template.id);
      if (error) {
        alert(`公開状態の変更に失敗しました: ${error}`);
      } else {
        onPublishChange();
      }
    } catch (err) {
      alert(`公開状態の変更に失敗しました: ${err}`);
    } finally {
      setTogglingPublish(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50">
      <div className="bg-white dark:bg-neutral-900 rounded-lg w-full max-w-2xl max-h-[90vh] overflow-auto p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">{template.templateName}</h2>
          <button
            onClick={onClose}
            className="text-neutral-500 dark:text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-300 text-xl"
          >
            ✕
          </button>
        </div>

        <div className="space-y-3">
          <div>
            <span className="text-sm text-neutral-600 dark:text-neutral-400">説明</span>
            <p className="text-sm">{template.description || "なし"}</p>
          </div>
          <div>
            <span className="text-sm text-neutral-600 dark:text-neutral-400">カテゴリー</span>
            <p className="text-sm">{template.category || "なし"}</p>
          </div>
          <div>
            <span className="text-sm text-neutral-600 dark:text-neutral-400">スコープ</span>
            <p className="text-sm">
              {template.projectId ? projectNameById.get(template.projectId) ?? `Project #${template.projectId}` : "グローバル"}
            </p>
          </div>
          <div>
            <span className="text-sm text-neutral-600 dark:text-neutral-400">バージョン</span>
            <p className="text-sm">{template.version}</p>
          </div>
          <div>
            <span className="text-sm text-neutral-600 dark:text-neutral-400">公開状態</span>
            <p className="text-sm">{template.isPublished ? "公開" : "非公開"}</p>
          </div>
          <button
            type="button"
            onClick={handleTogglePublish}
            disabled={togglingPublish}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800 disabled:bg-neutral-100 dark:disabled:bg-neutral-800 disabled:text-neutral-400"
          >
            {template.isPublished ? "非公開に戻す" : "公開する"}
          </button>
        </div>

        <TemplateEditor initialHtml={template.htmlTemplate} initialCss={template.cssContent || ""} />

        <div className="space-y-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">適用先プロジェクト</span>
            <select
              value={applyProjectId}
              onChange={(e) => setApplyProjectId(e.target.value)}
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            >
              <option value="">選択してください</option>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </label>
          <input
            type="text"
            value={applyTagName}
            onChange={(e) => setApplyTagName(e.target.value)}
            placeholder="タグ名(例: note)"
            pattern="[a-zA-Z][a-zA-Z0-9_\-]*"
            className="w-full rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
          <button
            type="button"
            onClick={handleApply}
            disabled={applying || !applyProjectId || !applyTagName.trim()}
            className="w-full rounded bg-green-600 px-4 py-2 text-sm text-white disabled:bg-neutral-300"
          >
            プロジェクトで使う
          </button>
          {applyMessage && <p className="text-sm text-green-700 dark:text-green-400">{applyMessage}</p>}
        </div>

        <div className="space-y-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">複製として新しい名前で保存</span>
            <input
              type="text"
              value={cloneName}
              onChange={(e) => setCloneName(e.target.value)}
              placeholder="新しいテンプレート名"
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
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
          className="w-full rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800"
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
  mine,
}: {
  templates: CustomTagTemplate[];
  projects: Project[];
  currentProjectId: number | null;
  currentCategory?: string;
  currentSearch?: string;
  showAll: boolean;
  mine: boolean;
}) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [selectedTemplate, setSelectedTemplate] = useState<CustomTagTemplate | null>(null);
  const [categories, setCategories] = useState<string[]>([]);
  const [searchValue, setSearchValue] = useState(currentSearch || "");
  const [categoryValue, setCategoryValue] = useState(currentCategory || "");
  const [showAllValue, setShowAllValue] = useState(showAll);
  const [mineValue, setMineValue] = useState(mine);

  useEffect(() => {
    const uniqueCategories = Array.from(new Set(templates.map((t) => t.category).filter(Boolean) as string[]));
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setCategories(uniqueCategories.sort());
  }, [templates]);

  const updateSearchParams = (newSearch?: string, newCategory?: string, newShowAll?: boolean, newMine?: boolean) => {
    const params = new URLSearchParams();
    if (currentProjectId) params.set("projectId", String(currentProjectId));
    if (newSearch) params.set("search", newSearch);
    if (newCategory) params.set("category", newCategory);
    if (newShowAll) params.set("showAll", "true");
    if (newMine) params.set("mine", "true");
    router.push(`/custom-tag-templates?${params.toString()}`);
  };

  const handleSearch = () => {
    updateSearchParams(searchValue, categoryValue, showAllValue, mineValue);
  };

  const handleCategoryChange = (category: string) => {
    setCategoryValue(category);
    updateSearchParams(searchValue, category, showAllValue, mineValue);
  };

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <label className="flex max-w-sm flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">表示スコープ</span>
            <select
              value={currentProjectId ?? ""}
              onChange={(e) => {
                const value = e.target.value;
                router.push(value ? `/custom-tag-templates?projectId=${value}` : "/custom-tag-templates");
              }}
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
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
                  updateSearchParams(searchValue, categoryValue, e.target.checked, mineValue);
                }}
              />
              <span className="text-neutral-600 dark:text-neutral-400">未公開を含める</span>
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={mineValue}
                onChange={(e) => {
                  setMineValue(e.target.checked);
                  updateSearchParams(searchValue, categoryValue, showAllValue, e.target.checked);
                }}
              />
              <span className="text-neutral-600 dark:text-neutral-400">自分が作ったものだけ</span>
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
            className="flex-1 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
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
                  : "bg-neutral-100 dark:bg-neutral-800 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700"
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
                    : "bg-neutral-100 dark:bg-neutral-800 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-200 dark:hover:bg-neutral-700"
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
          <div className="col-span-full rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-8 text-center text-neutral-600 dark:text-neutral-400">
            テンプレートがありません
          </div>
        ) : (
          templates.map((template) => (
            <div
              key={template.id}
              className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 hover:shadow-md transition cursor-pointer"
              onClick={() => setSelectedTemplate(template)}
            >
              <div className="space-y-2">
                <h3 className="font-semibold text-sm truncate">{template.templateName}</h3>
                <p className="text-xs text-neutral-600 dark:text-neutral-400 line-clamp-2">{template.description || "説明なし"}</p>
                <div className="flex items-center justify-between gap-2 text-xs">
                  {template.category && <span className="bg-neutral-100 dark:bg-neutral-800 px-2 py-1 rounded">{template.category}</span>}
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
          currentProjectId={currentProjectId}
          onClose={() => setSelectedTemplate(null)}
          onClone={() => {
            setSelectedTemplate(null);
            router.refresh();
          }}
          onPublishChange={() => {
            setSelectedTemplate(null);
            router.refresh();
          }}
        />
      )}
    </div>
  );
}
