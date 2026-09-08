#!/usr/bin/env python3
"""`implementer` サブエージェント1回あたりのターン数中央値を測る。

`#1199` Requirement 6。窓の大きさ(直近何回分を見るか)は引数で受け取る。
手順の詳細と、なぜ `metrics.json` からは中央値が出せないかは
`docs/IMPLEMENTER_PERFORMANCE_MEASUREMENT.md` を参照。

データソース:
  `~/.claude/projects/-home-seiji-src-lets-blog-server/<session_id>/subagents/*.jsonl`
  が implementer サブエージェント1回分のトランスクリプトそのもの、対応する
  `*.meta.json` が `{"agentType": "...", ...}` を持つ。`agentType == "implementer"`
  のものだけを選び、jsonl 内で `type == "assistant"` のレコード数を「そのサブ
  エージェント実行1回分のターン数」として数える。実行時刻の代理として jsonl
  ファイルの mtime を使う(セッション終了後トランスクリプトは不変になるため)。

使い方:
    python3 scripts/measure-implementer-turns.py --window 20
    python3 scripts/measure-implementer-turns.py --window 20 \
        --base ~/.claude/projects/-home-seiji-src-lets-blog-server
"""

import argparse
import json
import statistics
from pathlib import Path

DEFAULT_BASE = "~/.claude/projects/-home-seiji-src-lets-blog-server"


def collect_turn_counts(base: Path) -> list[tuple[float, int]]:
    records: list[tuple[float, int]] = []
    for meta_path in base.glob("*/subagents/*.meta.json"):
        try:
            meta = json.loads(meta_path.read_text())
        except (OSError, json.JSONDecodeError):
            continue
        if meta.get("agentType") != "implementer":
            continue

        jsonl_path = meta_path.with_name(meta_path.name[: -len(".meta.json")] + ".jsonl")
        if not jsonl_path.exists():
            continue

        turns = 0
        for line in jsonl_path.read_text().splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            if record.get("type") == "assistant":
                turns += 1

        records.append((jsonl_path.stat().st_mtime, turns))

    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--window",
        type=int,
        default=20,
        help="直近何回分の implementer 実行を対象にするか(既定20)",
    )
    parser.add_argument(
        "--base",
        default=DEFAULT_BASE,
        help="Claude プロジェクトディレクトリのパス(既定 %(default)s)",
    )
    args = parser.parse_args()

    if args.window <= 0:
        parser.error("--window must be a positive integer")

    base = Path(args.base).expanduser()
    records = collect_turn_counts(base)
    records.sort(key=lambda r: r[0], reverse=True)
    window = [turns for _, turns in records[: args.window]]

    print(f"window={args.window}")
    print(f"n={len(window)}")
    print(f"turns per run={window}")
    if window:
        print(f"median={statistics.median(window)}")
    else:
        print("median=N/A (no data)")


if __name__ == "__main__":
    main()
