import Link from "next/link";

/**
 * ダッシュボードのウィジェット枠(issue #385)。GA/AdSense/Amazonアソシエイト/ソーシャル統計は
 * 別issue(#386〜#388, #390)でデータ取得を実装するため、ここでは未設定時の説明文と
 * 設定ページへのリンクのみを表示するプレースホルダーとして描画する。
 * settingsHref未指定時(連携先が未確定な場合)はリンクの代わりにラベルのみ表示する。
 */
export function DashboardWidgetSlot({
  title,
  description,
  configured,
  settingsHref,
  settingsLabel,
  children,
}: {
  title: string;
  description: string;
  configured: boolean;
  settingsHref?: string;
  settingsLabel: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 space-y-3">
      <h2 className="font-medium">{title}</h2>
      {configured && children ? (
        children
      ) : (
        <div className="space-y-2">
          <p className="text-sm text-neutral-600 dark:text-neutral-400">{description}</p>
          {settingsHref ? (
            <Link href={settingsHref} className="text-sm text-blue-600 dark:text-blue-400 hover:underline">
              {settingsLabel}
            </Link>
          ) : (
            <span className="text-sm text-neutral-400 dark:text-neutral-600">{settingsLabel}</span>
          )}
        </div>
      )}
    </div>
  );
}
