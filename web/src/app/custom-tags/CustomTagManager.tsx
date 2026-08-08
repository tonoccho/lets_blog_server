"use client";

import { useActionState, useEffect, useRef, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import type { CustomTag, Project } from "@/lib/apiClient";
import { deleteCustomTagAction, upsertCustomTagAction, CustomTagFormState } from "./actions";
import { CustomTagGenerationForm } from "./CustomTagGenerationForm";

const initialState: CustomTagFormState = {};

const SAMPLE_CONTENT = "サンプルテキストです。ここに本文が入ります。";
const ATTR_PATTERN = /\{\{attr:([a-zA-Z0-9_]+)\}\}/g;

/** プレビュー用に{{content}}をサンプルテキストへ、{{attr:xxx}}をサンプル値へ置換したHTMLを組み立てる。 */
function buildPreviewSrcDoc(htmlTemplate: string, cssContent: string): string {
  const html = htmlTemplate
    .replaceAll("{{content}}", SAMPLE_CONTENT)
    .replace(ATTR_PATTERN, (_match, key: string) => `サンプル${key}`);
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${cssContent}</style></head><body>${html}</body></html>`;
}

/**
 * HTMLテンプレート/CSS入力とライブプレビューを担当する。編集対象(editing)が変わるたびに
 * 親側で`key`を変えて再マウントさせることで初期値を切り替える(useEffectでのprops→state同期は避ける)。
 */
function TemplateEditor({ initialHtml, initialCss }: { initialHtml: string; initialCss: string }) {
  const [htmlTemplateValue, setHtmlTemplateValue] = useState(initialHtml);
  const [cssContentValue, setCssContentValue] = useState(initialCss);
  const [previewSrcDoc, setPreviewSrcDoc] = useState(() => buildPreviewSrcDoc(initialHtml, initialCss));

  // HTML/CSS変更のたびに即再描画すると入力のたびにiframeが再構築されカクつくため、300msデバウンスする
  useEffect(() => {
    const timer = setTimeout(() => {
      setPreviewSrcDoc(buildPreviewSrcDoc(htmlTemplateValue, cssContentValue));
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
          <span className="text-neutral-600">CSS(任意、このタグが使われた投稿の本文冒頭に一度だけ挿入されます)</span>
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
        <span className="text-neutral-600">
          プレビュー({"{{content}}"}/{"{{attr:xxx}}"}はサンプル値に置き換えて表示、入力後300ms自動更新)
        </span>
        <iframe
          title="カスタムタグプレビュー"
          srcDoc={previewSrcDoc}
          sandbox="allow-same-origin"
          className="h-[268px] rounded border border-neutral-300 bg-white"
        />
      </div>
    </div>
  );
}

interface GeneratedContent {
  htmlTemplate: string;
  cssContent: string;
  tagName: string;
  description: string;
}

export function CustomTagManager({
  tags,
  projects,
  currentProjectId,
}: {
  tags: CustomTag[];
  projects: Project[];
  currentProjectId: number | null;
}) {
  const router = useRouter();
  const [editing, setEditing] = useState<CustomTag | null>(null);
  const [generatedContent, setGeneratedContent] = useState<GeneratedContent | null>(null);
  const [state, formAction, pending] = useActionState(upsertCustomTagAction, initialState);
  const [isDeleting, startDeleteTransition] = useTransition();
  const formRef = useRef<HTMLFormElement>(null);
  const [handledSuccess, setHandledSuccess] = useState(false);

  const projectNameById = new Map(projects.map((p) => [p.id, p.name]));
  const formProjectId = editing ? editing.projectId : currentProjectId;

  if (state.success && !handledSuccess) {
    setHandledSuccess(true);
    setEditing(null);
  } else if (!state.success && handledSuccess) {
    setHandledSuccess(false);
  }

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
    }
  }, [state.success]);

  function handleDelete(id: number) {
    if (!window.confirm("このカスタムタグを削除しますか?")) {
      return;
    }
    startDeleteTransition(() => {
      deleteCustomTagAction(id);
    });
  }

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <label className="flex max-w-sm flex-col gap-1 text-sm">
          <span className="text-neutral-600">表示スコープ</span>
          <select
            value={currentProjectId ?? ""}
            onChange={(e) => {
              const value = e.target.value;
              router.push(value ? `/custom-tags?projectId=${value}` : "/custom-tags");
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
        <a
          href={`/custom-tags/css-bundle${currentProjectId ? `?projectId=${currentProjectId}` : ""}`}
          className="rounded border border-neutral-300 px-3 py-2 text-sm text-neutral-700 hover:bg-neutral-50"
        >
          統合CSSダウンロード
        </a>
      </div>

      <CustomTagGenerationForm
        projects={projects}
        currentProjectId={currentProjectId}
        onGenerationSuccess={(htmlTemplate, cssContent, tagName, description) => {
          setGeneratedContent({ htmlTemplate, cssContent, tagName, description });
          setEditing(null);
        }}
      />

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">タグ名</th>
              <th className="px-4 py-2">スコープ</th>
              <th className="px-4 py-2">説明</th>
              <th className="px-4 py-2">HTMLテンプレート</th>
              <th className="px-4 py-2">CSS</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {tags.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-neutral-600">
                  登録済みカスタムタグはありません
                </td>
              </tr>
            )}
            {tags.map((tag) => (
              <tr key={tag.id} className="border-b border-neutral-100 last:border-0 align-top cursor-pointer hover:bg-neutral-50 hover:shadow-sm transition-colors">
                <td className="px-4 py-2 font-mono">:::{tag.tagName}</td>
                <td className="px-4 py-2">
                  <span
                    className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                      tag.projectId ? "bg-blue-100 text-blue-700" : "bg-neutral-100 text-neutral-600"
                    }`}
                  >
                    {tag.projectId ? projectNameById.get(tag.projectId) ?? `project#${tag.projectId}` : "グローバル"}
                  </span>
                </td>
                <td className="px-4 py-2 text-neutral-600">{tag.description}</td>
                <td className="px-4 py-2 font-mono text-xs text-neutral-500">
                  <code className="whitespace-pre-wrap break-all">{tag.htmlTemplate}</code>
                </td>
                <td className="px-4 py-2 font-mono text-xs text-neutral-500">
                  {tag.cssContent && <code className="whitespace-pre-wrap break-all">{tag.cssContent}</code>}
                </td>
                <td className="px-4 py-2 text-right whitespace-nowrap">
                  <button
                    type="button"
                    onClick={() => setEditing(tag)}
                    className="text-sm text-blue-600 hover:underline"
                  >
                    編集
                  </button>
                  <button
                    type="button"
                    onClick={() => handleDelete(tag.id)}
                    disabled={isDeleting}
                    className="ml-3 text-sm text-red-600 hover:underline disabled:text-neutral-400"
                  >
                    削除
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <form
        ref={formRef}
        action={formAction}
        className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5"
      >
        <div className="flex items-center justify-between">
          <h2 className="font-medium">
            {editing
              ? `カスタムタグを編集: :::${editing.tagName}`
              : generatedContent
              ? `カスタムタグを作成: :::${generatedContent.tagName}`
              : "カスタムタグを追加"}
          </h2>
          {(editing || generatedContent) && (
            <button
              type="button"
              onClick={() => {
                setEditing(null);
                setGeneratedContent(null);
              }}
              className="text-sm text-neutral-500 hover:underline"
            >
              新規作成に戻す
            </button>
          )}
        </div>
        <p className="text-sm text-neutral-600">
          投稿のMarkdown本文中で <code>{":::tagname key=\"value\""}</code> 〜 <code>:::</code> の形式で使用できます。
          テンプレート内では本文を <code>{"{{content}}"}</code>、属性値を <code>{"{{attr:key}}"}</code> で参照できます。
          スコープ: <strong>{formProjectId ? projectNameById.get(formProjectId) ?? `project#${formProjectId}` : "グローバル"}</strong>
          (上部の表示スコープに従います。プロジェクト変更後は再保存されません)
        </p>
        {editing && <input type="hidden" name="id" value={editing.id} />}
        <input type="hidden" name="projectId" value={formProjectId ?? ""} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600">タグ名(英数字・ハイフン・アンダースコアのみ)</span>
            <input
              name="tagName"
              key={editing?.id ?? generatedContent?.tagName ?? "new"}
              defaultValue={editing?.tagName ?? generatedContent?.tagName ?? ""}
              required
              pattern="[a-zA-Z][a-zA-Z0-9_\-]*"
              placeholder="alert"
              className="rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600">説明(任意)</span>
            <input
              name="description"
              key={`desc-${editing?.id ?? generatedContent?.tagName ?? "new"}`}
              defaultValue={editing?.description ?? generatedContent?.description ?? ""}
              placeholder="注意書きの装飾"
              className="rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </label>
        </div>
        <TemplateEditor
          key={editing?.id ?? generatedContent?.tagName ?? "new"}
          initialHtml={editing?.htmlTemplate ?? generatedContent?.htmlTemplate ?? ""}
          initialCss={editing?.cssContent ?? generatedContent?.cssContent ?? ""}
        />
        {state.error && <p className="text-sm text-red-600">{state.error}</p>}
        {state.success && <p className="text-sm text-green-600">保存しました。</p>}
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "保存中…" : editing ? "更新" : "追加"}
        </button>
      </form>
    </div>
  );
}
