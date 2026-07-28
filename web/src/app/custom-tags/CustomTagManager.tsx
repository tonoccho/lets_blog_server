"use client";

import { useActionState, useEffect, useRef, useState, useTransition } from "react";
import type { CustomTag } from "@/lib/apiClient";
import { deleteCustomTagAction, upsertCustomTagAction, CustomTagFormState } from "./actions";

const initialState: CustomTagFormState = {};

export function CustomTagManager({ tags }: { tags: CustomTag[] }) {
  const [editing, setEditing] = useState<CustomTag | null>(null);
  const [state, formAction, pending] = useActionState(upsertCustomTagAction, initialState);
  const [isDeleting, startDeleteTransition] = useTransition();
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
      setEditing(null);
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
      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">タグ名</th>
              <th className="px-4 py-2">説明</th>
              <th className="px-4 py-2">HTMLテンプレート</th>
              <th className="px-4 py-2">CSS</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {tags.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-neutral-600">
                  登録済みカスタムタグはありません
                </td>
              </tr>
            )}
            {tags.map((tag) => (
              <tr key={tag.id} className="border-b border-neutral-100 last:border-0 align-top">
                <td className="px-4 py-2 font-mono">:::{tag.tagName}</td>
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
          <h2 className="font-medium">{editing ? `カスタムタグを編集: :::${editing.tagName}` : "カスタムタグを追加"}</h2>
          {editing && (
            <button
              type="button"
              onClick={() => setEditing(null)}
              className="text-sm text-neutral-500 hover:underline"
            >
              新規作成に戻す
            </button>
          )}
        </div>
        <p className="text-sm text-neutral-600">
          投稿のMarkdown本文中で <code>{":::tagname key=\"value\""}</code> 〜 <code>:::</code> の形式で使用できます。
          テンプレート内では本文を <code>{"{{content}}"}</code>、属性値を <code>{"{{attr:key}}"}</code> で参照できます。
        </p>
        {editing && <input type="hidden" name="id" value={editing.id} />}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600">タグ名(英数字・ハイフン・アンダースコアのみ)</span>
            <input
              name="tagName"
              key={editing?.id ?? "new"}
              defaultValue={editing?.tagName}
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
              key={`desc-${editing?.id ?? "new"}`}
              defaultValue={editing?.description ?? ""}
              placeholder="注意書きの装飾"
              className="rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </label>
        </div>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">HTMLテンプレート</span>
          <textarea
            name="htmlTemplate"
            key={`tpl-${editing?.id ?? "new"}`}
            defaultValue={editing?.htmlTemplate}
            required
            rows={4}
            placeholder='<div class="alert">{{content}}</div>'
            className="rounded border border-neutral-300 px-3 py-2 font-mono text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">CSS(任意、このタグが使われた投稿の本文冒頭に一度だけ挿入されます)</span>
          <textarea
            name="cssContent"
            key={`css-${editing?.id ?? "new"}`}
            defaultValue={editing?.cssContent ?? ""}
            rows={4}
            placeholder=".alert { color: red; border: 1px solid; padding: 0.5em; }"
            className="rounded border border-neutral-300 px-3 py-2 font-mono text-sm"
          />
        </label>
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
