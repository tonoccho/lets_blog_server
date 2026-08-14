import { NextResponse } from "next/server";
import { getContainerStatuses } from "@/lib/apiClient";

/**
 * ダッシュボードのクライアントコンポーネントが定期ポーリングで叩くためのルート(issue #280)。
 * getConnectedServiceStatuses()と同じ理由でサーバー側からの中継が必要。
 */
export async function GET() {
  try {
    const statuses = await getContainerStatuses();
    return NextResponse.json(statuses);
  } catch (err) {
    const message = err instanceof Error ? err.message : "コンテナの状態取得に失敗しました。";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
