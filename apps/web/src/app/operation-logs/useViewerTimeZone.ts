"use client";

import { useSyncExternalStore } from "react";

function subscribe(): () => void {
  // ブラウザのTZはページの生存中に変わらないものとして扱う。購読は不要。
  return () => {};
}

function getBrowserTimeZone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  } catch {
    return null;
  }
}

/**
 * 表示に使うタイムゾーンを返す(issue #1260)。個人設定TZがあればそれを優先し、
 * 無ければ閲覧者のブラウザTZ。
 *
 * ブラウザTZはサーバー描画時には分からない。`useSyncExternalStore` のサーバースナップ
 * ショットを `null` にしておくと、サーバー描画とハイドレーション中は `null`(呼び出し側は
 * 仮表示を出す)、ハイドレーション完了後にブラウザTZへ再描画されるため、不一致を起こさない。
 * `null` が返るのは、個人設定TZが無く、まだブラウザTZを解決できない間だけ。
 */
export function useViewerTimeZone(personalTimeZone: string | null): string | null {
  const browserTimeZone = useSyncExternalStore(subscribe, getBrowserTimeZone, () => null);
  return personalTimeZone ?? browserTimeZone;
}
