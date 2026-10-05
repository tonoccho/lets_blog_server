# ADR-0010: 開発ワークフローを GitHub からセルフホスト GitLab CE へ移行する

- **状態**: 採用
- **日付**: 2026-09-03
- **関連**: #1021（Epic）、#1022〜#1030

## 背景

リポジトリと Issue を、GitHub からセルフホストの **GitLab Community Edition 19.3.1**
（`https://server.tonoccho.local/gitlab`）へ移行した。移行そのものは利用者が実施済みで、
本 ADR はその後に必要となった判断を記録する。

移行データの健全性は実測で確認した。

- GitHub Projects の 268 件すべてが GitLab に**同じ番号**で存在する。`#751` `#584` などの
  既存参照はそのまま生きている
- Status が Done の 219 件は closed、非 Done の 49 件は open。状態の食い違いはゼロ
- GitHub で PR だった番号は Issue の番号空間では欠番だが、**MR としては番号を保って
  移行されている**（`!1019` など）。コミットメッセージの `(#1019)` は存在しない Issue を
  指すため、別の Issue に誤リンクすることはない
- 移行済み Issue は `closed_at` が **null** になっている。移行前に閉じた Issue について、
  「いつ閉じたか」は追跡できない

## 決定

### 1. Community Edition のまま運用する

CE には無く Premium 以上を要する機能が2つあり、いずれもワークフローが依拠していた。

| 機能 | CE | 代替 |
| --- | --- | --- |
| **スコープ付きラベル**（排他性） | 無し | `status::` 命名 + フックとスクリプトの2層強制（#1023） |
| **`blocks` / `is_blocked_by`** | 無し（`relates_to` のみ） | ルールを撤回し、コードの確認を唯一の基準にした（#1024） |

EE パッケージへ入れ替えれば Premium を購入する道は残るが、現時点で必要としない。

### 2. ステータスはラベルで表現する

GitHub Projects の Status は単一選択フィールドで、2つ持つことは構造的に不可能だった。
GitLab CE にその保証は無い。`status::Inbox` … `status::Done` の通常ラベルとし、
**「ちょうど1つ」は仕組みで守る**（#1023）。

- `guard.py` が `labels=` による上書きと、`status::` の片側追加/片側削除を拒否する
- `scripts/check-issue-labels.sh` が、フックの見えない Web UI 編集を事後に検出する

`0個` は `2個` より危険である。ステータスの無い Issue はボードのどの列にも現れず、
`work-next` からも triage からも見えないまま忘れられる。

### 3. 依存の判定基準からルール1を撤回した

旧ルール「open な `blocked_by` リンクが唯一の状態ベースのブロッカー」は、**named した機構が
CE に存在しない**ため撤回した（#1024）。`relates_to` は方向を持たず、「A が B をブロックする」
を表現できない。

存在しない機構を指すルールを残す方が危険で、**確認していない形式的根拠を主張する判定を招く**。
代わりに置いたのは「無し」ではなく、旧ルール2が要求していたコードの確認を、例外なく
全ての依存に適用することである。

### 4. CI は稼働させない

Runner 0台。GitHub Actions を意図的に無効化していた従来の運用をそのまま引き継ぐ（#1027）。
`.github/` は削除した。動く見込みの無い定義とバッジを残さないためである。

代わりに品質を担保するのは、コミットとマージの経路上で機械的に強制される3つ
（`scripts/git-hooks/pre-commit`、`.claude/hooks/guard.py`、`scripts/check-changed-coverage.py`）。

**自動セキュリティスキャンと自動依存更新は失われた。** これは受け入れたトレードオフであり、
手動手順を [SECURITY_SCANNING.md](../SECURITY_SCANNING.md) と
[DEPENDENCY_UPDATE_POLICY.md](../DEPENDENCY_UPDATE_POLICY.md) に定めた。

### 5. マージは squash + fast-forward

`merge_method: ff` / `squash_option: always`。**`ff` が要点である**（#1030）。squash だけでは
足りず、`merge_method: merge` のままだと GitLab は squash コミットの**上にマージコミットを積む**。
GitHub の squash merge とは結果が違い、`develop` の線形履歴が崩れる。

### 6. 製品機能としての GitHub 連携は変更しない

記事プラン機能が利用者の GitHub リポジトリの Issue を読む部分
（`services/ai/.../github/`、`ProjectGithubTokenController`、`ProjectGithubRepositoryForm`、
`issueParser`、`infra/e2e-stubs/github/`）は**移行対象外**（利用者決定）。開発ワークフローとは
別物であり、GitLab 対応にはしない。

### 7. GitHub はコードとタグの一方向ミラーとして残す（2026-09-13 追記、#1256）

決定4（CI 不稼働）は「`.github/` を削除した」理由が「CI が動かないため」であって、GitHub の
存在自体を否定するものではなかった。移行元の GitHub リポジトリ `tonoccho/lets_blog_server`
は削除せず、**GitLab → GitHub の一方向 push ミラー**として維持することにした
（#1256）。当初は private だったが、第三者が認証なしで clone できるよう、利用者が
**遅くとも 2026-10-01 に public へ公開した**（#1251 の note 12613: 同日の資格情報なしの API で
`private: false`）。2026-10-05 に再確認した。

- **公開範囲はコードとコミット履歴のみ（#1251）。** GitHub 側の Issue・Wiki・Projects・
  Discussions・Pull Request は公開しない。これらは機能を無効化して実現している
  （`has_issues` / `has_wiki` / `has_projects` / `has_discussions` はすべて false、PR の
  API は 404）。無効化はデータを削除しないため、移行前の Issue は GitHub 上に保全されている
  （#1032）。2026-10-05 の再確認で、資格情報なしの API が `visibility: public`、資格情報なしの
  `git clone` が成功することを確かめた。
- **公開が先行し、秘密情報スキャンは公開後に実施した。** 2026-10-01 のスキャン（note 12619、
  gitleaks）で実資格情報2件（Keycloak クライアントシークレット letsblog-services /
  letsblog-web と E2E アカウントのパスワード）が露出していると判明し、同日ローテーションした
  （note 12711）。2026-10-05 に全 ref を再スキャンした（note 14803、訂正 note 14806、
  gitleaks v8.30.1）。検出 216 件はすべて資格情報ではなく、稼働中の値との一致は 0 件。
  露出した実資格情報はローテーション済みで、現在未解決の検出は 0 件である。
- **GitLab は引き続き source of truth であり、非公開のままである。** 公開したのは GitHub の
  ミラーだけで、GitLab の Issue・MR・CI は公開していない。
- **GitHub はコードとタグの副本にとどまる。** Issue・Merge Request・CI は GitLab に一本化
  したまま変えない。GitHub 側で Issue・PR・Actions を運用することはしない。
- ミラー対象は `main` / `develop` の2ブランチ（`only_protected_branches: true`）とタグ全件
  （GitLab の push ミラーはタグを絞り込めない。gitlab-org/gitlab#24873、#457680）。
- #1274 が GitLab の `main` へ検証済み develop をマージし semver タグを付ける運用に対し、
  本ミラーが GitHub 側へその `main` とタグを届ける経路になる。GitHub の `main` に付いた
  タグが、以後のリリースバージョンとして扱われる。
- 認証は GitHub の fine-grained personal access token（対象リポジトリ限定、
  Contents: Read and write、Workflows: Read and write）。同期方向は一方向のみで、GitHub 側の
  変更が GitLab へ戻ることはない。

設定手順・トークンの権限とその理由・運用手順は
[GITLAB_WORKFLOW_SETUP.md](../GITLAB_WORKFLOW_SETUP.md) の「7. GitHub へのミラー」を参照。

## 帰結

### 得たもの

- ワークフローが自前のインフラ上で完結する
- ガードの実効性が上がった。移行作業の過程で、**元から存在していた欠陥**を2件発見して修正した
  - `guard.py` の判定が `timeout` などの前置詞で全て素通りしていた（#1029）
  - 同じ原因で、引用符内の `>` や `rm` を誤検知し正当な調査を拒否していた（#986）

### 失ったもの

- 自動セキュリティスキャン（CodeQL 相当の静的解析）
- 自動依存更新（Dependabot）
- スコープ付きラベルによる排他性の**構造的**保証。いまは規約 + 2層の強制で守っている
- 移行前 Issue の `closed_at`。#751 のような時系列の追跡ができない。**書き戻しは不可能**
  であることを実測で確認した（下記）

### `closed_at` は書き戻せない（#1032、実測）

移行済み Issue の `closed_at` は null になっている。GitLab 上で閉じた Issue には値が入る
ため、移行前後で断絶がある。

```
#926  state=closed  closed_at=null              ← GitHub 時代に閉じた
#1022 state=closed  closed_at=2026-09-03T...    ← GitLab で閉じた
```

**GitLab の API は `closed_at` の設定を受け付けない。** 実測で確定した。

| 経路 | 結果 |
| --- | --- |
| REST `PUT /projects/:id/issues/:iid` | 受理パラメータの一覧を挙げてエラー。`created_at` は受理されるが `closed_at` は無い |
| GraphQL `updateIssue` | `UpdateIssueInput` が `closedAt` という引数を持たない |

したがって「GitLab へ復元する」という選択肢は**存在しない**。取れる手は2つだけ。

- **保全する** — GitHub から取り出して参照用のデータとしてリポジトリに残す。
  `scripts/export-github-closed-at.sh` がこれを行う
- **受け入れる** — 失われたものとして記録するに留める

**保全を推奨する。** 実際に必要になる場面（#751 のような事後分析）は稀だが、取得コストは
1回限りで小さく、失うと二度と戻らない。

**ただし期限がある。** 取得元は移行元の GitHub リポジトリであり、それが削除された時点で
この情報は永久に失われる。判断は先送りできるが、判断できる期間には期限がある。

取得には `gh` の一時的な再認証が要る。**作業後は必ず `gh auth logout` すること** — 認証が
通った `gh` は、スキルが移行元を誤って読み書きする事故の温床である（#1025）。移行元は
現存している（遅くとも 2026-10-01 以降は public で、コードとコミット履歴のみを公開している。
上記決定7）。

### 将来 CI を持つ場合に再発明しないこと

削除したワークフローには、実際に踏んだ失敗の記録が埋まっていた。要点のみ残す。

- **#810 / #739**: `apps/web` は tsconfig の paths で `packages/api-client` に型レベル依存して
  いる。変更検知が `packages/api-client` を含まないと web の型エラーがすり抜ける。
  **実際に17件すり抜けた**
- **#810**: web の型チェックは `index.ts` から到達できるファイルしか見ない。再エクスポート
  されない生成物はどのビルドでも型検査されない。`include` 指定の tsconfig が要る
- **#557**: `packages/lbs-common` と `services/*` は同じ Gradle マルチプロジェクトビルド。
  サービス単位のマトリクスで変更分だけ回す
- **#980**: Gradle のルートはリポジトリ直下。`api/` ディレクトリは存在しない

## 参照

- [GITLAB_WORKFLOW_SETUP.md](../GITLAB_WORKFLOW_SETUP.md) — 環境構築手順
- `.claude/CLAUDE.md` — ワークフローの規約（ステータスの表現と遷移、マージ方式、依存の判定）
