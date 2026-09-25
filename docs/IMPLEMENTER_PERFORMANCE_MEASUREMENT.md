# implementer のターン数と所要時間の計測手順

`#1199` Requirement 6。CLAUDE.md がどんな規則を課すかではなく、**その規則の下で実際にどれ
だけターンと時間を使っているか**を、再現可能なコマンドで測るための手順を記録する。

窓の大きさ(直近何回分 / 何件分を見るか)は、下記2本のスクリプトの `--window` 引数で
指定する。**既定値は20**。AC-1 の検証用コマンドはこの既定値のまま(または明示的に
`--window 20`)を使い、値そのものは手順の中で固定して示す — 引数を受け付けない
決め打ちのスクリプトにはしない。

---

## 1. `implementer` サブエージェント1回あたりのターン数中央値

### データソース

`~/.claude/projects/-home-seiji-src-lets-blog-server/<session_id>/subagents/*.jsonl` が
サブエージェント1回分のトランスクリプトそのもの、対応する `*.meta.json` が
`{"agentType": "...", ...}` を持つ。`agentType == "implementer"` のものだけを選び、
jsonl 内で `type == "assistant"` のレコード数を「そのサブエージェント実行1回分のターン数」
として数える。

`metrics.json`(後述)からはこの中央値は出せない —
`breakdown[]` は `turns`(窓全体の合計)と `runs`(実行回数)しか持たず、平均
(`turns / runs`)までしか復元できない。中央値を得るには jsonl を直接読むしかない。

### スクリプト

`scripts/measure-implementer-turns.py` — `--window N`(既定20)で直近N回分の
`implementer` 実行を対象にする。`--base` で Claude プロジェクトディレクトリのパスを
上書きできる(既定 `~/.claude/projects/-home-seiji-src-lets-blog-server`)。

実行時刻の代理として jsonl ファイルの mtime を使う(セッション終了後トランスクリプトは
不変になるため、mtime はそのサブエージェント実行が完了した時刻にほぼ一致する)。

### AC-1 検証用コマンド(窓=20固定)

```bash
python3 scripts/measure-implementer-turns.py --window 20
```

2026-09-08 時点で実行した結果(このコマンドをそのまま実行した出力):

```
window=20
n=20
turns per run=[9, 27, 44, 119, 61, 129, 168, 83, 280, 198, 131, 47, 145, 102, 134, 223, 157, 33, 98, 145]
median=124.0
```

この `124.0` は**現在の実測値**であり、下記「3. 歴史的スナップショット」の `143` とは
別物である(算出時刻が異なるため、当然一致しない)。

---

## 2. 直近N件の完了 Issue の所要時間(分)中央値

### データソース

`queue-metrics-json.py`(`~/.local/bin/queue-metrics-json.py`、このリポジトリ外)が書き出す
`metrics.json`(既定パス `/home/seiji/src/infra/proxy/html/ai/queue/metrics.json`、公開URL
`https://server.tonoccho.local/ai/queue/`)の `issues[]` 配列。各要素は
`{"iid": ..., "minutes": ..., "run": "20260908-224137", ...}` の形で、1件が1 Issue の処理に
かかった分数と、それがどのキュー実行(`run`)に属するかを持つ。

`headline.minutes_per_issue` は**これとは別物**であり、直接使えない —
`queue-metrics-json.py` 冒頭のコメントにある通り集計窓は「直近 `RUNS`(既定12)キュー」の
ローリング平均で、Issue 単位の中央値ではなく、キュー単位で重み付けされた平均分数である。

### スクリプト

`scripts/measure-issue-duration.py` — `--window N`(既定20)で直近N件の完了 Issue を
対象にする。`--metrics-json` でパスを上書きできる(既定は上記の
`/home/seiji/src/infra/proxy/html/ai/queue/metrics.json`)。

`run` は `YYYYMMDD-HHMMSS` 形式の文字列なので、文字列としての降順ソートがそのまま時刻の
降順になる。直近N件に絞ってから `minutes` を取り出し、中央値を計算する。

### AC-1 検証用コマンド(窓=20固定)

```bash
python3 scripts/measure-issue-duration.py --window 20
```

2026-09-08 時点で実行した結果:

```
window=20
n=20
minutes per issue=[11.6, 23.0, 26.1, 23.6, 9.8, 44.7, 26.0, 20.4, 37.5, 30.4, 22.6, 29.9, 22.8, 22.4, 17.6, 15.6, 30.2, 29.8, 22.6, 49.8]
median=23.3
```

この時点では `metrics.json` の `issues[]` は20件しかなく(窓ちょうど)、この値は
下記スナップショットの `38.4` とは異なる測定タイミング・異なる集計内容(このコマンドは
Issue 単位の中央値、`38.4` はキュー窓の平均)なので、一致しないのが当然である。

---

## 3. 歴史的スナップショット(2026-09-08、`#1199` 起票時点)

`#1199` 起票時点の Background に記録されている実測値:

- `implementer` サブエージェント1回あたりターン数**中央値 143**(平均 170.6、36回・
  合計6,142ターン)
- 1 Issue あたり所要時間**中央値 38.4分**(`headline.minutes_per_issue`、直近12キュー・
  31 Issue の平均としての値。本来は平均であって中央値ではないが、`#1199` の Background は
  この値を「1 Issue あたり平均」として記録しており、本書はその記述をそのまま引き写す)

**この2値は後から再計算・再現できない。** 理由は `metrics.json` の集計窓が「直近12キュー」
でローリングするためで、`#1199` 起票と同日 `2026-09-08T21:31:51Z` の時点で既に
`headline.minutes_per_issue` は `38.4 → 35.1`、`implementer` の `turns`/`runs` は
`6142/36 → 5431/33` に変化していた。つまり同じ手順を今実行しても `143` / `38.4分` は
再現されない — 上記「1.」「2.」で実行したコマンドの出力(`124.0` / `23.3`)が実際に別の
値になっていることも、このローリング窓による変化の一例である。

この文書はこの2値を**書き留めるためのもの**であって、再計算する対象ではない。今後この文書
の手順を実行して得られる値は、常にこの2値とは別の「現在の実測値」として扱うこと。

---

## まとめ

| 指標 | コマンド | 窓 | 2026-09-08 起票時点の値 | 本書のコマンドで得た値(同日、別時刻) |
| --- | --- | --- | --- | --- |
| `implementer` ターン数中央値 | `python3 scripts/measure-implementer-turns.py --window 20` | 直近20回 | 143(再現不可) | 124.0 |
| 1 Issue あたり所要時間 | `python3 scripts/measure-issue-duration.py --window 20` | 直近20件 | 38.4分(再現不可、かつ平均であって中央値ではない) | 23.3 |
