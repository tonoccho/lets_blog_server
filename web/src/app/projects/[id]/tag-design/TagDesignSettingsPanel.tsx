"use client";

import { useActionState, useEffect, useState } from "react";
import type { EmbedTagType, TagDesignPreset, TagDesignSetting } from "@/lib/apiClient";
import { saveTagDesignSettingAction, TagDesignFormState } from "./actions";

const initialState: TagDesignFormState = {};

interface Colors {
  backgroundColor: string;
  textColor: string;
  accentColor: string;
}

const TAG_LABELS: Record<EmbedTagType, string> = {
  TOC: "目次 ([toc])",
  BLOGCARD: "ブログカード ([blogcard URL])",
  AMAZON: "Amazon商品カード ([amazon URL])",
};

function buildPreviewHtml(tagType: EmbedTagType, colors: Colors): string {
  const body = (() => {
    switch (tagType) {
      case "TOC":
        return `<ul style="list-style:none;margin:0;padding:12px 16px;border-radius:8px;background:${colors.backgroundColor};font-family:sans-serif;">
          <li style="margin:4px 0;"><a href="#" style="color:${colors.textColor};text-decoration:none;">セクション1</a></li>
          <li style="margin:4px 0 4px 16px;"><a href="#" style="color:${colors.accentColor};text-decoration:underline;">セクション1-1(ホバー時の色)</a></li>
          <li style="margin:4px 0;"><a href="#" style="color:${colors.textColor};text-decoration:none;">セクション2</a></li>
        </ul>`;
      case "BLOGCARD":
        return `<a style="display:flex;align-items:stretch;border:1px solid #e0e0e0;border-left:4px solid ${colors.accentColor};border-radius:8px;overflow:hidden;text-decoration:none;background:${colors.backgroundColor};color:${colors.textColor};font-family:sans-serif;">
          <div style="flex:0 0 96px;background:#f2f2f2;"></div>
          <div style="flex:1 1 auto;min-width:0;padding:10px 14px;">
            <div style="font-weight:600;">サンプル記事タイトル</div>
            <div style="font-size:.85em;opacity:.75;">記事の説明文がここに入ります。</div>
            <div style="font-size:.75em;opacity:.6;">example.com</div>
          </div>
        </a>`;
      case "AMAZON":
        return `<a style="display:flex;align-items:stretch;border:1px solid #e0e0e0;border-radius:8px;overflow:hidden;text-decoration:none;color:${colors.textColor};background:${colors.backgroundColor};font-family:sans-serif;">
          <div style="flex:0 0 96px;background:#fff;"></div>
          <div style="flex:1 1 auto;min-width:0;padding:10px 14px;">
            <div style="font-weight:600;">サンプル商品名</div>
            <div style="font-weight:700;color:${colors.accentColor};">￥1,980</div>
            <div style="display:inline-block;font-size:.8em;color:#fff;background:${colors.accentColor};border-radius:4px;padding:4px 10px;margin-top:4px;">Amazonで見る</div>
          </div>
        </a>`;
      default:
        return "";
    }
  })();
  return `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body style="margin:12px;">${body}</body></html>`;
}

function TagDesignEditor({
  projectId,
  tagType,
  presets,
  initialSetting,
}: {
  projectId: number;
  tagType: EmbedTagType;
  presets: TagDesignPreset[];
  initialSetting: TagDesignSetting;
}) {
  const [state, formAction, pending] = useActionState(saveTagDesignSettingAction, initialState);
  const [presetId, setPresetId] = useState(initialSetting.presetId);
  const [colors, setColors] = useState<Colors>({
    backgroundColor: initialSetting.backgroundColor,
    textColor: initialSetting.textColor,
    accentColor: initialSetting.accentColor,
  });
  const [previewSrcDoc, setPreviewSrcDoc] = useState(() => buildPreviewHtml(tagType, colors));

  // 色変更のたびに即再描画すると入力のたびにiframeが再構築されカクつくため、300msデバウンスする
  useEffect(() => {
    const timer = setTimeout(() => {
      setPreviewSrcDoc(buildPreviewHtml(tagType, colors));
    }, 300);
    return () => clearTimeout(timer);
  }, [tagType, colors]);

  function applyPreset(preset: TagDesignPreset) {
    setPresetId(preset.id);
    setColors({
      backgroundColor: preset.backgroundColor,
      textColor: preset.textColor,
      accentColor: preset.accentColor,
    });
  }

  return (
    <section className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="font-medium">{TAG_LABELS[tagType]}</h2>

      <form action={formAction} className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <input type="hidden" name="projectId" value={projectId} />
        <input type="hidden" name="tagType" value={tagType} />
        <input type="hidden" name="presetId" value={presetId} />

        <div className="space-y-3">
          <div className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">プリセット</span>
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

          <div className="grid grid-cols-3 gap-3">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-600 dark:text-neutral-400">背景色</span>
              <input
                type="color"
                name="backgroundColor"
                value={colors.backgroundColor}
                onChange={(e) => setColors((prev) => ({ ...prev, backgroundColor: e.target.value }))}
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
                onChange={(e) => setColors((prev) => ({ ...prev, textColor: e.target.value }))}
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
                onChange={(e) => setColors((prev) => ({ ...prev, accentColor: e.target.value }))}
                className="h-9 w-full rounded border border-neutral-300 dark:border-neutral-700"
              />
              <span className="text-xs text-neutral-500">{colors.accentColor}</span>
            </label>
          </div>

          <button
            type="submit"
            disabled={pending}
            className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pending ? "保存中…" : "保存"}
          </button>
          {state.error && <p className="text-sm text-red-600">{state.error}</p>}
          {state.success && <p className="text-sm text-green-600">保存しました。</p>}
        </div>

        <div className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">プレビュー(サンプルデータ、入力後300ms自動更新)</span>
          <iframe
            title={`${TAG_LABELS[tagType]}プレビュー`}
            srcDoc={previewSrcDoc}
            sandbox="allow-same-origin"
            className="h-[160px] rounded border border-neutral-300 dark:border-neutral-700 bg-white"
          />
        </div>
      </form>
    </section>
  );
}

export function TagDesignSettingsPanel({
  projectId,
  presets,
  settings,
}: {
  projectId: number;
  presets: TagDesignPreset[];
  settings: TagDesignSetting[];
}) {
  return (
    <div className="space-y-6">
      {settings.map((setting) => (
        <TagDesignEditor
          key={setting.tagType}
          projectId={projectId}
          tagType={setting.tagType}
          presets={presets}
          initialSetting={setting}
        />
      ))}
    </div>
  );
}
