"use client";

import { useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { approveArticleReviewAction, rejectArticleReviewAction } from "./actions";

type Outcome = { kind: "approved"; productionPostUrl: string; branchDeleted: boolean } | { kind: "rejected" };

/**
 * 一覧の 1 行分の「レビュー完了」「記事差し戻し」(issue #1346)。
 * 「レビュー完了」は本番登録・マージ・ブランチ削除を伴い取り消せないので、確認を挟む。
 * 成功したら結果をこの行に表示し、`onSucceeded` で一覧に知らせたうえで一覧を取り直す
 * (レビュー完了で PR は開いている PR の一覧から消えるため、行を残すのは一覧側の役目)。
 * 失敗はサーバの理由を行に出し、成功の結果は出さない。
 */
export function ArticleReviewDecision({
  projectId,
  prNumber,
  onSucceeded,
}: {
  projectId: number;
  prNumber: number;
  onSucceeded: () => void;
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [busy, setBusy] = useState<"approve" | "reject" | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [feedback, setFeedback] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const succeed = (result: Outcome) => {
    setOutcome(result);
    onSucceeded();
    router.refresh();
  };

  const approve = () => {
    setConfirming(false);
    setError(null);
    setBusy("approve");
    startTransition(async () => {
      const result = await approveArticleReviewAction(projectId, prNumber);
      if (result.error !== undefined || !result.productionPostUrl) {
        setError(result.error ?? "本番の投稿 URL が返りませんでした");
      } else {
        succeed({ kind: "approved", productionPostUrl: result.productionPostUrl, branchDeleted: result.branchDeleted !== false });
      }
      setBusy(null);
    });
  };

  const reject = () => {
    setConfirming(false);
    if (feedback.trim() === "") {
      setError("指摘事項を入力してください");
      return;
    }
    setError(null);
    setBusy("reject");
    startTransition(async () => {
      const result = await rejectArticleReviewAction(projectId, prNumber, feedback);
      if (result.rejected) {
        succeed({ kind: "rejected" });
      } else {
        setError(result.error ?? "差し戻せませんでした");
      }
      setBusy(null);
    });
  };

  const buttonClass = "whitespace-nowrap rounded px-3 py-1 text-white disabled:bg-neutral-200 disabled:text-neutral-600";

  return (
    <div className="flex flex-col items-start gap-2">
      {outcome === null && (
        <>
          <button
            type="button"
            onClick={() => setConfirming(true)}
            disabled={pending || confirming}
            className={`${buttonClass} bg-green-700`}
          >
            {busy === "approve" ? "本番環境へ登録しています…" : "レビュー完了"}
          </button>
          {confirming && (
            <div
              role="group"
              aria-label="レビュー完了の確認"
              className="flex flex-col items-start gap-1 rounded border border-neutral-300 dark:border-neutral-700 p-2 text-xs"
            >
              <p>
                本番環境へ登録し、Pull Request をマージしてブランチを削除します。この操作は取り消せません。
                本番の公開状態は記事の front matter の <code>status</code>(未指定なら draft)に従います。
              </p>
              <div className="flex gap-2">
                <button type="button" onClick={approve} className={`${buttonClass} bg-green-700`}>
                  実行する
                </button>
                <button
                  type="button"
                  onClick={() => setConfirming(false)}
                  className="rounded border border-neutral-400 px-3 py-1"
                >
                  取りやめる
                </button>
              </div>
            </div>
          )}
          <label className="flex w-full flex-col gap-1 text-xs">
            指摘事項
            <textarea
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              rows={3}
              className="w-56 rounded border border-neutral-300 dark:border-neutral-700 bg-transparent p-1 text-sm"
            />
          </label>
          <button type="button" onClick={reject} disabled={pending} className={`${buttonClass} bg-red-700`}>
            {busy === "reject" ? "差し戻しています…" : "記事差し戻し"}
          </button>
        </>
      )}
      {outcome?.kind === "approved" && (
        <>
          <p role="status" className="text-sm text-green-700 dark:text-green-400">
            レビューを完了しました。
            {!outcome.branchDeleted && "(公開とマージは完了しましたが、ブランチの削除に失敗しました)"}
          </p>
          <a
            href={outcome.productionPostUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="break-all text-blue-600 dark:text-blue-400 hover:underline"
          >
            {outcome.productionPostUrl}
          </a>
        </>
      )}
      {outcome?.kind === "rejected" && (
        <p role="status" className="text-sm text-green-700 dark:text-green-400">
          記事を差し戻しました。
        </p>
      )}
      {error && (
        <p role="alert" className="text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
