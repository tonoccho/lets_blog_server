"use client";

import { useState, useTransition } from "react";
import type { StaticContent, StaticContentType } from "@/lib/apiClient";
import type { StaticContentJobResult } from "@/lib/llmJobResults";
import { generateStaticContentAction, saveStaticContentAction } from "../../actions";

const LABELS: Record<StaticContentType, string> = {
  PRIVACY_POLICY: "プライバシーポリシー",
  OPERATOR_INFO: "運営者情報",
  TERMS_OF_SERVICE: "利用規約",
};

const CONTENT_TYPES: StaticContentType[] = ["PRIVACY_POLICY", "OPERATOR_INFO", "TERMS_OF_SERVICE"];

/**
 * サイトの静的コンテンツ。生成は非同期ジョブとして処理キューに積まれ(issue #1409)、完了を待たず、生成と同時に
 * 保存もしない。処理キューの「結果を見る」でこの画面へ戻ると、`generatedResult`(ジョブの結果)が
 * 未保存として示され、「保存」を押したときに初めて `static_content` へ書き込まれる。
 */
export function StaticContentPanel({
  siteId,
  initialContents,
  generatedResult,
}: {
  siteId: number;
  initialContents: StaticContent[];
  generatedResult?: (StaticContentJobResult & { jobId: number }) | null;
}) {
  const [contents, setContents] = useState<Record<StaticContentType, StaticContent | null>>({
    PRIVACY_POLICY: initialContents.find((c) => c.contentType === "PRIVACY_POLICY") ?? null,
    OPERATOR_INFO: initialContents.find((c) => c.contentType === "OPERATOR_INFO") ?? null,
    TERMS_OF_SERVICE: initialContents.find((c) => c.contentType === "TERMS_OF_SERVICE") ?? null,
  });

  return (
    <section className="max-w-xl space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="text-sm font-medium text-neutral-600 dark:text-neutral-400">静的コンテンツ</h2>
      <p className="text-xs text-neutral-600 dark:text-neutral-400">
        生成は処理キューで進みます。完了後、処理キューの「結果を見る」からこの画面で内容を確認し、
        「保存」を押すと静的コンテンツとして保存されます。
      </p>
      {CONTENT_TYPES.map((contentType) => (
        <StaticContentItem
          key={contentType}
          siteId={siteId}
          contentType={contentType}
          content={contents[contentType]}
          generated={generatedResult?.contentType === contentType ? generatedResult : null}
          onSaved={(content) => setContents((prev) => ({ ...prev, [contentType]: content }))}
        />
      ))}
    </section>
  );
}

function StaticContentItem({
  siteId,
  contentType,
  content,
  generated,
  onSaved,
}: {
  siteId: number;
  contentType: StaticContentType;
  content: StaticContent | null;
  generated: (StaticContentJobResult & { jobId: number }) | null;
  onSaved: (content: StaticContent) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [queuedJobId, setQueuedJobId] = useState<number | null>(null);
  const [copied, setCopied] = useState(false);
  const [saved, setSaved] = useState(false);
  const [isPending, startTransition] = useTransition();
  const [isSaving, startSaveTransition] = useTransition();

  function handleGenerate() {
    setError(null);
    setQueuedJobId(null);
    startTransition(async () => {
      const result = await generateStaticContentAction(siteId, contentType);
      if (result.error) {
        setError(result.error);
        return;
      }
      if (result.status === "failed") {
        // 実行枠と待ち行列が満杯のとき、ジョブは作られた上で failed として返る。
        setError("静的コンテンツ生成の待ち行列が満杯です。しばらくしてからもう一度要求してください。");
        return;
      }
      setQueuedJobId(result.jobId ?? null);
    });
  }

  function handleSave() {
    if (!generated) return;
    setError(null);
    startSaveTransition(async () => {
      const result = await saveStaticContentAction(siteId, contentType, generated.body);
      if (result.error) {
        setError(result.error);
        return;
      }
      if (result.content) {
        onSaved(result.content);
        setSaved(true);
      }
    });
  }

  async function handleCopy() {
    if (!content) return;
    await navigator.clipboard.writeText(content.body);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  // 保存したら、同じ結果を「未保存」として出し続けない。
  const showGenerated = generated !== null && !saved;

  return (
    <div className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-3 first:border-t-0 first:pt-0">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">{LABELS[contentType]}</span>
        <button
          type="button"
          onClick={handleGenerate}
          disabled={isPending}
          className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
        >
          {isPending ? "生成中…" : content ? "再生成" : "生成"}
        </button>
      </div>
      {error && <p className="text-sm text-red-600">{error}</p>}
      {queuedJobId !== null && (
        <p
          data-testid="static-content-queued"
          data-job-id={queuedJobId}
          className="rounded bg-green-50 p-2 text-xs text-green-800"
        >
          生成を要求しました。処理キューに追加されました。完了後、処理キューの「結果を見る」から内容を確認し、
          「保存」を押すと保存されます(この時点ではまだ保存されていません)。
        </p>
      )}
      {showGenerated && (
        <div
          data-testid="static-content-generated"
          data-job-id={generated.jobId}
          className="space-y-2 rounded border border-yellow-200 bg-yellow-50 p-3"
        >
          <p className="text-xs font-medium text-yellow-800">
            生成結果(ジョブ #{generated.jobId})。まだ保存されていません。内容を確認して「保存」を押してください。
          </p>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded bg-white dark:bg-neutral-900 p-3 font-mono text-xs text-neutral-600 dark:text-neutral-400">
            {generated.body}
          </pre>
          <button
            type="button"
            onClick={handleSave}
            disabled={isSaving}
            className="rounded bg-green-600 px-3 py-1.5 text-sm text-white hover:bg-green-700 disabled:opacity-50"
          >
            {isSaving ? "保存中…" : "保存"}
          </button>
        </div>
      )}
      {saved && <p className="text-sm text-green-600">保存しました。</p>}
      {content && (
        <div className="space-y-2">
          <button
            type="button"
            onClick={handleCopy}
            className="inline-block rounded bg-neutral-900 px-3 py-1.5 text-sm text-white hover:bg-neutral-800"
          >
            {copied ? "コピーしました" : "コピー"}
          </button>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded bg-neutral-50 dark:bg-neutral-800 p-3 font-mono text-xs text-neutral-600 dark:text-neutral-400">
            {content.body}
          </pre>
        </div>
      )}
    </div>
  );
}
