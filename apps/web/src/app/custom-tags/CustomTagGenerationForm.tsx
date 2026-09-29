"use client";

import { useEffect, useRef, useState } from "react";
import { useSession } from "next-auth/react";
import { useCustomTagGeneration } from "@/lib/useCustomTagGeneration";
import { useCustomTagValidation } from "@/lib/useCustomTagValidation";
import { ValidationPanel } from "./ValidationPanel";
import type { CustomTag, Project } from "@/lib/apiClient";

interface CustomTagGenerationFormProps {
  projects: Project[];
  currentProjectId: number | null;
  /** 統合CSS生成時に実際に適用されるCSSセレクタのプリフィックス(未設定時はプロジェクトのslug)。プロジェクトに紐付かない場合はnull(issue #307) */
  effectivePrefix?: string | null;
  /** 生成結果は生成時点で既にDB保存済みのため、idを含む保存済みタグをそのまま渡す(issue #354)。 */
  onGenerationSuccess: (tag: CustomTag) => void;
}

export function CustomTagGenerationForm({
  projects,
  currentProjectId,
  effectivePrefix,
  onGenerationSuccess,
}: CustomTagGenerationFormProps) {
  const { data: session, status: sessionStatus } = useSession();
  // セッション未解決のまま送信された場合に、利用者へ理由を見せるためのメッセージ(issue #778)。
  const [sessionError, setSessionError] = useState<string | null>(null);
  const { isLoading, error, result, generate, reset } = useCustomTagGeneration();
  const { isLoading: isValidating, error: validationError, result: validationResult, validate: validateContent, reset: resetValidation } = useCustomTagValidation();
  const formRef = useRef<HTMLFormElement>(null);
  const [showResults, setShowResults] = useState(false);
  // issue #1414: ハイドレーション完了(mounted)までは送信ボタンを押せないようにする(#1413と同じ方式)。
  // 完了前はonSubmitが未結線で、クリックがネイティブ送信になり入力値だけが失われる。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  const projectNameById = new Map(projects.map((p) => [p.id, p.name]));
  const formProjectId = currentProjectId;

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    // issue #778: 以前はここで黙ってreturnしていた。isLoadingが立たずerrorも出ないため、
    // 「ボタンを押しても何も起きない」という手がかりの無い無反応状態になっていた。
    // 根本的にはSessionProviderへサーバー解決済みのセッションを渡して未解決期間を
    // 無くしたが(layout.tsx)、セッション切れや取得失敗では依然ここに到達しうるため、
    // 捨てずに理由を表示する。
    if (!session?.user) {
      setSessionError(
        sessionStatus === "loading"
          ? "セッションを確認しています。少し待ってからもう一度お試しください。"
          : "セッションが確認できませんでした。ページを再読み込みするか、再ログインしてください。"
      );
      return;
    }
    setSessionError(null);

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
        onGenerationSuccess(generatedTag);
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
            AIに自然言語でUIコンポーネントのリクエストを送信すると、HTMLテンプレートとCSSが自動生成されます。
          </p>
          {/* issue #1051: JS無効時のネイティブGETフォールバックで入力値がURLへ漏れることを防ぐため、
              method="post"を明示する。送信自体はhandleSubmitがpreventDefaultして処理する。 */}
          <form ref={formRef} onSubmit={handleSubmit} method="post" className="space-y-3">
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
            {sessionError && <p className="text-sm text-red-600">{sessionError}</p>}
            {/* セッション未解決の間は押せないようにし、ラベルでも状態を示す(issue #778)。
                押せてしまうと、送信が捨てられたのか処理中なのかを利用者が区別できない。 */}
            <button
              type="submit"
              disabled={isLoading || sessionStatus === "loading" || !mounted}
              className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
            >
              {isLoading ? "生成中..." : sessionStatus === "loading" ? "セッション確認中..." : "生成"}
            </button>
          </form>
        </>
      ) : result ? (
        <div className="space-y-3">
          <div className="rounded-lg bg-green-50 p-3 text-sm text-green-800">
            <p className="font-medium">生成完了！</p>
            <p className="mt-1">
              生成と同時に保存済みです。内容は下の編集フォームに反映されているので、必要であれば修正して更新してください。
            </p>
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
                {effectivePrefix && (
                  <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
                    上記はそのまま保存される内容です。実際に配信される統合CSSでは、各セレクタの先頭に自動でプリフィックス「
                    <code>.{effectivePrefix}</code>」が付与されます(例: 先頭のセレクタは
                    <code> .{effectivePrefix} {result.cssContent.trim().split(/[\s{]/)[0] || "..."}</code>
                    のようになります)。
                  </p>
                )}
              </div>
            )}
          </div>
          <ValidationPanel result={validationResult} isLoading={isValidating} error={validationError} />
          {result.penpotFileUrl && (
            <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-3 text-sm">
              <p className="text-neutral-600 dark:text-neutral-400">
                同じプロンプトを元に、Penpot上にデザイン作業用のファイルを作成しました。
              </p>
              <a
                href={result.penpotFileUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="mt-1 inline-block font-medium text-blue-600 hover:underline dark:text-blue-400"
              >
                Penpotで開く →
              </a>
            </div>
          )}
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
