import Link from "next/link";

/**
 * NextAuthのpages.error(auth.ts)が指すエラーページ(issue #1392)。
 *
 * signIn()内部の/api/auth/csrf・/api/auth/providersへのfetchが一過性の通信断で失敗すると、
 * NextAuthは既定のエラーページ(/api/auth/error)へ進め、そこで利用者が行き止まりになっていた。
 * ここでは原因の詳細(?error=…)を出さず、/loginへ戻る導線だけを置く。
 *
 * パスを/login配下にしたのは、proxy.tsのPUBLIC_PATHS("/login")がstartsWithで判定するため、
 * 未ログインでも認証ゲートを通らず到達できるから。"/auth/error"はリバースプロキシ上で
 * Keycloak(/auth/realms/…)の名前空間と重なるため避けた。
 */
export default function LoginErrorPage() {
  return (
    <div className="mx-auto max-w-sm space-y-4 text-center text-sm text-neutral-600 dark:text-neutral-400">
      <h1 className="text-base font-semibold">ログインに失敗しました</h1>
      <p>一時的な通信の問題でログインを開始できませんでした。しばらくしてからもう一度お試しください。</p>
      <Link
        href="/login"
        data-testid="login-retry-link"
        className="inline-block rounded bg-neutral-900 px-4 py-2 text-sm text-white"
      >
        もう一度ログインする
      </Link>
    </div>
  );
}
