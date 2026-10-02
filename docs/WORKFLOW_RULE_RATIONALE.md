# ワークフロー規則の経緯

`.claude/CLAUDE.md` は**規則そのもの**を置く場所で、毎ターン読み込まれる。この文書は、その
規則が**なぜそうなったか**——どの失敗が規則を生んだか、どの規則が撤回されたか——を引き取る。

規則を変えようとするときは、まずここを読むこと。ここに書かれている失敗の多くは、
「明らかに不要に見えるガード」を外したことで再発したものである。

---

## モデル選択: 宣言と実測の乖離 (2026-09-02)

`CLAUDE.md` → **Model Selection** は、各スキル・エージェントが frontmatter で宣言する
モデルを定めている。**宣言は要求であって保証ではない。**

`~/.local/state/claude-auto/*.log` にセッションIDが残っている3回の無人 `work-next` サイクル
(assistant 応答 411 件)を集計した結果。当時ループは `claude -p "/work-next" --model opus`
を呼んでいた。

| Stage | 宣言 | 実測 | 一致 |
| --- | --- | --- | --- |
| (スキル未帰属 — トップレベルのターン) | — | `opus` ×125、出力 90K tok | — |
| `work-next` | `sonnet` | `sonnet` ×92 | はい |
| `git-workflow` | `haiku` | `sonnet` ×57 | いいえ |
| `implement-issue` | `opus` | `sonnet` ×56、`opus` ×0 | いいえ |
| `qa-issue` | `sonnet` | `opus` ×24、`sonnet` ×7 | いいえ |
| `review-issue` | `sonnet` | `opus` ×17、`sonnet` ×11 | いいえ |
| `merge-request`（改名前に計測。#1033） | `sonnet` | `sonnet` ×18、`opus` ×4 | 部分的 |
| `complete-issue` | `sonnet` | `sonnet` ×14、`opus` ×12 | 部分的 |

3つの発見。これが「割り当ては事実である」と書かなくなった理由である。

1. **CLI の `--model` が最大の塊を支配していた** — スキルに帰属しないトップレベルのターンが
   応答125件・出力90Kトークンで、単独の最大消費者だった。
2. **`implement-issue` は一度も `opus` に到達しなかった** — 宣言していたにもかかわらず。
3. **`review-issue` / `qa-issue` はほとんど `opus` で動いていた** — `sonnet` を宣言していた
   のに、意図と逆だった。

これらのサイクルでは全応答が `effort=medium` だった。同じ transcript ディレクトリにある
対話セッションは `effort=high` を示すので、これはループの `claude` 呼び出し方の性質であって、
リポジトリ全体の設定ではない。

標本は小さい。セッションIDの記録は 2026-09-02 に始まったばかりで、24サイクル中3つしか
帰属できなかった。宣言と挙動の差を埋めるのは #1011。

### 実際に何が動いたかを調べる

```bash
cd ~/.claude/projects/-home-seiji-src-lets-blog-server
cat *.jsonl | jq -R 'fromjson? // empty' \
  | jq -sr '[.[] | select(.type=="assistant")]
      | group_by((.attributionSkill // "(none)") + "|" + .message.model)
      | map({k: .[0], n: length}) | sort_by(-.n) | .[]
      | "\(.n)  \(.k.attributionSkill // "(none)")  \(.k.message.model)  effort=\(.k.effort)"'
```

`attributionSkill`、`message.model`、`effort` は応答ごとに記録されている。**サブエージェント
自身の応答はこの transcript に含まれない**ため、`Agent` 呼び出しの背後にいるモデルはこの方法
では確認できない。その盲点も #1011 の一部である。

サブエージェントのモデルを見るには、`claude -p --output-format json` の結果に含まれる
`modelUsage` を読む。トークン量がモデル別に出るので、親セッションの transcript と突き合わせる
と差分がサブエージェント分になる。

---

## squash はなぜ2箇所で強制されるのか

| 層 | 設定 | 覆う範囲 | 見落とす範囲 |
| --- | --- | --- | --- |
| GitLab プロジェクト | `squash_option: always`, `merge_method: ff` | Web UI を含む全マージ | *なぜ*かを何も語らない。プロジェクト管理者が変更できる |
| `guard.py` フック | `glab mr merge` に `--squash` を要求 | エージェントと CLI のマージ | Web UI のマージ、シェルの間接実行 |

フックはプロジェクト設定によって冗長にはならない。フックは**間違えたその瞬間に大きな音で
失敗し、規則の名を挙げる**。サーバ側の静かな書き換えは呼び出し側に何も教えないし、設定は
API 1回で元に戻せる。

**`merge_method: ff` こそが履歴を線形にしている**。ここが見落とされやすい。squash だけでは
足りない — `merge_method: merge` のままだと、GitLab は squash したコミットを作った**上に
マージコミットを乗せる**。!1020 (#1030) で実際に起きたのがこれである。

```
*   8cdb16dd Merge branch 'fix/1022-glab-guards' into 'develop'
|\
| * b40b02c1 fix: guard.py の空振りしていた… (!1020)
|/
* 1c054624 test: 記事プランと… (#1019)
```

これは GitHub の squash マージがしていたことでもなく、この履歴の他の部分の姿でもない。
`ff` なら squash したコミットが対象ブランチの先端の上に作られて fast-forward される。
1 Issue、1 コミット、マージの泡なし。

`ff` はソースブランチがマージコミットなしでマージできることを要求する。squash は構成上
それを満たす(squash したコミットは現在の対象ブランチの上に作られる)ので、通常の流れは
影響を受けない。コンフリクトは従来どおり作業ブランチで解決する。

---

## ガードは何であって、何でないか

ガードは**間違い**を止めるものであって、**回避**を止めるものではない。ガードはツールが
実行しようとしているコマンドを検査するが、シェルはいつでも検査を破れる —
`bash -c '...'`、`eval`、禁止された語に展開される変数。完全に塞ぐことは達成不可能であり、
目的でもない。

この区別は効いている。ガードは以前、実際より強いと思い込まれていた。**#1029 まで、ガードは
コマンド先頭に固定した正規表現で照合しており、`timeout 60 git push --no-verify` は素通り
した** — 回避ですらなく、ごく普通のコマンドの書き方である。現在はコマンドを構文解析する
(区切りで分割し、環境変数代入や `timeout` / `env` / `nice` / `sudo` のようなラッパを剥がし、
引用符を尊重する)ことで、この種の穴を塞いでいる。すべての穴を塞いだふりはしていない。

意図的な回避への防御は別の場所にあり、そこに置き続けなければならない。

- **GitLab の保護ブランチ設定** — 誰がマージ・push できるかをサーバ側で強制する
- **`scripts/git-hooks/pre-commit`** — エージェントでも人間でも、あらゆるコミット者に対して走る

### リダイレクトの誤判定 (#1034)

読み取り専用ステージが拒むリダイレクトの規則は、2度間違えて代償を払っている。
`2>&1` がファイル名 `1` への書き込みとして拒否される一方、`&>` は演算子として認識されず
`echo x &> real.txt` が素通りした。

見た目が似ていて意味が逆なので、`>` `>>` `>|` `&>`(ファイル名が対象)と
`>&`(ファイル記述子が対象)を別扱いにする。

ガードがコマンドをどう読んでいるかを見るには:

```bash
python3 .claude/hooks/guard.py explain 'timeout 60 git push --no-verify'
```

「フックが動いていない」と結論する前にこれを使うこと。その結論は一度下されて、そして
間違っていた。フックは動いており、判定がコマンドを取り逃していた(#1029)。

---

## status ベースのブロッカーを撤回した理由 (2026-09-03, #1024)

`CLAUDE.md` → **Dependency Resolution** は、かつて次の規則で始まっていた。

> **An open `blocked_by` link is the only status-based blocker.** If the formal dependency
> graph names an open Issue, the Issue is blocked. Full stop.

**この規則は撤回された。名指しした機構がここに存在しないからである。** GitHub の issue
dependency graph は方向を持つ — `blocked_by` がどの Issue がどの Issue をブロックするかを
述べる。GitLab Community Edition には同等物が無い。`blocks` / `is_blocked_by` は Premium で、
使えるリンク種別は `relates_to` だけであり、これは方向を持たない。`relates_to` は「A が B を
ブロックする」を表現できないので、ブロッカーにはなりえない。

規則は既に死にかけていた。このリポジトリは `blocked_by` リンクをほとんど使っておらず、
旧規則2がほぼ全ての判定が通る経路だった。存在しない機構を指す規則を残すことは、削除するより
悪い — 判定が、実際には確認していない形式的な根拠を主張する余地を与えてしまう。

置き換えたのは「何もなし」ではない。旧規則2が要求していたのと同じ調査を、例外なく全ての
依存に適用する: **コードを見る。** 「依存はブロックしない」と主張しながら**調べた具体的な
ファイル・エンドポイント・設定を挙げていない**判定は判定ではない。未検証として扱い、調査を
やり直すこと。スクリプトは入力を印字するだけで、コードベースを調べてはくれない。

---

## Merge precondition: マージ前提の検出を after-the-fact にした理由 (#1031)

`complete-issue` はマージ前に Issue の `status::` ラベルを読む(skill の Step 2)が、これは文書化された
手順であって機械的な保証ではない。#959 は、skill の指示があったにもかかわらず `status::In Progress`
のままマージされた実例である。

検討した選択肢は3つ(全文は #1031):

- **A** — `guard.py` が `glab mr merge` の前に GitLab API を引き、Issue が `status::QA` にあることを確認する。
  却下: フックがネットワークとトークンに依存する。#1023 が `check_status_label_integrity` で意図的に避けた点で
  あり、マージコマンドだけでは Issue 番号が構文上保証されない。
- **B** — `complete-issue` の Step 2 の記述だけに頼る。**唯一の**機構としては却下: #959 がそれでは足りないことを示している。
- **C**(採用)— `scripts/check-issue-labels.sh` を拡張し、不整合を事後に検出する。

**最初の実装はなぜ間違っていたか。** C の最初の版は、`status::Done` を持たない `state: closed` の Issue を全て
違反として挙げた。`merge-request` が開く Merge Request の説明には必ず `Closes #<issue番号>` があり
(`.claude/skills/merge-request/SKILL.md`)、GitLab はそのMRのマージでラベルと無関係に Issue を閉じる。
その理論自体は、`merge-request` が実際に閉じた Issue については正しい。しかしチェックは、それを他の
`state: closed` と区別できなかった。独立レビューがこのプロジェクトに対して走らせたところ **47件の偽陽性**が
出た — ほぼ全てが `status::` ラベル運用(#1023)より前の履歴上の Issue か、マージと無関係な理由(重複、wontfix)で
手動クローズされた Issue である。さらに開いている Issue と閉じた Issue の両リストを `per_page=100` の
ページ送りなしで取っていたため、1ページ目を超える Issue(閉じた Issue は490件)は一度も検査されていなかった。
過去に閉じた Issue を全て違反扱いすることは、当該 Issue 自身の Out of Scope 4(既存 Issue の遷移履歴は判定しない)
にも反していた。

**何に置き換えたか。** `check-issue-labels.sh` は何かを違反として挙げる前に2段で絞り、両リストを最後まで
ページ送りする(空ページが返るまでループ。`per_page=100` の1回では足りない):

1. **カットオフ** — `closed_at` が `2026-09-03T02:44:25Z`(#1023 のマージ時刻、`!1025`)以降の閉じた Issue だけを見る。
   それ以前は `status::` ラベルがまだ確立した運用ではなく、`status::Done` が無いことは段階の飛ばしを意味しない。
   これは Out of Scope 4 が禁じる履歴判定そのものになる。
2. **MR 突き合わせ** — カットオフを通った候補について `issues/<iid>/closed_by` を呼び、**マージ済み**の MR が
   結果にある場合だけ違反とする。手動クローズ(重複、wontfix 等)は #959 のパターンではないので放置する。

修正後にこのプロジェクトに対して実走させると、違反は47件から **1件**に減った — #1110(マージ済み `!1056` で
閉じたが `status::Inbox` のまま)。#959 パターンの実在する未解決の事例を、隠さずに正しく挙げている。

これは `guard.py` をネットワークなしに保ち(A の代償を避け)、文書だけの約束(B の穴)を超える。予防(`guard.py`)と
事後検出(`check-issue-labels.sh`)という、一意性チェックが既に使っている分担と同じである。突き合わせは候補ごとに
API を1回呼ぶが、ネットワークなしを主張されていたのは `guard.py` だけで、このスクリプトではない。

## Legal Transitions の表を置いた経緯 (#1023, #1031)

#1023 の一意性チェックは、段階を飛ばす遷移を捕まえない。`status::Ready → status::Done` でも前後とも
`status::` ラベルはちょうど1つで、「ちょうど1つ」は破られていない。これは別の失敗(#1031)である —
ラベルはどの瞬間も正しく見えるのに、**実際にはどの段階も通っていない**。

表は、`status::` ラベルを変える全ての skill(`triage-backlog`, `ready-issue`, `work-next`, `review-issue`,
`qa-issue`, `complete-issue`)に対して #1031 の時点で照合済みで、それらが行う遷移は全て表にある。
**既定は warn ではなく reject**: 警告は、この規則が守ろうとしている無人ループの下では無視されやすいため、
表にない遷移は `guard.py` が拒否する。検査は、対になった1回の呼び出しの `remove_labels=` / `add_labels=`
の値の (from, to) だけで、一意性チェックと同じくコマンド文面のみに基づく — ネットワークも、Issue の現在の
ラベルの参照もしない。**過去の遷移は検証しない**: 今後のラベル変更だけを門にし、Issue が現在のラベルに
至った経緯は判定しない。

## 「ちょうど1つ」の強制が2層である理由

squash が2箇所で強制されるのと同じ理由(上の「squash はなぜ2箇所で強制されるのか」)で、どちらの層も相手の
見えるものを見られない。フックは、コマンド文面だけから決まる2点だけを拒否する — `labels=`(全置換。`epic`、
`bug` 等を黙って落とす)と、`status::` の追加のみ/削除のみ。Issue の現在のラベルは引かないので、速く、
ネットワークも要らない。Issue の作成(`glab issue create --label status::Inbox,...`)は遷移ではなく、最初の
status はそこから来るので拒否しない。

`scripts/check-issue-labels.sh` は `0` 個と `2+` 個を別々に報告する。別の失敗だからである。**0 個が危険な方**:
`status::` の無い Issue はどのボード列にも現れず、`work-next` にもトリアージにも見えないので、誰も何も
指摘しない。2 個なら、Issue はどの定義済み段階にもいない。

## Process substitution と heredoc の扱い (#1035)

`bash -c '...'` と `eval` は意図的な回避(非目標)である — 呼び出し側が不透明な文字列を渡すことを選ぶ。
**process substitution(`>(...)` / `<(...)`)はそうではない**。`diff <(sort a) <(sort b)` は日常の調査コマンドで、
検査を破る手段ではなく、癖で無意識に書ける。そのため `split_commands()` は process substitution の内容を独立した
コマンドとして解析し、他と同じ破壊的コマンド検査に通す。外側のコマンドの引数に隠れさせない。

**扱うもの**: 1段の process substitution。**扱わないもの**: 入れ子(`diff <(cat <(x)) y` は1つの不透明な塊として
解析され、それ以上は分解されない)— 「完全性は主張しない」という、この節の他と同じ境界である。

heredoc の本体は関連するが逆の問題で、間接実行ではなく、シェル構文と誤読されていた文字列にすぎない。
`split_commands()` は `<<WORD` / `<<-WORD` の導入から終端行までの本体を解析前に読み飛ばすので、本体内の `;` や `>`
がコマンド区切りや実際のファイル書き込みと誤認されない。heredoc 自身のリダイレクト(`cat <<EOF > out.txt`)は
本体の前にあるので影響を受けず、引き続き捕まる。

## 受け入れ基準が5件までである理由

Issue 1件の固定コスト — Review + QA + Merge Request + マージ — は実測で15〜25分(#938: 68分中18分、#941: 99分中21分、
#940: 129分中20分)なので、分割は総所要時間を**増やす**。上限は速度を買わない。上限が抑えるのは**1回のロールバックで
何が失われるか**である。#940 は17シナリオを抱え、うち3つが QA で落ち、Issue は2回ロールバックされ、CLAUDE.md の
エスカレーションは4コミット全てを破棄した — 動いていた14シナリオを含めて。

分割が「兄弟を待たない」継ぎ目でだけ許されるのは、順序依存の分割が「先行作業がまだコードに無い」停滞を生むからで、
2026-09-08 の Backlog 棚卸しの `NOT READY` 17件中6件がこれだった。

## 利用者由来ラベルのバックフィル (2026-09-09)

既存の167件の Issue に、`~/.claude/projects/-home-seiji-*lets-blog-server/` の Claude Code transcript から
出自を復元してラベルを付けた。全ての `gh`/`glab issue create` 呼び出しを、その直前の実際のユーザープロンプトと
応答の `attributionSkill` に突き合わせた。`work-next`、`implement-issue`、`review-issue`、`qa-issue`、
`git-workflow`、`merge-request`、`discover-issues` の下で作られた Issue は Claude 由来、自由記述のユーザー
プロンプトはユーザー由来と分類した。

**682件中417件は全く帰属できなかった** — transcript は 2026-08-10 までしか遡れず、プロジェクトが GitHub Issues から
GitLab へ移行した際に番号の対応が壊れた。それらは推測せず、意図的にラベル無しのまま残した。バックフィルを完全と
みなさず、帰属不能な残りに推測を再実行しないこと。ラベル無しは「ユーザーが頼んでいない」の証拠ではない。

## Git hook の経緯 (#1039, #1040, #1055, #1319)

**バインドは確認するもので、断言するものではない。** `core.hooksPath` は git の設定であってリポジトリの内容ではなく、
どの clone にも checkout にも diff にも入らない。この節はかつて「この checkout では設定済み」と書いていて、
その主張は #976 から #1039 まで、全コミットについて偽だった — フックは一度も走っていなかった。バインドされて
いないフックの唯一の症状は**何も起きない**ことで、`guard.py` がエージェントのコミットに対して発火し続けることが、
強制が働いているという偽の安心を与える。`scripts/test_git_hooks_binding.py` は同じ確認を Python の単体テストで
行うので、ずれは既にルーチンの一部である実行で失敗する。

**`apps/web` のカバレッジ床 (#1040)** は、Coverage 節が要求する自動検知の経路である。`apps/web/jest.config.ts` の
`coverageThreshold` の床は、開発者がたまたま手で実行したときだけでなく、関係する全コミットで検査される。
`apps/web/package.json` が無いとき(フック自身の単体テスト用の使い捨てフィクスチャ repo 等)は飛ばす。
`scripts/check-changed-coverage.py` とは別の機構で、そちらはこのブランチが変えた行の C1/C2 だけを門にする。
`apps/web` はコミットを実際に行う worktree(`git rev-parse --show-toplevel`)から解決する。フックスクリプト自身の
物理位置(`__file__`)から導くと、リンクされた `git worktree` で誤ったディレクトリを検査した — QA が見つけた
退行(#1040, #1319)である。

## Unclassified path rejection がマージコミットを免除しない理由 (#1208, #1321, #1452)

`scripts/git-hooks/pre-commit` → `check_unclassified` は、`.claude/hooks/paths.py` が
テスト/プロダクション/宣言済み中立のどれにも分類しないパスのコミットを拒否する。
`check_phase_separation` はマージコミットを免除する(#1125)が、`check_unclassified` は
**免除しない**。理由は事情が違うからである: マージでテストとプロダクションが同じ差分に
混在するのは、取り込む側の複数コミット分がまとめて見えるという構造上正常な事象だが、
未分類パスがマージ経由で `develop` に入ることは正常な事象ではなく、まさに本節が名指す
再発そのものである(直下にスクリプトが1本増えるたびに `develop` が赤くなった
#1208 → #1321 → #1452)。ここで免除すると、CLAUDE.md → Merge Conflicts が許すローカルでの
衝突解決マージが、この検査を回避する抜け道になってしまう。

### 経路が1本足りなかった — `pre-merge-commit` の新設 (#1452、QA FAIL)

上の「免除しない」判断は、実装の初版では `scripts/git-hooks/pre-commit` にしか置かれていな
かった。ところが git は**コミットの経路ごとに別のフックを起動する**(`man githooks`):
`pre-commit` は `git commit` と、コンフリクトを解決した後の明示コミットしか拾わない。
**コンフリクトなしの `git merge`(= 最も普通の結果)は、自動でコミットを作る前に
`pre-merge-commit` だけを呼び出し、`pre-commit` は一切起動しない。** このリポジトリの
`scripts/git-hooks/` に `pre-merge-commit` が無かったため、`CLAUDE.md` → Merge Conflicts が
標準手順として示す `git merge origin/develop` の**最も普通の実行結果**が、この検査を
一切通らずに成立していた — QA が実測: `rc=0`、フックの出力なし。まさに
#1208 → #1321 → #1452 自身が繰り返してきた事故パターン(他ブランチで追加された未分類パスが
develop の取り込みで気づかれず入り込む)が通る道であり、しかも検査が発火しないので
気づく手段がそもそも無い、という形で現れた。欠陥は判断の誤りではなく、置き場所が
1つ足りないという**不足**だった。

是正は `scripts/git-hooks/pre-merge-commit` を新設し、その経路でも同じ「免除しない」判断を
実行することである。ただし `pre-commit` を丸ごと委譲はしない。`man githooks` の既定の
`pre-merge-commit` サンプルは有効なら `pre-commit` を走らせるが、それに倣うと次の理由で
**正当なマージを壊す**:

- 検査1(フェーズ分離)は `merge_in_progress()` で即 return するので、委譲しても何も得ない。
- **検査3(テストファースト)にはマージ免除が無い。** マージでステージされる差分は取り込む側の
  全コミット分なので `prod`(プロダクションパス)はほぼ常に非空になる。一方
  `check_test_first` が「テストが先にあるか」を見るのは
  `git diff --name-only <merge-base> HEAD` — つまり**自ブランチ**の変更だけであり、
  `.claude/` と `docs/` は中立分類なので、ワークフローや文書だけを直す Issue のブランチ
  (このリポジトリで最も普通の Issue の形)には `is_test` が1件も無い。委譲すると、
  そのブランチで `develop` を取り込む**正当な** `git merge origin/develop` が拒否されてしまう。
- 検査2(テストの黙殺)・検査4(`apps/web` カバレッジ床)は #1452 では扱わず #1460 に
  回した。#1460 の結論(利用者の判断、2026-10-01)は本節末尾の「#1460 の追補」を参照。

したがって `pre-merge-commit` はこの検査(検査5)だけを走らせる。実装は
`scripts/git-hooks/pre-commit` を `importlib` でモジュールとして読み込み、
`check_unclassified(files, merging=True)` を直接呼ぶ — ロジックの実体は1箇所に保つ。
`merging=True` を明示するのは、`pre-merge-commit` フックの実行時点では **`MERGE_HEAD` が
まだ書かれていない**ため(`man githooks`: 「マージが自動で成立した後、コミットを作る前」に
呼ばれる。実測でも `git rev-parse -q --verify MERGE_HEAD` は空)。`merge_in_progress()` に
任せると、まさにこの経路でマージ用ヒントが常に欠落する。

**免除しなくても詰みにはならない — レビュアーが実際の `git worktree` で実測した。** マージコミットが
未分類パスを持ち込んだときの解決策は、**その同じコミットに新ファイルと `paths.py` への分類を
両方含める**ことである(CLAUDE.md → Merge Conflicts が許す「衝突解決コミットは機械的な
突き合わせ」の範囲内)。これが成立する前提は、フックが**コミットする側の** worktree から
`paths.py` を読むことにある。`core.hooksPath` は現在**相対値**(`scripts/git-hooks`、
`git config --show-origin core.hooksPath` → `file:.git/config  scripts/git-hooks`)なので、
どの worktree からコミットしても、git はそのコミットを行っている worktree 自身の
`pre-commit` を起動し、`pre-commit` はそこから相対的に `paths.py` を import する
(`scripts/git-hooks/pre-commit` の `HERE`/`sys.path.insert` を参照)。レビュアーの実測:

- 相対 `core.hooksPath`(現状): 新規ファイルとその分類を1コミットでステージ → 成功(`rc=0`)
- 同じリポジトリで `core.hooksPath` を main worktree への絶対パスに書き換え: 同じコミットが
  拒否される — その worktree 自身の `paths.py` が分類しているにもかかわらず、フックは
  main worktree の `pre-commit`/`paths.py` を実行するため

つまり `check_unclassified` がマージコミットを免除しなくても解決不能にならないのは、
`core.hooksPath` が相対値であることに依存した性質であり、絶対値に切り替わると崩れる。
`committing_worktree_root()`(`scripts/git-hooks/pre-commit`)の docstring と
`scripts/test_check_web_coverage_floor.py` は、このリポジトリの実環境が絶対パス構成である
ことを前提に書かれており、**#1319(未解決)** がその構成のもとでの linked worktree の
扱いを扱っている。`check_unclassified` はこの依存を自身の docstring に明記するに留め、
`core.hooksPath` の構成自体は #1319 の領域として変更しない。

**`pre-merge-commit` 経路の脱出路も、同じ「コミットする側の worktree」への依存に帰着する。**
`pre-merge-commit` が非0で終わると、git は**コミットを作らずにマージを中断する**が、
**マージ結果は index に残り、`MERGE_HEAD` もこの時点で書かれる**(`man githooks`)。つまり
中断した直後の状態は、上の「相対 `core.hooksPath` で新規ファイルとその分類を1コミットで
ステージ」する場合と同じ状態(`MERGE_HEAD` あり、index にマージ結果あり)になり、その場で
`paths.py` に分類を足して `git commit` すれば、以後は(`pre-merge-commit` ではなく)
`pre-commit` が起動して解決する。実測:
`scripts/test_pre_merge_commit_unclassified.py` →
`test_paths_py_classification_added_after_aborted_merge_lets_commit_succeed`。

### #1460 の追補 — `pre-merge-commit` に検査2だけを足し、検査4は足さない

利用者の判断(2026-10-01): `pre-merge-commit` に走らせるのは検査2(テストの黙殺)を足すところまで。
検査4(`apps/web` カバレッジ床)はマージ経路では走らせない(`npm run test:coverage` は実測20秒超で、
マージのたびに課さない)。検査2の免除は `pre-commit` の #1125(「`MERGE_HEAD` 側にあれば許す」)を
流用できない: コンフリクトなしのマージでは追加行がすべて取り込み側由来で、しかも `pre-merge-commit`
の時点では `MERGE_HEAD` が無いため、何も拒否できなくなる。そこで**マージ前の `HEAD:<path>` に
同じパターンが無いときだけ拒否**する。この結果、同じ黙殺の取り込みが「コンフリクトなしなら拒否、
コンフリクトありなら #1125 により許可」と経路で異なる。揃えるかは #1460 の Open Question で、
本変更はコンフリクトあり経路を変えていない。`cherry-pick` / `revert` / `rebase` には git にフックが
無く、塞げない限界として CLAUDE.md に明記した。

## Coverage check が測定不能なコードを失敗にしない理由 (#942, #935)

カバレッジランナーが通らない production コード(`apps/*/webviews/` の素の `.js`、`infra/e2e-stubs/**`
= docker-compose の下でしか動かない Node プロセス、`next.config.ts`)には、jest も JaCoCo も届かず、レポートが
存在しえない。それを要求すると、それらのファイルだけに触れる Issue では Merge Request を開くこと自体が不可能に
なった(#942, #935)。そこで**測定不能として報告して飛ばし**、失敗にはしない。受け入れテスト層で検証する — `docs/COVERAGE_TARGETS.md`
が `extension.ts` と Panel コンストラクタに既に適用している慣例と同じである。これは**カバレッジ**の免除だけで、
`paths.py` は依然としてこれらを production に分類するので、フェーズ分離と test-first は全面的に適用される。

## SILENCERS を単一にした理由 (#1055)

#1055 以前は、2つのフックが `SILENCERS` をそれぞれ独立に書き直していて、コピーは既にずれていた。`pre-commit` は
`guard.py` にあった `@(skip|fixme)` タグのパターンを欠いていた。このリポジトリの受け入れテストは Gherkin
(`apps/web/e2e/features/**/*.feature`)で、`@skip` / `@fixme` はまさに Gherkin シナリオを黙らせる方法だ — 欠けた
パターンのせいで、`@skip` で黙らせた `.feature` が `pre-commit` を素通りしてコミットされ、検出されなかった。
`.claude/hooks/paths.py` が経路分類で既に作った前例に従い、`silencers.py` を1箇所の定義とした。

## release の直接 push が squash 規則の違反ではない理由 (#1274, #1305)

squash / `ff` の規則は `develop` への Issue Merge Request に適用される。`main` へのリリースマージは
`scripts/release-verify-tag.py` による直接 push(#1274)で、Issue MR ではなく、この規則の違反ではない。後段が
`main` 上のマージコミットをそう誤読してはならない。同スクリプトによる、リリースコミット R と次の開発版コミット V
の `develop` への直接 push(#1305)も、R がリリースマージが要求する全検証を通った後にだけ行われる、同じく文書化された
例外である。

## ワークフローのエージェントをフォアグラウンドで起動させる理由 (#1269)

無人キュー(`claude -p`)は、段階の終了時に Issue が `Done` でなければ「やりかけ」として再開する(上限 3 回)。
サブエージェントをバックグラウンドで起動すると、メイン側が「完了通知を待つ」と宣言してターンを終え、`claude -p` は
そこで結果を返す。実装が進んでいても Review 以降が走らず、再開が 1 回消費される。実測は #1202(`issue-1202-resume-1`)、
#1203(`issue-1203-work`、実時間 11 分に対し `duration_ms` は 1 分)、#1078(`issue-1078-resume-3`、残りのワークフロー全体を
バックグラウンドのエージェントに渡して終了し、再開を使い切って park)。

`guard.py` の `agent` 検査は、`subagent_type` が `implementer` / `reviewer` / `qa` / `project-planner` で
`run_in_background` が真の呼び出しだけを拒否する。`general-purpose` などの汎用エージェントは対象外にした。対話作業では
バックグラウンド起動が正当な使い方であり、フックの入力からはワークフロー中かどうかを判別できないためである。#1078 のように
「残りのワークフローを汎用エージェントに渡す」型は機械的には止められず、各スキルの手順書の文言(フォアグラウンドで起動する、
結果を受け取るまでターンを終えない、残りをバックグラウンドのエージェントに渡さない)だけで防ぐ。
