const LINKS = [
  { label: "phpMyAdmin", url: "http://localhost:8081", description: "MySQLデータベースの管理" },
  { label: "ComfyUI", url: "http://localhost:8188", description: "画像生成ワークフローUI" },
  { label: "PlantUML Server", url: "http://localhost:8085", description: "図のプレビュー・検証用" },
  { label: "Ollama", url: "http://localhost:11434", description: "ローカルLLM API(UIなし)" },
];

export default function SystemPage() {
  const apiUrl = process.env.LETS_BLOG_API_URL ?? "http://localhost:8080";

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">システム</h1>

      <div className="rounded-lg border border-neutral-200 bg-white p-5">
        <h2 className="mb-2 font-medium">仲介APIサーバー</h2>
        <p className="text-sm text-neutral-600">
          このWeb管理画面が接続しているAPIサーバー: <code className="rounded bg-neutral-100 px-1">{apiUrl}</code>
        </p>
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
