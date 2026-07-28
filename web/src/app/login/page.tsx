"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { signIn } from "next-auth/react";
import Link from "next/link";

export default function LoginPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);

    const formData = new FormData(event.currentTarget);
    const result = await signIn("credentials", {
      email: formData.get("email"),
      password: formData.get("password"),
      redirect: false,
    });

    setPending(false);

    if (!result || result.error) {
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
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "ログイン中…" : "ログイン"}
        </button>
        <div className="flex justify-between text-sm">
          <Link href="/login/forgot-password" className="text-blue-600 hover:underline">
            パスワードをお忘れの方
          </Link>
          <Link href="/signup" className="text-blue-600 hover:underline">
            新規登録
          </Link>
        </div>
      </form>
    </div>
  );
}
