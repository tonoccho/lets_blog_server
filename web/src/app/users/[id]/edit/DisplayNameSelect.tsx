"use client";

import { useMemo } from "react";

export function DisplayNameSelect({
  firstName,
  lastName,
  nickname,
  email,
  defaultValue,
}: {
  firstName: string;
  lastName: string;
  nickname: string;
  email: string;
  defaultValue: string | null;
}) {
  const candidates = useMemo(() => {
    const list: string[] = [];

    if (nickname) list.push(nickname);
    if (firstName) list.push(firstName);
    if (lastName) list.push(lastName);
    if (firstName && lastName) {
      list.push(`${firstName} ${lastName}`);
      list.push(`${lastName} ${firstName}`);
    }
    if (email) {
      list.push(email.split("@")[0]);
    }

    const unique = Array.from(new Set(list));

    if (defaultValue && !unique.includes(defaultValue)) {
      unique.unshift(defaultValue);
    }

    return unique;
  }, [firstName, lastName, nickname, email, defaultValue]);

  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="text-neutral-600 dark:text-neutral-400">表示名</span>
      <select
        name="displayName"
        defaultValue={defaultValue ?? ""}
        className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
      >
        <option value="">選択してください</option>
        {candidates.map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
      </select>
    </label>
  );
}
