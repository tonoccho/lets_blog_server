/**
 * サイトの公開 URL(baseUrl)と管理画面パスから、管理画面の絶対 URL を組み立てる。
 * 解決できない場合(baseUrl が URL でない、パスが別オリジンを指す)は null。
 */
export function resolveSiteAdminUrl(baseUrl: string, adminPath: string): string | null {
  try {
    const base = new URL(baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`)
    const resolved = new URL(adminPath.replace(/^\//, ''), base)
    return resolved.origin === base.origin ? resolved.toString() : null
  } catch {
    return null
  }
}
