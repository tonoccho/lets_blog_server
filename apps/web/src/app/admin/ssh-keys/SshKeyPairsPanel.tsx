"use client";

import { useEffect, useState, useTransition } from "react";
import { GeneratedSshKeyPair, SavedSshKeyPair } from "@/lib/apiClient";
import { formatDateTime, TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import { createSshKeyPairAction, deleteSshKeyPairAction } from "./actions";

export function SshKeyPairsPanel({
  keyPairs,
  personalTimeZone,
}: {
  keyPairs: SavedSshKeyPair[];
  /**
   * 個人設定(システム画面)で保存したタイムゾーン(issue #1362、親issue #1261 分割A)。
   * 未設定(null)ならマウント後に解決したブラウザのタイムゾーンで表示する。
   */
  personalTimeZone: string | null;
}) {
  const [name, setName] = useState("");
  const [comment, setComment] = useState("");
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [generated, setGenerated] = useState<GeneratedSshKeyPair | null>(null);
  const [generatePending, startGenerateTransition] = useTransition();
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [deletePending, startDeleteTransition] = useTransition();
  /**
   * issue #1361: 一覧は`keyPairs`(サーバコンポーネントのprops)のみに依存していたため、
   * `deleteSshKeyPairAction`が呼ぶ`revalidatePath`によるサーバ再描画がマウント済みの
   * このクライアントコンポーネントへ届くタイミングに一覧の更新が左右されていた
   * (2026-09-19のリリース検証で1分以上更新されなかった実例あり)。
   * `revalidatePath`はサーバ側キャッシュの整合性(次回ナビゲーション・再読み込み時の
   * 再取得)のために`actions.ts`側にそのまま残しつつ、画面上の即時反映は削除の成功が
   * 確定した時点でローカル状態から行を除くことで保証する(サーバの再描画到着を待たない)。
   * 生成(create)時の一覧反映は本issueのOut of Scope(現行でも`generated`は別枠表示のみで
   * 一覧へは反映されない)であるため、propsが変わるたびにこのローカル状態を作り直す同期は
   * 持たない。ページ遷移・再読み込みでコンポーネントごと作り直されれば、その時点のpropsで
   * 初期化し直される。
   */
  const [items, setItems] = useState<SavedSshKeyPair[]>(keyPairs);
  // 個人設定TZが未設定のときだけ使う(mounted前後でサーバー/クライアントの出力を
  // 一致させるため、issue #1362)。個人設定TZがあるときはSSR/クライアントで常に同じ
  // 文字列になるためこのフラグを見ない。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  function handleGenerate(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setGenerateError(null);
    startGenerateTransition(async () => {
      const result = await createSshKeyPairAction(name, comment);
      if (result.error) {
        setGenerateError(result.error);
        return;
      }
      setGenerated(result.keyPair ?? null);
      setName("");
      setComment("");
    });
  }

  function handleDelete(id: number, keyName: string) {
    if (!window.confirm(`SSH鍵ペア「${keyName}」を削除します。よろしいですか?`)) {
      return;
    }
    setDeleteError(null);
    setDeletingId(id);
    startDeleteTransition(async () => {
      const result = await deleteSshKeyPairAction(id);
      if (result.error) {
        setDeleteError(result.error);
      } else {
        setItems((prev) => prev.filter((keyPair) => keyPair.id !== id));
      }
      setDeletingId(null);
    });
  }

  return (
    <div className="space-y-8">
      <section className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <h2 className="font-medium">新しいSSH鍵ペアを生成</h2>
        {/* issue #1051: JS無効時のネイティブGETフォールバックで入力値がURLへ漏れることを防ぐため、
            method="post"を明示する。送信自体はhandleGenerateがpreventDefaultして処理する。 */}
        <form onSubmit={handleGenerate} method="post" className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-600 dark:text-neutral-400">名前</span>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                maxLength={100}
                placeholder="production-deploy"
                className="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 text-sm"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-neutral-600 dark:text-neutral-400">コメント(任意)</span>
              <input
                type="text"
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                maxLength={255}
                placeholder="本番サーバーデプロイ用"
                className="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-3 py-2 text-sm"
              />
            </label>
          </div>
          {generateError && <p className="text-sm text-red-600">{generateError}</p>}
          {/* issue #1413: ハイドレーション完了(mounted)までは押せないようにする。
              未結線の状態で押されると `onSubmit` が走らず、`method="post"` の
              ネイティブ送信にフォールバックしてページが遷移し、鍵は生成されずに
              入力値だけが失われる(`/admin/ssh-keys` にPOSTハンドラは無い)。
              `method="post"` 自体は #1051 の緩和策(素のGET送信で入力値がURL・
              アクセスログ・Refererへ漏れるのを防ぐ)なので残す。多重防御である。
              リリース検証 run 11 はこの経路で停止した(Playwrightのログに
              `navigated to "https://localhost/admin/ssh-keys"` が残っている)。
              受け入れテスト側の再試行では直せない —— `retryClick.ts` の
              `clickUntilVisible` は「べき等な操作にのみ使うこと」と明記しており、
              鍵ペアの生成は非べき等だからである。Playwright の actionability
              チェックは `enabled` を待つため、ここを塞げばテストは無変更で安定する。 */}
          <button
            type="submit"
            disabled={generatePending || !mounted}
            className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-700 disabled:opacity-50"
          >
            {generatePending ? "生成中…" : "SSH鍵ペアを生成"}
          </button>
        </form>

        {generated && (
          <div className="space-y-2 rounded border border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-950/30 p-3">
            <p className="text-sm font-medium text-amber-800 dark:text-amber-300">
              「{generated.name}」を生成しました。秘密鍵はこの画面でのみ表示され、閉じると二度と確認できません。
              必要な場所へ今すぐコピーしてください。
            </p>
            <div className="space-y-1">
              <p className="text-xs text-neutral-600 dark:text-neutral-400">公開鍵(対象サーバーの~/.ssh/authorized_keysへ追記)</p>
              <textarea
                readOnly
                value={generated.publicKeyLine}
                rows={2}
                onFocus={(e) => e.currentTarget.select()}
                className="w-full rounded border border-neutral-300 dark:border-neutral-700 bg-neutral-50 dark:bg-neutral-800 px-3 py-2 font-mono text-xs"
              />
            </div>
            <div className="space-y-1">
              <p className="text-xs text-neutral-600 dark:text-neutral-400">秘密鍵</p>
              <textarea
                readOnly
                value={generated.privateKeyPem}
                rows={6}
                onFocus={(e) => e.currentTarget.select()}
                className="w-full rounded border border-neutral-300 dark:border-neutral-700 bg-neutral-50 dark:bg-neutral-800 px-3 py-2 font-mono text-xs"
              />
            </div>
            <button
              type="button"
              onClick={() => setGenerated(null)}
              className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-900 dark:text-neutral-50 hover:bg-neutral-200 dark:hover:bg-neutral-700"
            >
              閉じる
            </button>
          </div>
        )}
      </section>

      <section className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
        <h2 className="font-medium">保存済みのSSH鍵ペア</h2>
        {deleteError && <p className="text-sm text-red-600">{deleteError}</p>}
        {items.length === 0 ? (
          <p className="text-sm text-neutral-600 dark:text-neutral-400">保存済みのSSH鍵ペアはありません。</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-neutral-200 dark:border-neutral-800 text-neutral-600 dark:text-neutral-400">
                  <th className="py-2 pr-4 font-medium">名前</th>
                  <th className="py-2 pr-4 font-medium">公開鍵</th>
                  <th className="py-2 pr-4 font-medium">作成日時</th>
                  <th className="py-2 pr-4 font-medium" />
                </tr>
              </thead>
              <tbody>
                {items.map((keyPair) => (
                  <tr key={keyPair.id} className="border-b border-neutral-100 dark:border-neutral-800/60 align-top">
                    <td className="py-2 pr-4">
                      <p className="font-medium">{keyPair.name}</p>
                      {keyPair.comment && (
                        <p className="text-xs text-neutral-500 dark:text-neutral-400">{keyPair.comment}</p>
                      )}
                    </td>
                    <td className="py-2 pr-4">
                      <textarea
                        readOnly
                        value={keyPair.publicKeyLine}
                        rows={2}
                        onFocus={(e) => e.currentTarget.select()}
                        className="w-full min-w-[16rem] rounded border border-neutral-300 dark:border-neutral-700 bg-neutral-50 dark:bg-neutral-800 px-2 py-1 font-mono text-xs"
                      />
                    </td>
                    <td className="py-2 pr-4 whitespace-nowrap text-neutral-600 dark:text-neutral-400">
                      {personalTimeZone
                        ? formatDateTime(keyPair.createdAt, personalTimeZone)
                        : mounted
                          ? formatDateTime(keyPair.createdAt)
                          : TIMEZONE_PENDING_PLACEHOLDER}
                    </td>
                    <td className="py-2 pr-4">
                      <button
                        type="button"
                        onClick={() => handleDelete(keyPair.id, keyPair.name)}
                        disabled={deletePending && deletingId === keyPair.id}
                        className="rounded bg-red-50 dark:bg-red-950/30 px-3 py-1.5 text-sm text-red-700 dark:text-red-400 hover:bg-red-100 dark:hover:bg-red-950/60 disabled:opacity-50"
                      >
                        {deletePending && deletingId === keyPair.id ? "削除中…" : "削除"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
