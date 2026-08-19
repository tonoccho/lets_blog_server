"use client";

import { useEffect, useState } from "react";

export const SAMPLE_CONTENT = "サンプルテキストです。ここに本文が入ります。";
const ATTR_PATTERN = /\{\{attr:([a-zA-Z0-9_]+)\}\}/g;

/**
 * プレビュー用に、サーバーでレンダリング済みのHTML({{content}}が実際の投稿と同じMarkdown変換済み、
 * lets-blog-renderedラッパー・プレフィックス付きCSSも適用済み)の{{attr:xxx}}をサンプル値へ置換し、
 * iframeのsrcDoc用に組み立てる。フォームのライブプレビュー(TemplateEditor)とタグ一覧の表示サンプル
 * (issue #297, #335)の両方で使う。
 */
export function buildPreviewSrcDoc(renderedHtml: string, css: string): string {
  const html = renderedHtml.replace(ATTR_PATTERN, (_match, key: string) => `サンプル${key}`);
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${css}</style></head><body>${html}</body></html>`;
}

/** テンプレート編集画面向けのプレビュー取得。実際の投稿と同じレンダリング結果を得るため、常にサーバーに問い合わせる(issue #335)。 */
export async function fetchPreview(
  projectId: number,
  htmlTemplate: string,
  cssContent: string,
  testContent: string
): Promise<{ html: string; css: string }> {
  const res = await fetch(`/projects/${projectId}/custom-tags/preview`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ htmlTemplate, cssContent, testContent }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new Error(body?.error || `プレビューの取得に失敗しました (${res.status})`);
  }
  return res.json();
}

/**
 * HTMLテンプレート/CSS入力とライブプレビューを担当する。編集対象(editing)が変わるたびに
 * 親側で`key`を変えて再マウントさせることで初期値を切り替える(useEffectでのprops→state同期は避ける)。
 */
export function TemplateEditor({
  projectId,
  initialHtml,
  initialCss,
}: {
  projectId: number;
  initialHtml: string;
  initialCss: string;
}) {
  const [htmlTemplateValue, setHtmlTemplateValue] = useState(initialHtml);
  const [cssContentValue, setCssContentValue] = useState(initialCss);
  const [testContent, setTestContent] = useState(SAMPLE_CONTENT);
  const [previewSrcDoc, setPreviewSrcDoc] = useState("");
  const [previewError, setPreviewError] = useState<string | null>(null);

  // HTML/CSS/テスト用コンテンツ変更のたびに即再描画すると入力のたびにリクエストが飛びカクつくため、300msデバウンスする
  useEffect(() => {
    let cancelled = false;
    const timer = setTimeout(() => {
      fetchPreview(projectId, htmlTemplateValue, cssContentValue, testContent)
        .then((result) => {
          if (cancelled) return;
          setPreviewError(null);
          setPreviewSrcDoc(buildPreviewSrcDoc(result.html, result.css));
        })
        .catch((err) => {
          if (cancelled) return;
          setPreviewError(err instanceof Error ? err.message : String(err));
        });
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [projectId, htmlTemplateValue, cssContentValue, testContent]);

  return (
    <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
      <div className="space-y-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">HTMLテンプレート</span>
          <textarea
            name="htmlTemplate"
            value={htmlTemplateValue}
            onChange={(e) => setHtmlTemplateValue(e.target.value)}
            required
            rows={6}
            placeholder='<div class="alert">{{content}}</div>'
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">CSS(任意、投稿本文には挿入されません。統合CSSの取得でのみ提供されます)</span>
          <textarea
            name="cssContent"
            value={cssContentValue}
            onChange={(e) => setCssContentValue(e.target.value)}
            rows={6}
            placeholder=".alert { color: red; border: 1px solid; padding: 0.5em; }"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
          />
        </label>
      </div>
      <div className="flex flex-col gap-3 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">
            テスト用コンテンツ({"{{content}}"}に差し込んでプレビューします。{"{{attr:xxx}}"}は自動でサンプル値に置き換わります)
          </span>
          <textarea
            value={testContent}
            onChange={(e) => setTestContent(e.target.value)}
            rows={2}
            placeholder={SAMPLE_CONTENT}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-1 flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">プレビュー(入力後300ms自動更新、実際の投稿と同じレンダリング結果)</span>
          {previewError && <p className="text-sm text-red-600 dark:text-red-400">{previewError}</p>}
          <iframe
            title="カスタムタグプレビュー"
            srcDoc={previewSrcDoc}
            sandbox="allow-same-origin"
            className="h-[220px] rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900"
          />
        </label>
      </div>
    </div>
  );
}

