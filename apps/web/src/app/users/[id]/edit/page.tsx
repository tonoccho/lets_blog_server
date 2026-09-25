import { redirect } from "next/navigation";
import { getUserProfile } from "@/lib/apiClient";
import { requireSession, getViewerProfile } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { Tabs, type TabItem } from "@/components/Tabs";
import { UserProfileForm } from "./UserProfileForm";
import { PersonalPreferencesForm } from "./PersonalPreferencesForm";

// Node/ブラウザがIntl.supportedValuesOfに対応していない場合のフォールバック。
const FALLBACK_TIMEZONES = [
  "Asia/Tokyo",
  "Asia/Seoul",
  "Asia/Shanghai",
  "Asia/Singapore",
  "Asia/Kolkata",
  "Europe/London",
  "Europe/Paris",
  "Europe/Berlin",
  "America/New_York",
  "America/Chicago",
  "America/Los_Angeles",
  "UTC",
];

function getTimezoneOptions(): string[] {
  if (typeof Intl.supportedValuesOf === "function") {
    try {
      return Intl.supportedValuesOf("timeZone");
    } catch {
      return FALLBACK_TIMEZONES;
    }
  }
  return FALLBACK_TIMEZONES;
}

export default async function UserProfileEditPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const session = await requireSession();

  // session.user.idはKeycloakのsub(UUID)であり、ローカルの数値ユーザーIDではない(issue #784)。
  // 以前は `session.user.id === id` が常にfalseになり、非adminが自分の編集画面を開けなかった。
  const viewer = await getViewerProfile();
  const isSelf = viewer != null && String(viewer.id) === id;
  if (!isSelf && session.user.role !== "admin") {
    redirect("/");
  }

  const profile = await getUserProfile(Number(id)).catch(() => null);
  if (!profile) {
    redirect(isSelf ? "/" : "/users");
  }

  // 個人設定(言語・タイムゾーン)は本人のみが対象(セッションに紐付く操作のため、
  // adminが他ユーザーの画面を開いても代理設定はできない)。
  // 2FA(TOTP)設定画面はissue #564でKeycloakへの移行に伴い削除した(認証自体をKeycloakへ
  // 委譲したため、TOTPの要否・設定はKeycloak側で管理する)。

  const tabs: TabItem[] = [
    {
      id: "profile",
      label: "プロフィール",
      content: <UserProfileForm profile={profile} />,
    },
  ];

  if (isSelf) {
    tabs.push({
      id: "preferences",
      label: "個人設定",
      content: (
        <PersonalPreferencesForm
          locale={profile.locale ?? "ja_JP"}
          timezone={profile.timezone}
          timezoneOptions={getTimezoneOptions()}
        />
      ),
    });
  }

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "ユーザー", href: session.user.role === "admin" ? "/users" : undefined },
          { label: profile.displayName || profile.email },
        ]}
      />
      <h1 className="text-xl font-semibold">ユーザープロフィール編集</h1>
      <p className="text-sm text-neutral-500 dark:text-neutral-400">{profile.email}</p>
      <Tabs tabs={tabs} />
    </div>
  );
}
