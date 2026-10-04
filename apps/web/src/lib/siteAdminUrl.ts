/**
 * サイトの公開 URL(baseUrl)と管理画面パスから、管理画面の絶対 URL を組み立てる。
 * パスはサイト個別の値(siteAdminPath)が空でなければそれを、そうでなければグローバル既定(adminPath)を使う。
 * 解決できない場合(baseUrl が URL でない、パスが別オリジンを指す)は null。
 */
export function resolveSiteAdminUrl(
  baseUrl: string,
  adminPath: string,
  siteAdminPath?: string | null
): string | null {
  const path = siteAdminPath || adminPath
  try {
    const base = new URL(baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`)
    const resolved = new URL(path.replace(/^\//, ''), base)
    return resolved.origin === base.origin ? resolved.toString() : null
  } catch {
    return null
  }
}
