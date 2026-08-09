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
  TOC: "目次",
  BLOGCARD: "ブログカード",
  AMAZON: "Amazon商品カード",
};

const TAG_SYNTAX: Record<EmbedTagType, string> = {
  TOC: "[toc]",
  BLOGCARD: "[blogcard URL]",
  AMAZON: "[amazon URL]",
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
    <form
      action={formAction}
      className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <h2 className="font-medium">
        {TAG_LABELS[tagType]}のデザインを編集: <code className="font-mono text-sm">{TAG_SYNTAX[tagType]}</code>
      </h2>
      <input type="hidden" name="projectId" value={projectId} />
      <input type="hidden" name="tagType" value={tagType} />
      <input type="hidden" name="presetId" value={presetId} />

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

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
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
  const [editingType, setEditingType] = useState<EmbedTagType>(settings[0]?.tagType ?? "TOC");
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
        />
      )}
    </div>
  );
}
