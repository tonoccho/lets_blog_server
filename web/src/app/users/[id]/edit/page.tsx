import { redirect } from "next/navigation";
import { getUserProfile } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";
import { UserProfileForm } from "./UserProfileForm";

export default async function UserProfileEditPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const session = await requireSession();

  const isSelf = session.user.id === id;
  if (!isSelf && session.user.role !== "admin") {
    redirect("/");
  }

  const actor = { id: Number(session.user.id), role: session.user.role };
  const profile = await getUserProfile(Number(id), actor).catch(() => null);
  if (!profile) {
    redirect(isSelf ? "/" : "/users");
  }

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">ユーザープロフィール編集</h1>
      <p className="text-sm text-neutral-500">{profile.email}</p>
      <UserProfileForm profile={profile} />
    </div>
  );
}
