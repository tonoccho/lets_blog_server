"use client";

import { useViewerTimeZone } from "./useViewerTimeZone";

/**
 * 絞り込みフォームへブラウザTZを載せる(issue #1437)。
 *
 * 日時範囲は GET フォームでサーバーへ渡り、サーバーは個人設定TZが無いとブラウザTZを知らない。
 * 表示と同じTZ(個人設定TZ、未設定ならブラウザTZ)で解釈させるため、個人設定TZが未設定のときだけ
 * ブラウザTZを hidden 入力 `tz` として一緒に送る。サーバー描画中は解決できないので空値になり、
 * ハイドレーション後に埋まる(`useViewerTimeZone` と同じ方式)。
 */
export function BrowserTimeZoneField({ personalTimeZone }: { personalTimeZone: string | null }) {
  const timeZone = useViewerTimeZone(personalTimeZone);
  if (personalTimeZone !== null) return null;
  return <input type="hidden" name="tz" value={timeZone ?? ""} readOnly />;
}
