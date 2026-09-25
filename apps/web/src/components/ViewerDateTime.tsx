"use client";

import { useEffect, useState } from "react";
import { formatDateTime, TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";

/**
 * サーバーコンポーネントのJSX内で日時を直接整形すると、閲覧者のブラウザTZに従えない
 * (issue #1364、親issue #1261 分割C)。`/users`・`/projects`・`/posts`はいずれも
 * `"use client"`の無いサーバーコンポーネントで、マウント後の処理が無いためブラウザTZを
 * 当てるクライアント境界が無かった。
 *
 * `ConnectedServiceStatusPanel.tsx`(issue #1362)・`SshKeyPairsPanel.tsx`・
 * `PostsTable.tsx`(issue #1363)がそれぞれ複製していたゲート(個人設定TZがあれば
 * 即座にそれで整形、無ければマウント後に解決したブラウザTZで整形、マウント前は
 * サーバー/クライアントで同じ固定文字列を描いてハイドレーション不一致を避ける。
 * 前例: `ThemeSwitcher.tsx:23-58`のmountedフラグ方式)を、この部品へ共通化する。
 */
export function ViewerDateTime({
  iso,
  personalTimeZone,
}: {
  iso: string;
  /**
   * 個人設定(システム画面)で保存したタイムゾーン。未設定(null)ならマウント後に
   * 解決したブラウザのタイムゾーンで表示する。
   */
  personalTimeZone: string | null;
}) {
  // 個人設定TZが未設定のときだけ使う(mounted前後でサーバー/クライアントの出力を
  // 一致させるため)。個人設定TZがあるときはSSR/クライアントで常に同じ文字列になる
  // ためこのフラグを見ない。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  return (
    <>
      {personalTimeZone
        ? formatDateTime(iso, personalTimeZone)
        : mounted
          ? formatDateTime(iso)
          : TIMEZONE_PENDING_PLACEHOLDER}
    </>
  );
}
