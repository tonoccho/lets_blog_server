import Link from "next/link";
import { Settings } from "lucide-react";
import { listAiConnections, type AiConnection, type AiConnectionProvider } from "@/lib/apiClient";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";

/** 常に表示する4行。APIが1件欠いても行が消えないよう、この定数を基準に描画する。 */
const PROVIDERS: { provider: AiConnectionProvider; label: string; apiKey: boolean }[] = [
  { provider: "OLLAMA", label: "Ollama", apiKey: false },
  { provider: "COMFYUI", label: "ComfyUI", apiKey: false },
  { provider: "OPENAI", label: "ChatGPT", apiKey: true },
  { provider: "CLAUDE", label: "Claude", apiKey: true },
];

const CARD = "rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 space-y-3";

/** 利用可能 = 設定済み かつ status が NORMAL / WARNING。 */
function isAvailable(connection: AiConnection | undefined): boolean {
  return !!connection && connection.configured && (connection.status === "NORMAL" || connection.status === "WARNING");
}

function targetInfo(connection: AiConnection | undefined, apiKey: boolean): string {
  if (apiKey) {
    return connection?.configured ? "APIキー設定済み" : "APIキー未設定";
  }
  return connection?.targetUrl ?? "未設定";
}

/** ダッシュボードの「AI接続状況」ウィジェット(issue #1501)。利用可否に関わらず各行に設定タブへの歯車リンクを出す(issue #1672)。 */
export function AiConnectionWidgetView({
  projectId,
  connections,
  failed,
}: {
  projectId: number;
  connections: AiConnection[];
  /** 取得に失敗したとき true。「0件」「全て利用不可」と区別して失敗を示す。 */
  failed: boolean;
}) {
  return (
    <div className={CARD}>
      <h2 className="font-medium">AI接続状況</h2>
      {failed ? (
        <FetchErrorNotice labels={["AI接続状況"]} />
      ) : (
        <ul className="divide-y divide-neutral-200 dark:divide-neutral-800 text-sm">
          {PROVIDERS.map(({ provider, label, apiKey }) => {
            const connection = connections.find((c) => c.provider === provider);
            const available = isAvailable(connection);
            return (
              <li key={provider} aria-label={label} className="flex items-center justify-between gap-3 py-2">
                <div>
                  <span>{label}</span>
                  <span className="ml-2 text-neutral-600 dark:text-neutral-400">{targetInfo(connection, apiKey)}</span>
                </div>
                <div className="flex items-center gap-3">
                  <span
                    className={
                      available
                        ? "rounded px-2 py-0.5 bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-200"
                        : "rounded px-2 py-0.5 bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200"
                    }
                  >
                    {available ? "利用可能" : "利用不可"}
                  </span>
                  <Link
                    href={`/projects/${projectId}?tab=settings`}
                    aria-label={`${label}の接続設定`}
                    className="text-neutral-600 hover:text-neutral-900 dark:text-neutral-400 dark:hover:text-neutral-100"
                  >
                    <Settings aria-hidden="true" className="h-4 w-4" />
                  </Link>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** `<Suspense>` の内側で取得する。取得が遅くても他のウィジェットの表示を止めない。 */
export async function AiConnectionWidget({ projectId }: { projectId: number }) {
  let connections: AiConnection[] = [];
  let failed = false;
  try {
    connections = await listAiConnections(projectId);
  } catch (err) {
    console.error(`[projects/${projectId}/dashboard] AI接続状況の取得に失敗しました:`, err);
    failed = true;
  }
  return <AiConnectionWidgetView projectId={projectId} connections={connections} failed={failed} />;
}

export function AiConnectionWidgetFallback() {
  return (
    <div className={CARD}>
      <h2 className="font-medium">AI接続状況</h2>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">読み込み中…</p>
    </div>
  );
}
