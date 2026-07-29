"use client";

import { useActionState } from "react";
import type { UserProfile } from "@/lib/apiClient";
import { updateUserProfileAction, UpdateProfileState } from "./actions";

const initialState: UpdateProfileState = {};

export function UserProfileForm({ profile }: { profile: UserProfile }) {
  const action = (prevState: UpdateProfileState, formData: FormData) =>
    updateUserProfileAction(profile.id, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <form action={formAction} className="space-y-4 rounded-lg border border-neutral-200 bg-white p-5">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">姓</span>
          <input
            name="lastName"
            defaultValue={profile.lastName ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">名</span>
          <input
            name="firstName"
            defaultValue={profile.firstName ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">表示名</span>
          <input
            name="displayName"
            defaultValue={profile.displayName ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">ニックネーム</span>
          <input
            name="nickname"
            defaultValue={profile.nickname ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">ウェブサイト</span>
          <input
            name="websiteUrl"
            type="url"
            defaultValue={profile.websiteUrl ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">言語</span>
          <select
            name="locale"
            defaultValue={profile.locale ?? "ja_JP"}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          >
            <option value="ja_JP">日本語</option>
            <option value="en_US">English</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">部署</span>
          <input
            name="department"
            defaultValue={profile.department ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">役職</span>
          <input
            name="position"
            defaultValue={profile.position ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
      </div>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600">自己紹介</span>
        <textarea
          name="bio"
          rows={3}
          defaultValue={profile.bio ?? ""}
          className="rounded border border-neutral-300 px-3 py-2 text-sm"
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600">アバターURL(Gravatar等)</span>
        <input
          name="avatarUrl"
          type="url"
          defaultValue={profile.avatarUrl ?? ""}
          className="rounded border border-neutral-300 px-3 py-2 text-sm"
        />
        {profile.avatarUrl && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={profile.avatarUrl} alt="" className="mt-2 h-16 w-16 rounded-full border border-neutral-200" />
        )}
      </label>

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
