import { getUserProfile } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";
import { SystemPreferencesForm } from "./SystemPreferencesForm";
import { GithubTokenForm } from "./GithubTokenForm";

const LINKS = [
  { label: "phpMyAdmin", url: "https://localhost/phpmyadmin/", description: "MySQLデータベースの管理" },
  { label: "ComfyUI", url: "https://localhost/comfyui/", description: "画像生成ワークフローUI" },
  { label: "PlantUML Server", url: "https://localhost/plantuml/", description: "図のプレビュー・検証用" },
  { label: "Ollama", url: "https://localhost/ollama/", description: "ローカルLLM API(UIなし)" },
];

// Node/ブラウザがIntl.supportedValuesOfに対応していない場合のフォールバック。
const FALLBACK_TIMEZONES = [
  "Asia/Tokyo",
  "Asia/Seoul",
  "Asia/Shanghai",
  "Asia/Singapore",
  "Asia/Kolkata",
  "Europe/London",
  "Europe/Paris",
  "Europe/Berlin",
  "America/New_York",
  "America/Chicago",
  "America/Los_Angeles",
  "UTC",
];

function getTimezoneOptions(): string[] {
  if (typeof Intl.supportedValuesOf === "function") {
    try {
      return Intl.supportedValuesOf("timeZone");
    } catch {
      return FALLBACK_TIMEZONES;
    }
  }
  return FALLBACK_TIMEZONES;
}

export default async function SystemPage() {
  const apiUrl = process.env.LETS_BLOG_API_URL ?? "https://localhost";
  const session = await requireSession();
  const profile = await getUserProfile(Number(session.user.id), {
    id: Number(session.user.id),
    role: session.user.role,
  });
  const timezoneOptions = getTimezoneOptions();

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">システム</h1>

      <SystemPreferencesForm
        locale={profile.locale ?? "ja_JP"}
        timezone={profile.timezone ?? "Asia/Tokyo"}
        timezoneOptions={timezoneOptions}
      />

      <GithubTokenForm githubTokenConfigured={profile.githubTokenConfigured} />

      <div className="rounded-lg border border-neutral-200 bg-white p-5">
        <h2 className="mb-2 font-medium">仲介APIサーバー</h2>
        <p className="text-sm text-neutral-600">
          このWeb管理画面が接続しているAPIサーバー: <code className="rounded bg-neutral-100 px-1">{apiUrl}</code>
        </p>
      </div>

      <div className="rounded-lg border border-neutral-200 bg-white p-5">
        <h2 className="mb-2 font-medium">VSCode拡張機能</h2>
        <p className="mb-3 text-sm text-neutral-600">
          Markdownでの記事執筆・投稿・記事計画ワークフローに使うVSCode拡張機能をダウンロードできます。
          クリック後にAPIサーバー側でビルドするため、初回は数十秒かかる場合があります。
        </p>
        <a
          href="/api/vscode-extension"
          download
          className="inline-block rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-700"
        >
          拡張機能をダウンロード (.vsix)
        </a>
      </div>

      <div className="rounded-lg border border-neutral-200 bg-white p-5">
        <h2 className="mb-3 font-medium">関連サービス</h2>
        <ul className="space-y-2">
          {LINKS.map((link) => (
            <li key={link.url} className="flex items-center justify-between text-sm">
              <div>
                <a href={link.url} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">
                  {link.label}
                </a>
                <span className="ml-2 text-neutral-500">{link.description}</span>
              </div>
              <span className="text-neutral-600">{link.url}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
