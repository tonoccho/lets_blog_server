"use client";

import { useRef, useState } from "react";
import { useSession } from "next-auth/react";
import { useCustomTagGeneration } from "@/lib/useCustomTagGeneration";
import { useCustomTagValidation } from "@/lib/useCustomTagValidation";
import { ValidationPanel } from "./ValidationPanel";
import type { Project } from "@/lib/apiClient";

interface CustomTagGenerationFormProps {
  projects: Project[];
  currentProjectId: number | null;
  onGenerationSuccess: (htmlTemplate: string, cssContent: string, tagName: string, description: string) => void;
}

export function CustomTagGenerationForm({
  projects,
  currentProjectId,
  onGenerationSuccess,
}: CustomTagGenerationFormProps) {
  const { data: session } = useSession();
  const { isLoading, error, result, generate, reset } = useCustomTagGeneration();
  const { isLoading: isValidating, error: validationError, result: validationResult, validate: validateContent, reset: resetValidation } = useCustomTagValidation();
  const formRef = useRef<HTMLFormElement>(null);
  const [showResults, setShowResults] = useState(false);

  const projectNameById = new Map(projects.map((p) => [p.id, p.name]));
  const formProjectId = currentProjectId;

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!session?.user) return;

    const formData = new FormData(e.currentTarget);
    const prompt = formData.get("prompt") as string;
    const tagName = formData.get("tagName") as string;
    const description = formData.get("description") as string;

    try {
      const generatedTag = await generate({
        prompt,
        tagName,
        description: description || undefined,
        projectId: formProjectId,
      });

      if (generatedTag) {
        // 生成後に自動検証
        resetValidation();
        await validateContent(generatedTag.htmlTemplate, generatedTag.cssContent || "");
        setShowResults(true);
        onGenerationSuccess(
          generatedTag.htmlTemplate,
          generatedTag.cssContent || "",
          generatedTag.tagName,
          generatedTag.description || ""
        );
      }
    } catch (err) {
      // エラーはstateに保存されている
      console.error("Generation failed:", err);
    }
  }

  function handleUseResult() {
    if (result) {
      reset();
      setShowResults(false);
      formRef.current?.reset();
    }
  }

  return (
    <div className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div className="flex items-center justify-between">
        <h2 className="font-medium">AIでカスタムタグを生成</h2>
        {showResults && (
          <button
            type="button"
            onClick={() => {
              setShowResults(false);
              reset();
            }}
            className="text-sm text-neutral-500 dark:text-neutral-400 hover:underline"
          >
            別のプロンプトを試す
          </button>
        )}
      </div>

      {!showResults ? (
        <>
          <p className="text-sm text-neutral-600 dark:text-neutral-400">
            Ollamaに自然言語でUIコンポーネントのリクエストを送信すると、HTMLテンプレートとCSSが自動生成されます。
          </p>
          <form ref={formRef} onSubmit={handleSubmit} className="space-y-3">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-600 dark:text-neutral-400">プロンプト（UIコンポーネントの説明）</span>
              <textarea
                name="prompt"
                required
                rows={4}
                placeholder="例: 青いボタンコンポーネントを作成してください。padding 10px、background-color #007bff、text-color whiteをstyleしてください。"
                className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
              />
            </label>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-neutral-600 dark:text-neutral-400">タグ名(英数字・ハイフン・アンダースコアのみ)</span>
                <input
                  name="tagName"
                  required
                  pattern="[a-zA-Z][a-zA-Z0-9_\-]*"
                  placeholder="my-button"
                  className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-neutral-600 dark:text-neutral-400">説明(任意)</span>
                <input
                  name="description"
                  placeholder="生成されたボタンコンポーネント"
                  className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
                />
              </label>
            </div>
            <div className="flex items-center justify-between text-sm text-neutral-600 dark:text-neutral-400">
              <span>
                スコープ:{" "}
                <strong>{formProjectId ? projectNameById.get(formProjectId) ?? `project#${formProjectId}` : "グローバル"}</strong>
              </span>
            </div>
            {error && <p className="text-sm text-red-600">{error}</p>}
            <button
              type="submit"
              disabled={isLoading}
              className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
            >
              {isLoading ? "生成中..." : "生成"}
            </button>
          </form>
        </>
      ) : result ? (
        <div className="space-y-3">
          <div className="rounded-lg bg-green-50 p-3 text-sm text-green-800">
            <p className="font-medium">生成完了！</p>
            <p className="mt-1">生成されたHTMLとCSSを下のフォームに自動入力しました。確認して保存してください。</p>
          </div>
          <div className="space-y-2">
            <div>
              <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">タグ名:</span>
              <p className="font-mono text-sm text-neutral-600 dark:text-neutral-400">[{result.tagName}]</p>
            </div>
            <div>
              <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">説明:</span>
              <p className="text-sm text-neutral-600 dark:text-neutral-400">{result.description || "(なし)"}</p>
            </div>
            <div>
              <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">HTMLテンプレート:</span>
              <pre className="overflow-x-auto rounded bg-neutral-50 dark:bg-neutral-800 p-2 font-mono text-xs text-neutral-600 dark:text-neutral-400">
                {result.htmlTemplate}
              </pre>
            </div>
            {result.cssContent && (
              <div>
                <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">CSS:</span>
                <pre className="overflow-x-auto rounded bg-neutral-50 dark:bg-neutral-800 p-2 font-mono text-xs text-neutral-600 dark:text-neutral-400">
                  {result.cssContent}
                </pre>
              </div>
            )}
          </div>
          <ValidationPanel result={validationResult} isLoading={isValidating} error={validationError} />
          <button
            type="button"
            onClick={handleUseResult}
            className="rounded bg-green-600 px-4 py-2 text-sm text-white hover:bg-green-700"
          >
            この結果を使用
          </button>
        </div>
      ) : null}
    </div>
  );
}
