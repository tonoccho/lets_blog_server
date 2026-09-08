#!/usr/bin/env python3
"""1 Issue あたりの所要時間(分)の中央値を測る。

`#1199` Requirement 6。窓の大きさ(直近何件分を見るか)は引数で受け取る。
手順の詳細は `docs/IMPLEMENTER_PERFORMANCE_MEASUREMENT.md` を参照。

データソース:
  `queue-metrics-json.py`(このリポジトリ外)が書き出す `metrics.json` の
  `issues[]` 配列。各要素は `{"iid": ..., "minutes": ..., "run": "20260908-224137", ...}`
  の形で、1件が1 Issue の処理にかかった分数と、それがどのキュー実行(`run`)に
  属するかを持つ。`run` は `YYYYMMDD-HHMMSS` 形式の文字列なので、文字列としての
  降順ソートがそのまま時刻の降順になる。

  `headline.minutes_per_issue` はこれとは別物であり、直接使えない —
  集計窓は「直近 RUNS(既定12)キュー」のローリング平均で、Issue 単位の中央値
  ではなく、キュー単位で重み付けされた平均分数である。

使い方:
    python3 scripts/measure-issue-duration.py --window 20
    python3 scripts/measure-issue-duration.py --window 20 \
        --metrics-json /home/seiji/src/infra/proxy/html/ai/queue/metrics.json
"""

import argparse
import json
import statistics
from pathlib import Path

DEFAULT_METRICS_JSON = "/home/seiji/src/infra/proxy/html/ai/queue/metrics.json"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--window",
        type=int,
        default=20,
        help="直近何件の完了 Issue を対象にするか(既定20)",
    )
    parser.add_argument(
        "--metrics-json",
        default=DEFAULT_METRICS_JSON,
        help="queue-metrics-json.py が書き出す metrics.json のパス(既定 %(default)s)",
    )
    args = parser.parse_args()

    if args.window <= 0:
        parser.error("--window must be a positive integer")

    metrics_path = Path(args.metrics_json).expanduser()
    data = json.loads(metrics_path.read_text())
    issues = data.get("issues", [])

    issues_sorted = sorted(issues, key=lambda issue: issue.get("run", ""), reverse=True)
    window = [issue["minutes"] for issue in issues_sorted[: args.window]]

    print(f"window={args.window}")
    print(f"n={len(window)}")
    print(f"minutes per issue={window}")
    if window:
        print(f"median={statistics.median(window)}")
    else:
        print("median=N/A (no data)")


if __name__ == "__main__":
    main()
