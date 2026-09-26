#!/usr/bin/env python3
"""docs/ACCEPTANCE_CRITERIA.md §6「集計」が §2 の表と一致することを検証する(#1133)。

    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'

## なぜ機械的に検査するのか

§6 は受け入れテストの進捗を外から読む唯一の要約だが、§2 の各行を `検証済` にする AT Issue は
集計を取り直さない。#927 の作成後に集計は一度も更新されず、`検証済` が実数の半分以下
(33 対 77)のまま放置された。手で数え直す運用は既に5回失敗している。

§2 の表を数え直し、§6 の状態別件数・領域別内訳・合計と突き合わせる。
`test_documentation_tree_structure.py` などの既存の文書検査と同じく、書式ではなく数値の一致だけを見る。
"""

import os
import re
import unittest
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))
DOC = os.path.join(REPO_ROOT, "docs/ACCEPTANCE_CRITERIA.md")

# §6 の状態別の表の行の見出し(§2 の状態欄の先頭語)。`既存spec(部分)` は `既存spec` に含める。
# `実装済み` は §1 に定義の無い状態だが §2 に実在するので、数えなければ合計が合わない。
STATES = ("検証済", "部分的に検証", "実装中", "実装済み", "既存spec", "未着手", "対象外")


def _read():
    with open(DOC, encoding="utf-8") as f:
        return f.read()


def _section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


def _cells(line):
    return [c.strip() for c in re.split(r"(?<!\\)\|", line)[1:-1]]


def count_inventory(text):
    """§2 の機能ID行から (状態別, 領域別, 合計) を数える。"""
    body = _section(text, "\n## 2. 機能インベントリ", "\n## 3.")
    states, domains = Counter(), Counter()
    for line in body.splitlines():
        m = re.match(r"\| AC-([A-Z]+)-\d+ \|", line)
        if not m:
            continue
        state_cell = _cells(line)[-1]
        matched = [s for s in STATES if state_cell.startswith(s)]
        if not matched:
            raise AssertionError(f"未知の状態です: {state_cell[:40]!r} ({line[:20]})")
        states[matched[0]] += 1
        domains[m.group(1)] += 1
    return states, domains, sum(states.values())


def parse_summary(text):
    """§6 の状態別の表・領域別の表・「§2 に列挙した機能ID」の記載を読む。"""
    body = _section(text, "\n## 6. 集計", "\n## 7.")
    total = int(re.search(r"§2 に列挙した機能ID: \*\*(\d+)\*\*", body).group(1))
    states, domains = Counter(), Counter()
    for line in body.splitlines():
        cells = _cells(line) if line.startswith("|") else []
        if len(cells) == 2 and re.fullmatch(r"\d+", cells[1]):
            name = cells[0].strip("`")
            for s in STATES:
                if name.startswith(s):
                    states[s] += int(cells[1])
                    break
        # 領域別: `| `SET` | 3 | | `AI` | 11 | ... |`
        for i in range(0, len(cells) - 1, 3):
            m = re.fullmatch(r"`([A-Z]+)`", cells[i])
            if m and re.fullmatch(r"\d+", cells[i + 1]):
                domains[m.group(1)] += int(cells[i + 1])
    section2_total = re.search(r"\| \*\*§2 合計\*\* \| \*\*(\d+)\*\* \|", body)
    return states, domains, total, int(section2_total.group(1))


class AcceptanceCriteriaSummaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        text = _read()
        cls.inv_states, cls.inv_domains, cls.inv_total = count_inventory(text)
        cls.sum_states, cls.sum_domains, cls.sum_total, cls.sum_table_total = parse_summary(text)

    def test_status_counts_match_inventory(self):
        self.assertEqual(dict(self.inv_states), dict(self.sum_states))

    def test_all_states_appear_in_summary(self):
        # 実装中 / 部分的に検証 のように後から増えた状態が欄ごと欠けていないこと。
        for state, n in self.inv_states.items():
            self.assertIn(state, self.sum_states, f"{state} ({n}件) が §6 に無い")

    def test_domain_counts_match_inventory(self):
        self.assertEqual(dict(self.inv_domains), dict(self.sum_domains))

    def test_totals_match_inventory(self):
        self.assertEqual(self.inv_total, self.sum_total)
        self.assertEqual(self.inv_total, self.sum_table_total)


if __name__ == "__main__":
    unittest.main()
