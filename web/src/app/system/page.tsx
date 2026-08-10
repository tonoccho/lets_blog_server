import { getUserProfile } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";
import { GithubTokenForm } from "./GithubTokenForm";

const LINKS = [
  { label: "phpMyAdmin", url: "https://localhost/phpmyadmin/", description: "MySQLデータベースの管理" },
  { label: "ComfyUI", url: "https://localhost/comfyui/", description: "画像生成ワークフローUI" },
  { label: "PlantUML Server", url: "https://localhost/plantuml/", description: "図のプレビュー・検証用" },
  { label: "Ollama", url: "https://localhost/ollama/", description: "ローカルLLM API(UIなし)" },
];

export default async function SystemPage() {
  const apiUrl = process.env.LETS_BLOG_API_URL ?? "https://localhost";
  const session = await requireSession();
  const profile = await getUserProfile(Number(session.user.id), {
    id: Number(session.user.id),
    role: session.user.role,
  });

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">システム</h1>

      <GithubTokenForm githubTokenConfigured={profile.githubTokenConfigured} />

      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <h2 className="mb-2 font-medium">仲介APIサーバー</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          このWeb管理画面が接続しているAPIサーバー: <code className="rounded bg-neutral-100 dark:bg-neutral-800 px-1">{apiUrl}</code>
        </p>
      </div>

      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <h2 className="mb-3 font-medium">関連サービス</h2>
        <ul className="space-y-2">
          {LINKS.map((link) => (
            <li key={link.url} className="flex items-center justify-between text-sm">
              <div>
                <a href={link.url} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">
                  {link.label}
                </a>
                <span className="ml-2 text-neutral-500 dark:text-neutral-400">{link.description}</span>
              </div>
              <span className="text-neutral-600 dark:text-neutral-400">{link.url}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
