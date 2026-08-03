# 障害調査ログ: Ollamaモデル(qwen3:14b)インストールが「一瞬表示されて消える」

## 症状

管理画面のAIモデル管理タブから Ollama モデル `qwen3:14b` のインストールを実行したところ、
「インストール中…」の表示が一瞬出た後に消え、完了したのか失敗したのか分からなくなった。

## 調査方法

- コード読解: フロントエンド(`web/src/app/projects/[id]/OllamaModelTable.tsx` ほか)とバックエンド
  (`OllamaModelService` → `ModelInstallJobRunner` → `OllamaClient`)のジョブ起動〜ポーリングの流れを確認
- `docker logs lbs-api` / `docker logs lbs-ollama` で実際の挙動を確認
- `generation_jobs` テーブルを直接参照し、実際に発行されたジョブの状態を確認

## 実データで確認できた事実

`generation_jobs` テーブルに `qwen3:14b` の `ollama_pull` ジョブが2件記録されていた。

| id | status | created_at (UTC) | updated_at (UTC) | 内容 |
|----|--------|-------------------|-------------------|------|
| 1  | failed | 2026-08-03 09:25:51 | 2026-08-03 09:26:07 | `{"error": "Ollamaモデルのインストール中にエラーが発生しました: closed"}` |
| 2  | done   | 2026-08-03 19:59:30 | 2026-08-03 20:02:59 | `{"success": "true"}`(3分28秒で正常完了。`docker logs lbs-ollama` の `GIN ... POST /api/pull` でも200/3m28sを確認) |

→ **id=2 は今回ユーザーが実行した試行そのものと考えられ、バックエンド/Ollama側は正常に完了していた**
(ダウンロード自体は成功しモデルは実際にインストール済み)。
つまり「消えた」ように見えたのは処理失敗ではなく、UI側が進捗を見失っただけという可能性が高い。

## 根本原因(フロントエンドのUI仕様上の欠陥)

`web/src/app/projects/[id]/OllamaModelTable.tsx` の `handleInstallSubmit`(78-95行目)で、
ジョブ起動リクエストが成功した直後に `setShowNewForm(false)`(92行目)を呼び、
新規インストール用フォームそのものを閉じている。

```
const result = await installOllamaModelAction(projectId, modelName);
...
setShowNewForm(false);        // ← ここでフォームごと閉じる
setNewModelName("");
startPolling(result.jobId as number);
```

一方で「インストール中…」ボタンラベルと進捗表示(`{progress ? formatJobProgress(progress) : "開始しています…"}`)は
136-142行目の通り `showNewForm` が true の間しか描画されない、フォーム内部の要素になっている。

```
{pendingAction?.type === "install" && (
  <p className="w-full text-neutral-500">
    {progress ? formatJobProgress(progress) : "開始しています…"}
  </p>
)}
```

そのため実際の挙動は次のようになる:

1. 送信直後は `pendingAction.type === "install"` かつ `showNewForm === true` なので「インストール中…」が一瞬表示される
2. `installOllamaModelAction` のレスポンスが返ると即座に `setShowNewForm(false)` が実行され、フォーム全体(ボタン・進捗表示とも)がDOMから消える
3. ポーリング自体(`useGenerationJobPolling` → `startPolling`)はバックグラウンドで継続しているが、それを表示する場所がなくなっている
4. ジョブが `done`/`failed` になった時点で `handleJobSettled` が呼ばれ、フォーム外の `message` 領域に完了/失敗メッセージが出る設計だが、
   qwen3:14b (約9.3GB)は数分かかるため、ユーザーが画面を離れる・再読み込みする・他タブに切り替える等をすると
   `activeJobId` を保持する React state が失われ、完了メッセージも表示されないまま終わる

つまり「一瞬インストール中と表示されて消えた」は、フォームが自動で閉じる実装によって進捗表示ごと隠れたことによる見た目上の問題であり、
バックエンドのジョブ自体(今回のケース)は正常に完了していた。

## 別件: 同日朝に発生していた実失敗(id=1)

同じ `qwen3:14b` に対して同日 09:25:51 にも試行があり、こちらは16秒で `closed` という
`IOException` により失敗している(`OllamaClient.java` 136-141行目、`/api/pull` のストリーミングレスポンス読み取り中に
接続が切断された場合の例外)。極端に短時間での失敗であり、Ollamaコンテナ側からモデルレジストリへの
アウトバウンド疎通(インターネットegress)が確立していないタイミングでの試行だった可能性がある
(`git status` 上で `docker-compose.yml` / `scripts/connect-internet-egress.sh` が変更中であり、
egress再接続が必要な状況と符合する)。

また `ModelInstallJobRunner.runJob`(87-116行目)は例外を `catch (RuntimeException e)` した際に
DBの `result_payload` へエラーメッセージを保存するのみで、アプリケーションログへは一切出力していない。
そのため今回 `docker logs lbs-api` をいくら検索してもエラーの手掛かりが得られず、
`generation_jobs` テーブルを直接見るまで原因特定に時間がかかった。

## 副次的に判明した点

- `/ai-jobs` ページ(`web/src/app/ai-jobs/page.tsx`)はジョブ履歴の一覧(種別・ステータス・作成/更新日時)を見られるが、
  サーバーコンポーネントで自動更新されず、進捗%も表示されない。実行中ジョブの有無を確認する簡易手段にはなるが、
  リアルタイム監視用途には作られていない。
- ジョブの進捗はプロジェクト詳細画面の「新規インストール」フォームを開いたままの状態でしか追えず、
  同一画面上でも他の操作(タブ切り替え等)をすると `OllamaModelTable` がアンマウントされポーリングが失われる。

## 対応方針(未実施・要相談)

以下は原因分析の結果としての改善候補。実装は行っていない。

1. `setShowNewForm(false)` を呼ぶタイミングを、ジョブ起動直後ではなくジョブ完了(`handleJobSettled`)後に変更し、
   進捗表示がインストール完了までフォーム内に残るようにする。
   または進捗表示をフォームの外(コンポーネント直下)に出し、`showNewForm` の状態に依存しないようにする。
2. `ModelInstallJobRunner.runJob` の失敗時に `logger.warn/error` でスタックトレース付きログを残す。
3. `qwen3:14b` は現在DBステータス上は `done` (実際にダウンロード済み)。プロジェクト画面をリロードして
   モデル一覧に反映されているか確認する。
