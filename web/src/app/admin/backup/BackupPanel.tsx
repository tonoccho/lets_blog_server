"use client";

import { useActionState, useRef } from "react";
import { restoreBackupAction, RestoreBackupFormState } from "./actions";

const initialState: RestoreBackupFormState = {};

export function BackupPanel() {
  const [state, formAction, pending] = useActionState(restoreBackupAction, initialState);
  const formRef = useRef<HTMLFormElement>(null);

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    if (
      !window.confirm(
        "リストアを実行すると、現在のデータベースの内容はアップロードしたバックアップの内容で上書きされます。よろしいですか?"
      )
    ) {
      e.preventDefault();
    }
  }

  return (
    <div className="space-y-8">
      <section className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <h2 className="font-medium">バックアップのダウンロード</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          現在のデータベース全体と生成画像ファイルをZIPアーカイブとしてダウンロードします
          (managed WordPressサイト個別のDB/ファイルは対象外です)。
        </p>
        <a
          href="/admin/backup/download"
          className="inline-block rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-700"
        >
          ダウンロード
        </a>
      </section>

      <section className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <h2 className="font-medium">バックアップからのリストア</h2>
        <p className="text-sm text-red-600">
          警告: リストアを実行すると、現在のデータベース・生成画像ファイルの内容は上書きされ元に戻せません。
        </p>
        <form ref={formRef} action={formAction} onSubmit={handleSubmit} className="space-y-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-neutral-600 dark:text-neutral-400">バックアップファイル(.zip)</span>
            <input
              type="file"
              name="file"
              accept=".zip"
              required
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            />
          </label>
          <label className="flex items-start gap-2 text-sm text-neutral-600 dark:text-neutral-400">
            <input type="checkbox" name="acknowledgeKeyMismatch" className="mt-0.5" />
            <span>
              このバックアップは現在の環境と異なるAPP_ENCRYPTION_KEYで作成された可能性があることを理解した上で続行する
              (通常はチェック不要。チェックしない状態でキーが不一致の場合はエラーで中断されます。異なる環境からの
              バックアップを意図的にリストアする場合のみチェックしてください。サイトの認証情報等が復号できなくなります)
            </span>
          </label>
          {state.error && <p className="text-sm text-red-600">{state.error}</p>}
          {state.success && <p className="text-sm text-green-600">リストアが完了しました。</p>}
          <button
            type="submit"
            disabled={pending}
            className="rounded bg-red-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pending ? "リストア中…" : "リストアを実行"}
          </button>
        </form>
      </section>
    </div>
  );
}
