"use client";

import { useState } from "react";
import { useActionState } from "react";
import type { UserProfile } from "@/lib/apiClient";
import { updateUserProfileAction, UpdateProfileState } from "./actions";
import { DisplayNameSelect } from "./DisplayNameSelect";
import { CustomLinksEditor } from "./CustomLinksEditor";
import { AvatarUploadField } from "./AvatarUploadField";

const initialState: UpdateProfileState = {};

const SOCIAL_LINK_FIELDS: { key: keyof NonNullable<UserProfile["socialLinks"]>; label: string }[] = [
  { key: "facebook", label: "Facebook" },
  { key: "youtube", label: "YouTube" },
  { key: "whatsapp", label: "WhatsApp" },
  { key: "tiktok", label: "TikTok" },
  { key: "instagram", label: "Instagram" },
  { key: "wechat", label: "WeChat" },
  { key: "x", label: "X (Twitter)" },
  { key: "threads", label: "Threads" },
  { key: "github", label: "GitHub" },
  { key: "pinterest", label: "Pinterest" },
  { key: "meetup", label: "Meetup" },
  { key: "line", label: "LINE" },
  { key: "linkedin", label: "LinkedIn" },
  { key: "hatena", label: "はてな" },
];

function SocialLinkField({
  label,
  name,
  value,
}: {
  label: string;
  name: string;
  value: string | null | undefined;
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-neutral-600 dark:text-neutral-400">{label}</span>
      <input
        name={`socialLinks.${name}`}
        type="url"
        defaultValue={value ?? ""}
        className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
      />
    </label>
  );
}

export function UserProfileForm({
  profile,
  canEditEmail = false,
}: {
  profile: UserProfile;
  /** メールアドレスを編集できるか(管理者のみ。#1192) */
  canEditEmail?: boolean;
}) {
  const action = (prevState: UpdateProfileState, formData: FormData) =>
    updateUserProfileAction(profile.id, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  const [lastName, setLastName] = useState(profile.lastName ?? "");
  const [firstName, setFirstName] = useState(profile.firstName ?? "");
  const [nickname, setNickname] = useState(profile.nickname ?? "");

  return (
    <form action={formAction} className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">メールアドレス</span>
        {canEditEmail ? (
          <input
            name="email"
            type="email"
            defaultValue={profile.email ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        ) : (
          <>
            <input
              type="email"
              value={profile.email ?? ""}
              disabled
              readOnly
              className="rounded border border-neutral-300 dark:border-neutral-700 bg-neutral-50 dark:bg-neutral-800 px-3 py-2 text-sm text-neutral-500 dark:text-neutral-400"
            />
            <span className="text-xs text-neutral-400">(メールアドレスの変更は管理者のみ可能です)</span>
          </>
        )}
      </label>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">姓</span>
          <input
            name="lastName"
            value={lastName}
            onChange={(e) => setLastName(e.target.value)}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">名</span>
          <input
            name="firstName"
            value={firstName}
            onChange={(e) => setFirstName(e.target.value)}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <DisplayNameSelect
          firstName={firstName}
          lastName={lastName}
          nickname={nickname}
          email={profile.email}
          defaultValue={profile.displayName}
        />
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">ニックネーム</span>
          <input
            name="nickname"
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">ウェブサイト</span>
          <input
            name="websiteUrl"
            type="url"
            defaultValue={profile.websiteUrl ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">言語</span>
          <select
            name="locale"
            defaultValue={profile.locale ?? "ja_JP"}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          >
            <option value="ja_JP">日本語</option>
            <option value="en_US">English</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">部署</span>
          <input
            name="department"
            defaultValue={profile.department ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">役職</span>
          <input
            name="position"
            defaultValue={profile.position ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
      </div>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">自己紹介</span>
        <textarea
          name="bio"
          rows={3}
          defaultValue={profile.bio ?? ""}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
        />
      </label>
      <div className="flex flex-col gap-3">
        <span className="text-sm text-neutral-600 dark:text-neutral-400">アバター</span>

        {/* issue #1241: 手元の画像ファイルをアップロード・正方形に切り抜いてアバターにする。
            既存の「アバターURL」テキスト入力(下)とは独立して動作し、どちらの方法でも
            設定できる(併存。テキスト入力欄の廃止・移行はスコープ外)。 */}
        <AvatarUploadField userId={profile.id} initialAvatarUrl={profile.avatarUrl} />

        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">アバターURL(Gravatar等)</span>
          <input
            name="avatarUrl"
            type="url"
            defaultValue={profile.avatarUrl ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
      </div>

      <fieldset className="flex flex-col gap-3">
        <legend className="text-sm font-medium text-neutral-600 dark:text-neutral-400">SNSリンク</legend>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {SOCIAL_LINK_FIELDS.map(({ key, label }) => (
            <SocialLinkField key={key} label={label} name={key} value={profile.socialLinks?.[key]} />
          ))}
        </div>
      </fieldset>

      <CustomLinksEditor defaultLinks={profile.customLinks} />

      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">保存しました。</p>}

      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存"}
      </button>
    </form>
  );
}
