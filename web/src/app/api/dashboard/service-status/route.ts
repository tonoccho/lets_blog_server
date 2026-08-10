import { NextResponse } from "next/server";
import { getConnectedServiceStatuses } from "@/lib/apiClient";

/**
 * ダッシュボードのクライアントコンポーネントが定期ポーリングで叩くためのルート。
 * apiClient.tsのgetConnectedServiceStatuses()はセッションCookie(HttpOnly)を前提に
 * サーバー側でしか呼べないため、ブラウザからの直接呼び出しをここで中継する。
 */
export async function GET() {
  try {
    const statuses = await getConnectedServiceStatuses();
    return NextResponse.json(statuses);
  } catch (err) {
    const message = err instanceof Error ? err.message : "接続サービスの状態取得に失敗しました。";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
