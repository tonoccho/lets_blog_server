"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { signIn } from "next-auth/react";
import Link from "next/link";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [needsTotp, setNeedsTotp] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);

    const formData = new FormData(event.currentTarget);
    const emailValue = needsTotp ? email : String(formData.get("email") ?? "");
    const passwordValue = needsTotp ? password : String(formData.get("password") ?? "");
    // signIn()にundefinedを渡すと内部で文字列"undefined"にシリアライズされてしまうため、
    // 未入力時は明示的に空文字列を渡す(サーバー側は空文字列をfalsyとして「未入力」判定する)
    const totpCode = needsTotp ? String(formData.get("totpCode") ?? "") : "";

    const result = await signIn("credentials", {
      email: emailValue,
      password: passwordValue,
      totpCode,
      redirect: false,
    });

    setPending(false);

    if (!result || result.error) {
      if (result?.error === "2FA_REQUIRED") {
        setEmail(emailValue);
        setPassword(passwordValue);
        setNeedsTotp(true);
        return;
      }
      if (result?.error === "2FA_INVALID") {
        setError("認証コードが正しくありません。もう一度入力してください。");
        return;
      }
      setError("メールアドレスまたはパスワードが正しくありません。");
      return;
    }

    router.push("/");
    router.refresh();
  }

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-6 text-xl font-semibold">ログイン</h1>
      <form onSubmit={handleSubmit} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5">
        {!needsTotp && (
          <>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-600">メールアドレス</span>
              <input
                name="email"
                type="email"
                required
                className="rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-600">パスワード</span>
              <input
                name="password"
                type="password"
                required
                className="rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </label>
          </>
        )}
        {needsTotp && (
          <>
            <p className="text-sm text-neutral-600">
              認証アプリに表示されている6桁のコード(またはバックアップコード)を入力してください。
            </p>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-600">認証コード</span>
              <input
                name="totpCode"
                type="text"
                inputMode="numeric"
                autoFocus
                required
                className="rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </label>
          </>
        )}
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "確認中…" : needsTotp ? "認証コードを確認" : "ログイン"}
        </button>
        {!needsTotp && (
          <div className="flex justify-between text-sm">
            <Link href="/login/forgot-password" className="text-blue-600 hover:underline">
              パスワードをお忘れの方
            </Link>
            <Link href="/signup" className="text-blue-600 hover:underline">
              新規登録
            </Link>
          </div>
        )}
        {needsTotp && (
          <button
            type="button"
            onClick={() => {
              setNeedsTotp(false);
              setError(null);
            }}
            className="text-sm text-blue-600 hover:underline"
          >
            メールアドレス・パスワード入力に戻る
          </button>
        )}
      </form>
    </div>
  );
}
