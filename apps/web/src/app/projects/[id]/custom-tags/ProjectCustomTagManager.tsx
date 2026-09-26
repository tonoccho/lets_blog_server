"use client";

import { useActionState, useEffect, useRef, useState, useTransition } from "react";
import type { CustomTag, Project } from "@/lib/apiClient";
import { CustomTagGenerationForm } from "@/app/custom-tags/CustomTagGenerationForm";
import { SAMPLE_CONTENT, TemplateEditor, buildPreviewSrcDoc, fetchPreview } from "@/app/custom-tags/CustomTagManager";
import { CssSelectorPrefixForm } from "./CssSelectorPrefixForm";
import { upsertProjectCustomTagAction, deleteProjectCustomTagAction, type CustomTagFormState } from "./actions";

const initialState: CustomTagFormState = {};

/** タグ一覧の表示サンプル列(issue #297)。実際の投稿と同じレンダリング結果をサーバーから取得する(issue #335)。 */
function CustomTagPreviewCell({ projectId, tag }: { projectId: number; tag: CustomTag }) {
  const [srcDoc, setSrcDoc] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetchPreview(projectId, tag.htmlTemplate, tag.cssContent ?? "", SAMPLE_CONTENT)
      .then((result) => {
        if (!cancelled) setSrcDoc(buildPreviewSrcDoc(result.html, result.css));
      })
      .catch(() => {
        if (!cancelled) setError(true);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId, tag.htmlTemplate, tag.cssContent]);

  if (error) {
    return <code className="whitespace-pre-wrap break-all text-xs text-neutral-500 dark:text-neutral-400">{tag.htmlTemplate}</code>;
  }

  return (
    <iframe
      title={`[${tag.tagName}]の表示サンプル`}
      srcDoc={srcDoc ?? ""}
      sandbox="allow-same-origin"
      className="h-24 w-full min-w-[220px] rounded border border-neutral-200 dark:border-neutral-700 bg-white dark:bg-neutral-900"
    />
  );
}

function formatLabel(tagFormat: CustomTag["tagFormat"]): string {
  return tagFormat === "INLINE" ? "インライン" : "ブロック";
}

/** プロジェクト詳細のカスタムタグ画面。表示・保存の対象を常に自プロジェクトのみに固定する(issue #157)。 */
export function ProjectCustomTagManager({
  projectId,
  projectName,
  projectSlug,
  cssSelectorPrefix,
  tags,
}: {
  projectId: number;
  projectName: string;
  projectSlug: string;
  cssSelectorPrefix: string | null;
  tags: CustomTag[];
}) {
  const [editing, setEditing] = useState<CustomTag | null>(null);
  const [state, formAction, pending] = useActionState(upsertProjectCustomTagAction, initialState);
  const [isDeleting, startDeleteTransition] = useTransition();
  const formRef = useRef<HTMLFormElement>(null);
  const [handledSuccess, setHandledSuccess] = useState(false);

  const currentProject: Project = { id: projectId, name: projectName } as Project;

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
      deleteProjectCustomTagAction(projectId, id);
    });
  }

  return (
    <div className="space-y-8">
      <p className="max-w-2xl text-sm text-neutral-600 dark:text-neutral-400">
        このプロジェクト専用のカスタムタグです。グローバルタグや他プロジェクトのタグは表示されません。
      </p>

      <CssSelectorPrefixForm projectId={projectId} projectSlug={projectSlug} cssSelectorPrefix={cssSelectorPrefix} />

      <CustomTagGenerationForm
        projects={[currentProject]}
        currentProjectId={projectId}
        effectivePrefix={cssSelectorPrefix ?? projectSlug}
        onGenerationSuccess={(tag) => setEditing(tag)}
      />

      <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
            <tr>
              <th className="px-4 py-2">タグ名</th>
              <th className="px-4 py-2">形式</th>
              <th className="px-4 py-2">説明</th>
              <th className="px-4 py-2">表示サンプル</th>
              <th className="px-4 py-2">Penpot</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {tags.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center">
                  <div className="flex flex-col items-center gap-4">
                    <p className="text-neutral-600 dark:text-neutral-400">登録済みカスタムタグはありません</p>
                    <a
                      href="#custom-tag-form"
                      className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
                    >
                      カスタムタグを作成する
                    </a>
                  </div>
                </td>
              </tr>
            )}
            {tags.map((tag) => (
              <tr
                key={tag.id}
                className="border-b border-neutral-100 dark:border-neutral-800 last:border-0 align-top cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors"
              >
                <td className="px-4 py-2 font-mono">[{tag.tagName}]</td>
                <td className="px-4 py-2">
                  <span
                    className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                      tag.tagFormat === "INLINE"
                        ? "bg-purple-100 text-purple-700"
                        : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
                    }`}
                  >
                    {formatLabel(tag.tagFormat)}
                  </span>
                </td>
                <td className="px-4 py-2 text-neutral-600 dark:text-neutral-400">{tag.description}</td>
                <td className="px-4 py-2">
                  <CustomTagPreviewCell projectId={projectId} tag={tag} />
                </td>
                <td className="px-4 py-2 whitespace-nowrap">
                  {tag.penpotFileUrl && (
                    <a
                      href={tag.penpotFileUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-sm text-blue-600 hover:underline"
                      onClick={(e) => e.stopPropagation()}
                    >
                      開く →
                    </a>
                  )}
                </td>
                <td className="px-4 py-2 text-right whitespace-nowrap">
                  <button type="button" onClick={() => setEditing(tag)} className="text-sm text-blue-600 hover:underline">
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
        id="custom-tag-form"
        ref={formRef}
        action={formAction}
        className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
      >
        <div className="flex items-center justify-between">
          <h2 className="font-medium">
            {editing ? `カスタムタグを編集: [${editing.tagName}]` : "カスタムタグを追加"}
          </h2>
          {editing && (
            <button
              type="button"
              onClick={() => setEditing(null)}
              className="text-sm text-neutral-500 dark:text-neutral-400 hover:underline"
            >
              新規作成に戻す
            </button>
          )}
        </div>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          投稿のMarkdown本文中で <code>{"[tagname key=\"value\"]"}</code> 〜 <code>{"[/tagname]"}</code> の形式で使用できます。
          ブロック形式は <code>{"[tagname]\\n複数行のコンテンツ\\n[/tagname]"}</code>
          のように開始タグの直後で改行し、インライン形式は <code>{"文章中[tagname]コンテンツ[/tagname]の続き"}</code>
          のように同じ行に開始・終了タグを書きます。
          テンプレート内では本文を <code>{"{{content}}"}</code>、属性値を <code>{"{{attr:key}}"}</code> で参照できます。
        </p>
        {editing && <input type="hidden" name="id" value={editing.id} />}
        <input type="hidden" name="projectId" value={projectId} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">タグ名(英数字・ハイフン・アンダースコアのみ)</span>
            <input
              name="tagName"
              key={editing?.id ?? "new"}
              defaultValue={editing?.tagName ?? ""}
              required
              pattern="[a-zA-Z][a-zA-Z0-9_\-]*"
              placeholder="alert"
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">説明(任意)</span>
            <input
              name="description"
              key={`desc-${editing?.id ?? "new"}`}
              defaultValue={editing?.description ?? ""}
              placeholder="注意書きの装飾"
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">形式</span>
            <select
              name="tagFormat"
              key={`format-${editing?.id ?? "new"}`}
              defaultValue={editing?.tagFormat ?? "BLOCK"}
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            >
              <option value="BLOCK">ブロック([tag]〜複数行〜[/tag])</option>
              <option value="INLINE">インライン(文章中に[tag]〜[/tag]を埋め込む)</option>
            </select>
          </label>
        </div>
        <TemplateEditor
          key={editing?.id ?? "new"}
          projectId={projectId}
          initialHtml={editing?.htmlTemplate ?? ""}
          initialCss={editing?.cssContent ?? ""}
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
