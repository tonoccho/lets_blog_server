// タイムゾーン未指定時のフォールバック。個人設定が未保存のユーザーはこの値で表示される。
// タイムゾーンを常に明示することで、サーバー(SSR)とブラウザ(ハイドレーション)の実行環境の
// タイムゾーンが異なっていても表示が一致するようにする(未指定だとハイドレーション不整合になる)。
const DEFAULT_TIME_ZONE = "Asia/Tokyo";

export function formatDateTime(iso: string, timeZone?: string | null): string {
  return new Date(iso).toLocaleString("ja-JP", { timeZone: timeZone ?? DEFAULT_TIME_ZONE });
}
