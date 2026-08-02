"use client";

import { useActionState } from "react";
import { updatePreferencesAction, UpdatePreferencesState } from "./actions";

const initialState: UpdatePreferencesState = {};

export function SystemPreferencesForm({
  locale,
  timezone,
  timezoneOptions,
}: {
  locale: string;
  timezone: string;
  timezoneOptions: string[];
}) {
  const [state, formAction, pending] = useActionState(updatePreferencesAction, initialState);

  return (
    <form action={formAction} className="rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="mb-3 font-medium">個人設定</h2>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">言語</span>
          <select
            name="locale"
            defaultValue={locale}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          >
            <option value="ja_JP">日本語</option>
            <option value="en_US">English</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">タイムゾーン</span>
          <select
            name="timezone"
            defaultValue={timezone}
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          >
            {timezoneOptions.map((tz) => (
              <option key={tz} value={tz}>
                {tz}
              </option>
            ))}
          </select>
        </label>
      </div>

      {state.error && <p className="mt-3 text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="mt-3 text-sm text-green-600">保存しました。</p>}

      <button
        type="submit"
        disabled={pending}
        className="mt-4 rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存"}
      </button>
    </form>
  );
}
