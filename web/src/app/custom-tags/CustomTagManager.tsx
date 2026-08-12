"use client";

import { useEffect, useState } from "react";

const SAMPLE_CONTENT = "サンプルテキストです。ここに本文が入ります。";
const ATTR_PATTERN = /\{\{attr:([a-zA-Z0-9_]+)\}\}/g;

/**
 * プレビュー用に{{content}}をサンプルテキストへ、{{attr:xxx}}をサンプル値へ置換したHTMLを組み立てる。
 * フォームのライブプレビュー(TemplateEditor)とタグ一覧の表示サンプル(issue #297)の両方で使う。
 */
export function buildPreviewSrcDoc(htmlTemplate: string, cssContent: string): string {
  const html = htmlTemplate
    .replaceAll("{{content}}", SAMPLE_CONTENT)
    .replace(ATTR_PATTERN, (_match, key: string) => `サンプル${key}`);
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><style>${cssContent}</style></head><body>${html}</body></html>`;
}

/**
 * HTMLテンプレート/CSS入力とライブプレビューを担当する。編集対象(editing)が変わるたびに
 * 親側で`key`を変えて再マウントさせることで初期値を切り替える(useEffectでのprops→state同期は避ける)。
 */
export function TemplateEditor({ initialHtml, initialCss }: { initialHtml: string; initialCss: string }) {
  const [htmlTemplateValue, setHtmlTemplateValue] = useState(initialHtml);
  const [cssContentValue, setCssContentValue] = useState(initialCss);
  const [previewSrcDoc, setPreviewSrcDoc] = useState(() => buildPreviewSrcDoc(initialHtml, initialCss));

  // HTML/CSS変更のたびに即再描画すると入力のたびにiframeが再構築されカクつくため、300msデバウンスする
  useEffect(() => {
    const timer = setTimeout(() => {
      setPreviewSrcDoc(buildPreviewSrcDoc(htmlTemplateValue, cssContentValue));
    }, 300);
    return () => clearTimeout(timer);
  }, [htmlTemplateValue, cssContentValue]);

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
          <span className="text-neutral-600 dark:text-neutral-400">CSS(任意、投稿本文には挿入されません。統合CSSダウンロードでのみ提供されます)</span>
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
      <div className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">
          プレビュー({"{{content}}"}/{"{{attr:xxx}}"}はサンプル値に置き換えて表示、入力後300ms自動更新)
        </span>
        <iframe
          title="カスタムタグプレビュー"
          srcDoc={previewSrcDoc}
          sandbox="allow-same-origin"
          className="h-[268px] rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900"
        />
      </div>
    </div>
  );
}

