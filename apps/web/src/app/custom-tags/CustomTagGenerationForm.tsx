"use client";

import { useEffect, useRef, useState } from "react";
import { useSession } from "next-auth/react";
import { useCustomTagGeneration } from "@/lib/useCustomTagGeneration";
import type { Project } from "@/lib/apiClient";

interface CustomTagGenerationFormProps {
  projects: Project[];
  currentProjectId: number | null;
}

/**
 * カスタムタグのAI生成を要求するフォーム。生成は非同期ジョブとして処理キューに積まれ(issue #1409)、
 * 完了を待たず、生成と同時に保存もしない。結果は処理キューの「結果を見る」から
 * {@link CustomTagGenerationResult}で確認し、「保存」を押したときに初めてカスタムタグとして登録される。
 */
export function CustomTagGenerationForm({
  projects,
  currentProjectId,
}: CustomTagGenerationFormProps) {
  const { data: session, status: sessionStatus } = useSession();
  // セッション未解決のまま送信された場合に、利用者へ理由を見せるためのメッセージ(issue #778)。
  const [sessionError, setSessionError] = useState<string | null>(null);
  const { isLoading, error, queuedJobId, generate } = useCustomTagGeneration();
  const formRef = useRef<HTMLFormElement>(null);
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

    const jobId = await generate({
      prompt,
      tagName,
      description: description || undefined,
      projectId: formProjectId,
    });
    // 受理されたら入力欄を空にして、続けて別のタグを要求できるようにする(完了は待たない)。
    if (jobId !== undefined) {
      formRef.current?.reset();
    }
  }

  return (
    <div className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="font-medium">AIでカスタムタグを生成</h2>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        AIに自然言語でUIコンポーネントのリクエストを送信すると、HTMLテンプレートとCSSが生成されます。
        生成は処理キューで進み、完了を待たずに他の操作を続けられます。生成結果は処理キューの「結果を見る」から
        確認し、「保存」を押したときにカスタムタグとして登録されます。
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
        {queuedJobId !== null && (
          <p
            data-testid="custom-tag-generation-queued"
            data-job-id={queuedJobId}
            className="rounded-lg bg-green-50 p-3 text-sm text-green-800"
          >
            生成を要求しました。処理キューに追加されました。完了後、処理キューの「結果を見る」から内容を確認し、
            「保存」を押すとカスタムタグとして登録されます(この時点ではまだ保存されていません)。
          </p>
        )}
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
    </div>
  );
}
