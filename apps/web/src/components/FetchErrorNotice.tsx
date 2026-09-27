/**
 * API取得に失敗した項目を利用者に示す(issue #1235)。
 * 「データが0件」と「取得に失敗した」を区別できるようにするための共通表示。
 */
export function FetchErrorNotice({ labels }: { labels: string[] }) {
  if (labels.length === 0) {
    return null;
  }
  return (
    <div
      role="alert"
      className="rounded-lg border border-red-200 dark:border-red-900 bg-red-50 dark:bg-red-950 p-4 text-sm text-red-800 dark:text-red-200"
    >
      <ul className="list-disc pl-5">
        {labels.map((label) => (
          <li key={label}>{label}を取得できませんでした。時間をおいて再読み込みしてください。</li>
        ))}
      </ul>
    </div>
  );
}
