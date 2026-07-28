import { PasswordResetForm } from "./PasswordResetForm";

export default async function PasswordResetPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  const { token } = await searchParams;

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-6 text-xl font-semibold">パスワード再設定</h1>
      <PasswordResetForm token={token ?? ""} />
    </div>
  );
}
