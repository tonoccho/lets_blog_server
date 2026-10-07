"use client";

import { useActionState, useEffect, useState, useTransition } from "react";
import type { EmbedTagType, TagDesignPreset, TagDesignSetting } from "@/lib/apiClient";
import type { TagDesignJobResult } from "@/lib/llmJobResults";
import { saveTagDesignSettingAction, generateTagDesignAction, TagDesignFormState } from "./actions";

const initialState: TagDesignFormState = {};

interface Colors {
  backgroundColor: string;
  textColor: string;
  accentColor: string;
}

const TAG_LABELS: Record<EmbedTagType, string> = {
  TOC: "目次",
  BLOGCARD: "ブログカード",
  AMAZON: "Amazon商品カード",
};

const TAG_SYNTAX: Record<EmbedTagType, string> = {
  TOC: "[toc]",
  BLOGCARD: "[blogcard URL]",
  AMAZON: "[amazon URL]",
};

/** 実際のレンダリング(TocStyleRenderService等)が出力するクラス名。CSSプレビュー反映に使う。 */
const TAG_CLASS: Record<EmbedTagType, string> = {
  TOC: "lb-toc-list",
  BLOGCARD: "lb-blogcard",
  AMAZON: "lb-amazon-card",
};

/**
 * HTMLテンプレートで使えるプレースホルダ一覧(バックエンドのEmbedTagTemplateRenderer/
 * TocStyleRenderService.applyHtmlTemplateと対応)。TOCのみ、目次全体を表す{{toc}}という
 * 1つのプレースホルダで外枠だけをカスタマイズする方式。
 */
const TAG_PLACEHOLDERS: Record<EmbedTagType, string[]> = {
  TOC: ["toc"],
  BLOGCARD: ["title", "description", "siteName", "url", "imageUrl"],
  AMAZON: ["productName", "price", "productUrl", "imageUrl"],
};

/** プレビュー用のサンプル値。実データ取得時に空になりうるimageUrlはあえて空のままにする。 */
const SAMPLE_VALUES: Record<EmbedTagType, Record<string, string>> = {
  TOC: {},
  BLOGCARD: {
    title: "サンプル記事タイトル",
    description: "記事の説明文がここに入ります。",
    siteName: "example.com",
    url: "https://example.com/sample-article",
    imageUrl: "",
  },
  AMAZON: {
    productName: "サンプル商品名",
    price: "￥1,980",
    productUrl: "https://www.amazon.co.jp/dp/SAMPLE",
    imageUrl: "",
  },
};

/**
 * バックエンドの標準HTML構造(BlogCardTagRenderService等)と同じ形をプレースホルダ化したもの。
 * 未保存タグの初期表示、および編集画面でCSS/HTMLを空にした際の「標準に戻す」動作の基準にもなる。
 * 実際のレンダリングは常にimage要素を出す(取得失敗時に空の背景画像になるだけ)点が、
 * 画像なし時に要素自体を省略する従来のハードコード実装とわずかに異なる(許容している差分)。
 */
const DEFAULT_HTML_TEMPLATE: Record<EmbedTagType, string> = {
  TOC: "{{toc}}",
  BLOGCARD:
    '<a class="lb-blogcard" href="{{url}}" target="_blank" rel="noopener noreferrer">' +
    '<span class="lb-blogcard-thumb" style="background-image:url(\'{{imageUrl}}\')"></span>' +
    '<span class="lb-blogcard-body">' +
    '<span class="lb-blogcard-title">{{title}}</span>' +
    '<span class="lb-blogcard-description">{{description}}</span>' +
    '<span class="lb-blogcard-site">{{siteName}}</span>' +
    "</span></a>",
  AMAZON:
    '<a class="lb-amazon-card" href="{{productUrl}}" target="_blank" rel="noopener noreferrer nofollow sponsored">' +
    '<span class="lb-amazon-card-thumb" style="background-image:url(\'{{imageUrl}}\')"></span>' +
    '<span class="lb-amazon-card-body">' +
    '<span class="lb-amazon-card-name">{{productName}}</span>' +
    '<span class="lb-amazon-card-price">{{price}}</span>' +
    '<span class="lb-amazon-card-cta">Amazonで見る</span>' +
    "</span></a>",
};

/**
 * バックエンドの標準CSS生成(Toc/BlogCard/AmazonTagRenderService.buildStyle)と同じ内容を
 * 背景色/テキスト色/アクセントカラーから組み立てる。保存済みCSSがないタグの初期表示、
 * および色ピッカー/プリセット変更時の自動再生成に使う。
 */
function buildDefaultCss(tagType: EmbedTagType, colors: Colors): string {
  switch (tagType) {
    case "TOC":
      return (
        `.${TAG_CLASS.TOC}{list-style:disc;list-style-position:inside;margin:1em 0;padding:12px 16px;` +
        `border-radius:8px;background:${colors.backgroundColor};}\n` +
        `.${TAG_CLASS.TOC} ul{list-style:disc;list-style-position:inside;margin:0;padding-left:20px;}\n` +
        `.${TAG_CLASS.TOC} li{margin:4px 0;}\n` +
        `.${TAG_CLASS.TOC} li::marker{color:#000;}\n` +
        `.${TAG_CLASS.TOC} a{color:${colors.textColor};text-decoration:none;}\n` +
        `.${TAG_CLASS.TOC} a:hover{color:${colors.accentColor};text-decoration:underline;}`
      );
    case "BLOGCARD":
      return (
        ".lb-blogcard{display:flex;align-items:stretch;border:1px solid #e0e0e0;" +
        `border-left:4px solid ${colors.accentColor};border-radius:8px;overflow:hidden;` +
        `text-decoration:none;background:${colors.backgroundColor};color:${colors.textColor};` +
        "max-width:100%;margin:1em 0;transition:box-shadow .15s ease;}\n" +
        ".lb-blogcard:hover{box-shadow:0 2px 8px rgba(0,0,0,.12);}\n" +
        ".lb-blogcard-thumb{flex:0 0 120px;background-size:cover;background-position:center;" +
        "background-color:#f2f2f2;}\n" +
        ".lb-blogcard-body{flex:1 1 auto;min-width:0;padding:12px 16px;display:flex;" +
        "flex-direction:column;gap:4px;}\n" +
        ".lb-blogcard-title{font-weight:600;font-size:1em;overflow:hidden;text-overflow:ellipsis;" +
        "white-space:nowrap;}\n" +
        ".lb-blogcard-description{font-size:.875em;opacity:.75;overflow:hidden;display:-webkit-box;" +
        "-webkit-line-clamp:2;-webkit-box-orient:vertical;}\n" +
        ".lb-blogcard-site{font-size:.75em;opacity:.6;margin-top:auto;}"
      );
    case "AMAZON":
      return (
        ".lb-amazon-card{display:flex;align-items:stretch;border:1px solid #e0e0e0;" +
        `border-radius:8px;overflow:hidden;text-decoration:none;color:${colors.textColor};` +
        `max-width:100%;margin:1em 0;background:${colors.backgroundColor};` +
        "transition:box-shadow .15s ease;}\n" +
        ".lb-amazon-card:hover{box-shadow:0 2px 8px rgba(0,0,0,.12);}\n" +
        ".lb-amazon-card-thumb{flex:0 0 120px;background-size:contain;background-repeat:no-repeat;" +
        "background-position:center;background-color:#fff;}\n" +
        ".lb-amazon-card-body{flex:1 1 auto;min-width:0;padding:12px 16px;display:flex;" +
        "flex-direction:column;gap:4px;}\n" +
        ".lb-amazon-card-name{font-weight:600;font-size:1em;overflow:hidden;display:-webkit-box;" +
        "-webkit-line-clamp:2;-webkit-box-orient:vertical;}\n" +
        `.lb-amazon-card-price{font-size:1.05em;font-weight:700;color:${colors.accentColor};}\n` +
        `.lb-amazon-card-cta{font-size:.8em;color:#fff;background:${colors.accentColor};` +
        "border-radius:4px;padding:4px 10px;align-self:flex-start;margin-top:auto;}"
      );
    default:
      return "";
  }
}

function substitutePlaceholders(template: string, values: Record<string, string>): string {
  return template.replace(/\{\{([a-zA-Z0-9_]+)}}/g, (_match, key: string) => values[key] ?? "");
}

function buildSampleTocList(tagClass: string): string {
  return (
    `<ul class="${tagClass}">` +
    '<li><a href="#">セクション1</a><ul><li><a href="#">セクション1-1</a></li></ul></li>' +
    '<li><a href="#">セクション2</a></li>' +
    "</ul>"
  );
}

/**
 * 実際のレンダリングと同じく、HTMLはクラス付きのマークアップのみ・見た目はすべてCSSで決まる
 * (バックエンドのEmbedTagTemplateRenderer/applyHtmlTemplate + buildStyleと同じ構成)。
 * htmlTemplate/customCssは常に何らかの値を持つ(未保存タグでも標準相当の内容で初期化されるため)。
 */
function buildPreviewHtml(tagType: EmbedTagType, customCss: string, htmlTemplate: string): string {
  const tagClass = TAG_CLASS[tagType];
  const template = htmlTemplate.trim() || DEFAULT_HTML_TEMPLATE[tagType];
  const body =
    tagType === "TOC"
      ? substitutePlaceholders(template, { toc: buildSampleTocList(tagClass) })
      : substitutePlaceholders(template, SAMPLE_VALUES[tagType]);
  const styleTag = customCss.trim() ? `<style>${customCss}</style>` : "";
  return `<!DOCTYPE html><html><head><meta charset="utf-8">${styleTag}</head><body style="margin:12px;">${body}</body></html>`;
}

function presetLabel(presets: TagDesignPreset[], presetId: string): string {
  return presets.find((preset) => preset.id === presetId)?.label ?? presetId;
}

function ColorSwatch({ color }: { color: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 font-mono text-xs text-neutral-500 dark:text-neutral-400">
      <span
        className="inline-block h-3 w-3 rounded-sm border border-neutral-300 dark:border-neutral-700"
        style={{ backgroundColor: color }}
      />
      {color}
    </span>
  );
}

interface GeneratedTagDesign {
  htmlTemplate: string;
  cssContent: string;
}

/**
 * 組み込みタグのデザインをAIで生成するフォーム(issue #183)。生成は非同期ジョブとして処理キューに積まれ
 * (issue #1409)、完了を待たず、生成と同時に保存もしない。処理キューの「結果を見る」でこの画面へ戻ると、
 * `pendingResult`(ジョブの結果)が未保存として示され、「保存」を押したときに初めて既存の保存先へ書き込まれる。
 * 保存前に手直ししたいときは「この結果を適用」で編集フォームへ反映できる。
 */
function TagDesignGenerationForm({
  projectId,
  tagType,
  pendingResult,
  onApply,
  onSave,
}: {
  projectId: number | null;
  tagType: EmbedTagType;
  pendingResult: (TagDesignJobResult & { jobId: number }) | null;
  onApply: (result: GeneratedTagDesign) => void;
  onSave: (result: GeneratedTagDesign) => Promise<string | null>;
}) {
  const [prompt, setPrompt] = useState("");
  const [isPending, startTransition] = useTransition();
  const [isSaving, startSaveTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [queuedJobId, setQueuedJobId] = useState<number | null>(null);
  const [resolved, setResolved] = useState<"applied" | "saved" | null>(null);

  function handleGenerate() {
    setError(null);
    setQueuedJobId(null);
    startTransition(async () => {
      const response = await generateTagDesignAction(projectId, tagType, prompt);
      if (response.error) {
        setError(response.error);
        return;
      }
      if (response.status === "failed") {
        // 実行枠と待ち行列が満杯のとき、ジョブは作られた上で failed として返る。
        setError("タグデザイン生成の待ち行列が満杯です。しばらくしてからもう一度要求してください。");
        return;
      }
      setQueuedJobId(response.jobId ?? null);
      setPrompt("");
    });
  }

  function handleApply() {
    if (!pendingResult) return;
    onApply(pendingResult);
    setResolved("applied");
  }

  function handleSave() {
    if (!pendingResult) return;
    setError(null);
    startSaveTransition(async () => {
      const saveError = await onSave(pendingResult);
      if (saveError) {
        setError(saveError);
        return;
      }
      setResolved("saved");
    });
  }

  // 保存・適用したら、同じ結果を「未保存」として出し続けない。
  const showResult = pendingResult !== null && resolved === null;

  return (
    <div className="space-y-2 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800/50 p-4">
      <h2 className="text-sm font-medium">AIでデザインを生成</h2>
      <p className="text-xs text-neutral-600 dark:text-neutral-400">
        AIに自然言語で見た目の要望を送信すると、CSS(必要であればHTMLテンプレートも)が生成されます。
        生成は処理キューで進み、生成結果はすぐには保存されません。完了後、処理キューの「結果を見る」から内容を
        確認し、「保存」を押したときに保存されます。
      </p>
      <textarea
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
        rows={3}
        placeholder="例: 背景を淡いグレーにして、影を付けてカードっぽくしてください。"
        className="w-full rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
      />
      {error && <p className="text-sm text-red-600">{error}</p>}
      <button
        type="button"
        onClick={handleGenerate}
        disabled={isPending || !prompt.trim()}
        className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {isPending ? "生成中…" : "生成"}
      </button>
      {queuedJobId !== null && (
        <p
          data-testid="tag-design-queued"
          data-job-id={queuedJobId}
          className="rounded bg-green-50 p-2 text-xs text-green-800"
        >
          生成を要求しました。処理キューに追加されました。完了後、処理キューの「結果を見る」から内容を確認し、
          「保存」を押すと保存されます(この時点ではまだ保存されていません)。
        </p>
      )}
      {resolved === "saved" && <p className="text-sm text-green-600">保存しました。</p>}

      {showResult && (
        <div
          data-testid="tag-design-generated"
          data-job-id={pendingResult.jobId}
          className="space-y-2 rounded border border-yellow-200 dark:border-yellow-900 bg-yellow-50 dark:bg-yellow-950/30 p-3"
        >
          <p className="text-sm font-medium text-yellow-800 dark:text-yellow-400">
            生成結果(ジョブ #{pendingResult.jobId})。まだ保存されていません。
          </p>
          <pre className="overflow-x-auto rounded bg-white dark:bg-neutral-900 p-2 font-mono text-xs text-neutral-600 dark:text-neutral-400">
            {pendingResult.cssContent}
          </pre>
          {pendingResult.htmlTemplate && (
            <pre className="overflow-x-auto rounded bg-white dark:bg-neutral-900 p-2 font-mono text-xs text-neutral-600 dark:text-neutral-400">
              {pendingResult.htmlTemplate}
            </pre>
          )}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={handleSave}
              disabled={isSaving}
              className="rounded bg-green-600 px-4 py-2 text-sm text-white hover:bg-green-700 disabled:opacity-50"
            >
              {isSaving ? "保存中…" : "保存"}
            </button>
            <button
              type="button"
              onClick={handleApply}
              className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm"
            >
              この結果を適用
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function TagDesignEditor({
  projectId,
  tagType,
  presets,
  initialSetting,
  jobResult,
}: {
  projectId: number | null;
  tagType: EmbedTagType;
  presets: TagDesignPreset[];
  initialSetting: TagDesignSetting;
  /** 処理キューの「結果を見る」から開いた、このタグ種別の生成結果(未保存。issue #1409)。 */
  jobResult: (TagDesignJobResult & { jobId: number }) | null;
}) {
  const [state, formAction, pending] = useActionState(saveTagDesignSettingAction, initialState);
  const [presetId, setPresetId] = useState(initialSetting.presetId);
  const [colors, setColors] = useState<Colors>({
    backgroundColor: initialSetting.backgroundColor,
    textColor: initialSetting.textColor,
    accentColor: initialSetting.accentColor,
  });
  const [customCss, setCustomCss] = useState(
    () => initialSetting.customCss || buildDefaultCss(tagType, colors)
  );
  // CSSがまだ色ピッカー由来のまま(手で編集されていない)かどうか。trueの間は色/プリセット変更のたびに再生成する。
  const [cssAutoGenerated, setCssAutoGenerated] = useState(!initialSetting.customCss);
  /**
   * issue #1144: SSRされた直後のCSS欄は、ReactのonChangeがまだアタッチされていない
   * (クライアントバンドルの読み込み・実行 = ハイドレーションが完了していない)状態でも、
   * 見た目上は普通に操作できる<textarea>になっている。ページ遷移直後にすぐ入力すると、
   * その窓の間はブラウザがイベントハンドラ無しでネイティブにDOMへ書き込むため、
   * ハイドレーション完了後に初めて読み取られるDOM値が標準/保存済みCSSと入力値の
   * 混ざったものになり得る(#940のQA中に発見。#1143のAI生成フォームの入力欠落と
   * 同種の「ハイドレーション完了前に入力を受け付けてしまう」ことが根本原因の競合)。
   *
   * ThemeSwitcher.tsx と同じ「mountedガード」を採用し、マウント完了
   * (= ハイドレーション完了)までCSS欄をdisabledにする。ブラウザは無効化された
   * フォーム部品へキー入力を配送しないため、競合の入力口自体を物理的に無くせる。
   * (LanguageSwitcher.tsxはissue #800/#721でこのガードを外している。locale解決を
   * I18nProvider のSSRスナップショットへ一本化したことで不要になったためで、
   * この競合とは別の理由。ここでの前例はThemeSwitcher.tsxのみ。)
   *
   * htmlTemplate欄もcustomCssと同じ<form>内で同じSSR/ハイドレーション構造を持ち、
   * 同じ経路(formAction)で保存されるため、同じガードを適用している。
   */
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);
  const [htmlTemplate, setHtmlTemplate] = useState(initialSetting.htmlTemplate || DEFAULT_HTML_TEMPLATE[tagType]);
  const [previewSrcDoc, setPreviewSrcDoc] = useState(() => buildPreviewHtml(tagType, customCss, htmlTemplate));

  // CSS・HTML変更のたびに即再描画すると入力のたびにiframeが再構築されカクつくため、300msデバウンスする
  useEffect(() => {
    const timer = setTimeout(() => {
      setPreviewSrcDoc(buildPreviewHtml(tagType, customCss, htmlTemplate));
    }, 300);
    return () => clearTimeout(timer);
  }, [tagType, customCss, htmlTemplate]);

  function updateColors(next: Colors) {
    setColors(next);
    if (cssAutoGenerated) {
      setCustomCss(buildDefaultCss(tagType, next));
    }
  }

  function applyPreset(preset: TagDesignPreset) {
    setPresetId(preset.id);
    updateColors({
      backgroundColor: preset.backgroundColor,
      textColor: preset.textColor,
      accentColor: preset.accentColor,
    });
  }

  function handleCssChange(value: string) {
    setCustomCss(value);
    setCssAutoGenerated(false);
  }

  function handleGenerated(result: { htmlTemplate: string; cssContent: string }) {
    setCustomCss(result.cssContent);
    setCssAutoGenerated(false);
    if (result.htmlTemplate) {
      setHtmlTemplate(result.htmlTemplate);
    }
  }

  /**
   * 生成結果の「保存」(issue #1409)。現在のプリセット・色に生成したCSS/HTMLを添えて、編集フォームの保存と同じ
   * 既存の保存アクションへ渡す。HTMLが空の結果(構造変更なし)は現在のHTMLテンプレートを保つ。成功したら編集欄へも反映する。
   * 失敗の理由を返す(成功は null)。
   */
  async function handleSaveGenerated(result: GeneratedTagDesign): Promise<string | null> {
    const formData = new FormData();
    formData.set("projectId", projectId === null ? "" : String(projectId));
    formData.set("tagType", tagType);
    formData.set("presetId", presetId);
    formData.set("backgroundColor", colors.backgroundColor);
    formData.set("textColor", colors.textColor);
    formData.set("accentColor", colors.accentColor);
    formData.set("customCss", result.cssContent);
    formData.set("htmlTemplate", result.htmlTemplate || htmlTemplate);
    const saveState = await saveTagDesignSettingAction({}, formData);
    if (saveState.error) {
      return saveState.error;
    }
    handleGenerated(result);
    return null;
  }

  return (
    <div className="space-y-4">
      <TagDesignGenerationForm
        projectId={projectId}
        tagType={tagType}
        pendingResult={jobResult}
        onApply={handleGenerated}
        onSave={handleSaveGenerated}
      />

      <form
        action={formAction}
        className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
      >
        <h2 className="font-medium">
          {TAG_LABELS[tagType]}のデザインを編集: <code className="font-mono text-sm">{TAG_SYNTAX[tagType]}</code>
        </h2>
        {/* projectIdがnull(グローバル既定、#763)のときは空文字を送り、サーバー側で
            グローバル扱いに倒す。value={null} だとReactが属性自体を落としてしまう。 */}
        <input type="hidden" name="projectId" value={projectId ?? ""} />
        <input type="hidden" name="tagType" value={tagType} />
      <input type="hidden" name="presetId" value={presetId} />

      <div className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">
          プリセット・色(下のCSS欄を直接編集していない間は、選択のたびにCSSへ自動反映されます)
        </span>
        <div className="flex flex-wrap gap-2">
          {presets.map((preset) => (
            <button
              key={preset.id}
              type="button"
              onClick={() => applyPreset(preset)}
              className={`rounded border px-3 py-1.5 text-sm ${
                presetId === preset.id
                  ? "border-neutral-900 dark:border-neutral-100 font-medium"
                  : "border-neutral-300 dark:border-neutral-700"
              }`}
            >
              {preset.label}
            </button>
          ))}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">背景色</span>
          <input
            type="color"
            name="backgroundColor"
            value={colors.backgroundColor}
            onChange={(e) => updateColors({ ...colors, backgroundColor: e.target.value })}
            className="h-9 w-full rounded border border-neutral-300 dark:border-neutral-700"
          />
          <span className="text-xs text-neutral-500">{colors.backgroundColor}</span>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">テキスト色</span>
          <input
            type="color"
            name="textColor"
            value={colors.textColor}
            onChange={(e) => updateColors({ ...colors, textColor: e.target.value })}
            className="h-9 w-full rounded border border-neutral-300 dark:border-neutral-700"
          />
          <span className="text-xs text-neutral-500">{colors.textColor}</span>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">アクセントカラー</span>
          <input
            type="color"
            name="accentColor"
            value={colors.accentColor}
            onChange={(e) => updateColors({ ...colors, accentColor: e.target.value })}
            className="h-9 w-full rounded border border-neutral-300 dark:border-neutral-700"
          />
          <span className="text-xs text-neutral-500">{colors.accentColor}</span>
        </label>
      </div>

      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">
          CSS(組み込みタグの見た目を決めるCSSです。標準のCSSがあらかじめ入力されています。空にすると標準に戻ります)
        </span>
        <textarea
          name="customCss"
          value={customCss}
          onChange={(e) => handleCssChange(e.target.value)}
          disabled={!mounted}
          rows={8}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
        />
      </label>

      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">
          HTMLテンプレート(組み込みタグのHTML構造です。標準の構造があらかじめ入力されています。空にすると標準に戻ります)
        </span>
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs text-neutral-500 dark:text-neutral-400">利用可能なプレースホルダ:</span>
          {TAG_PLACEHOLDERS[tagType].map((placeholder) => (
            <code
              key={placeholder}
              className="rounded bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5 font-mono text-xs text-neutral-700 dark:text-neutral-300"
            >
              {`{{${placeholder}}}`}
            </code>
          ))}
        </div>
        <textarea
          name="htmlTemplate"
          value={htmlTemplate}
          onChange={(e) => setHtmlTemplate(e.target.value)}
          disabled={!mounted}
          rows={6}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
        />
      </label>

      <div className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">プレビュー(サンプルデータ、入力後300ms自動更新)</span>
        <iframe
          title={`${TAG_LABELS[tagType]}プレビュー`}
          srcDoc={previewSrcDoc}
          sandbox="allow-same-origin"
          className="h-[268px] rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900"
        />
      </div>

      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">保存しました。</p>}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存"}
      </button>
      </form>
    </div>
  );
}

export function TagDesignSettingsPanel({
  projectId,
  presets,
  settings,
  jobResult,
}: {
  projectId: number | null;
  presets: TagDesignPreset[];
  settings: TagDesignSetting[];
  /** 処理キューの「結果を見る」から開いた、タグデザイン生成ジョブの結果(未保存。issue #1409)。 */
  jobResult?: (TagDesignJobResult & { jobId: number }) | null;
}) {
  // 結果を見に来たときは、生成されたタグ種別の編集画面を開く(一覧に無ければ先頭)。
  const initialType =
    jobResult && settings.some((setting) => setting.tagType === jobResult.tagType)
      ? jobResult.tagType
      : (settings[0]?.tagType ?? "TOC");
  const [editingType, setEditingType] = useState<EmbedTagType>(initialType);
  const editingSetting = settings.find((setting) => setting.tagType === editingType) ?? settings[0];

  return (
    <div className="space-y-8">
      <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
            <tr>
              <th className="px-4 py-2">タグ</th>
              <th className="px-4 py-2">プリセット</th>
              <th className="px-4 py-2">背景色</th>
              <th className="px-4 py-2">テキスト色</th>
              <th className="px-4 py-2">アクセントカラー</th>
              <th className="px-4 py-2">CSS</th>
              <th className="px-4 py-2">HTMLテンプレート</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {settings.map((setting) => (
              <tr
                key={setting.tagType}
                className="border-b border-neutral-100 dark:border-neutral-800 last:border-0 align-top cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors"
              >
                <td className="px-4 py-2">
                  <div className="font-medium">{TAG_LABELS[setting.tagType]}</div>
                  <div className="font-mono text-xs text-neutral-500 dark:text-neutral-400">
                    {TAG_SYNTAX[setting.tagType]}
                  </div>
                </td>
                <td className="px-4 py-2">
                  <span className="inline-block rounded px-2 py-0.5 text-xs font-medium bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400">
                    {presetLabel(presets, setting.presetId)}
                  </span>
                </td>
                <td className="px-4 py-2">
                  <ColorSwatch color={setting.backgroundColor} />
                </td>
                <td className="px-4 py-2">
                  <ColorSwatch color={setting.textColor} />
                </td>
                <td className="px-4 py-2">
                  <ColorSwatch color={setting.accentColor} />
                </td>
                <td className="px-4 py-2 font-mono text-xs text-neutral-500 dark:text-neutral-400">
                  {setting.customCss ? (
                    <code className="whitespace-pre-wrap break-all">{setting.customCss}</code>
                  ) : (
                    <span className="text-neutral-500 dark:text-neutral-400">標準</span>
                  )}
                </td>
                <td className="px-4 py-2 font-mono text-xs text-neutral-500 dark:text-neutral-400">
                  {setting.htmlTemplate ? (
                    <code className="whitespace-pre-wrap break-all">{setting.htmlTemplate}</code>
                  ) : (
                    <span className="text-neutral-500 dark:text-neutral-400">標準</span>
                  )}
                </td>
                <td className="px-4 py-2 text-right whitespace-nowrap">
                  <button
                    type="button"
                    onClick={() => setEditingType(setting.tagType)}
                    className="text-sm text-blue-600 hover:underline"
                  >
                    編集
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editingSetting && (
        <TagDesignEditor
          key={editingSetting.tagType}
          projectId={projectId}
          tagType={editingSetting.tagType}
          presets={presets}
          initialSetting={editingSetting}
          jobResult={jobResult && jobResult.tagType === editingSetting.tagType ? jobResult : null}
        />
      )}
    </div>
  );
}
