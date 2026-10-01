#!/usr/bin/env python3
"""3秒予算の対象一覧(`docs/ACCEPTANCE_CRITERIA.md` §10.4 / §10.5)の解析(#1477)。

一覧とシナリオの照合(`check-response-budget-coverage.py`、#1477)と、一覧とコードの照合(#1544)が
同じ一覧を読むので、解析だけをここへ切り出してある。依存は標準ライブラリのみ。

    from response_budget_list import parse_budget_list
    rows = parse_budget_list(open('docs/ACCEPTANCE_CRITERIA.md', encoding='utf-8').read())

返す行は、画面なら1ページ1行(`ident` は `/projects/[id]/plan` のようなルート)、
Server Action なら1関数1行(`ident` は `updateProjectNameAction` のような関数名)。
表では1行に複数の Action が `<br>` 区切りで入っているので、ここで1つずつに分ける。
"""

import re
from dataclasses import dataclass

#: §10.4 / §10.5 の見出しから、次の `### ` 見出しの手前までを表として読む。
SECTION_PAGES = "### 10.4"
SECTION_ACTIONS = "### 10.5"

#: 分類の正規の名前。`予算対象外` は `予算対象` で始まるので、前方一致で比べてはならない。
CLASS_TARGET = "予算対象"

_CELLS = 5
_PAGE_IDENT = re.compile(r"^`(/[^`\s]*)`")
_BACKTICK = re.compile(r"`([^`]+)`")

#: 名前の後ろの注記にこの印があれば、その Server Action は受け入れシナリオから到達できない
#: (UI のどこからも呼ばれていない等)。印の後ろに理由が要る。分類は「予算対象」のまま。
UNREACHABLE_MARK = "受け入れシナリオから到達できない:"


@dataclass(frozen=True)
class BudgetRow:
    """一覧の1項目。`kind` は `page` か `action`。"""

    kind: str
    ident: str
    classification: str
    source: str
    #: 受け入れシナリオから到達できない理由。到達できるなら空。Server Action の注記にだけ付く。
    unreachable: str = ""

    @property
    def is_budget_target(self) -> bool:
        return self.classification == CLASS_TARGET


def _section_lines(markdown: str, heading: str) -> list[str]:
    lines = markdown.splitlines()
    for start, line in enumerate(lines):
        if line.startswith(heading):
            break
    else:
        raise ValueError(f"見出し「{heading}」が見つかりません")
    section = []
    for line in lines[start + 1:]:
        if line.startswith("### ") or line.startswith("## "):
            break
        section.append(line)
    return section


def _table_rows(section: list[str], heading: str) -> list[list[str]]:
    rows = []
    for line in section:
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split(" | ")]
        if cells[0].startswith("操作") or set(cells[0]) <= {"-", " "}:
            continue
        if len(cells) != _CELLS:
            raise ValueError(f"{heading} の表の行が {_CELLS} 列ではありません: {line[:80]}")
        rows.append(cells)
    return rows


def _parse_pages(markdown: str) -> list[BudgetRow]:
    result = []
    for cells in _table_rows(_section_lines(markdown, SECTION_PAGES), SECTION_PAGES):
        match = _PAGE_IDENT.match(cells[0])
        if not match:
            raise ValueError(f"{SECTION_PAGES} の行からルートを読めません: {cells[0][:80]}")
        result.append(BudgetRow("page", match.group(1), cells[4], SECTION_PAGES))
    return result


def _parse_actions(markdown: str) -> list[BudgetRow]:
    result = []
    for cells in _table_rows(_section_lines(markdown, SECTION_ACTIONS), SECTION_ACTIONS):
        # 先頭の要素はファイルのパス、以降が Action 名(`(環境間同期)` のような注記が続くことがある)。
        found = 0
        for part in cells[0].split("<br>")[1:]:
            match = _BACKTICK.match(part.strip())
            if not match:
                continue
            found += 1
            note = part.strip()[match.end():]
            reason = ""
            if UNREACHABLE_MARK in note:
                reason = note.split(UNREACHABLE_MARK, 1)[1].strip().rstrip(")").strip()
                if not reason:
                    raise ValueError(f"{SECTION_ACTIONS} の「{match.group(1)}」に到達できない印があるのに理由がありません")
            result.append(BudgetRow("action", match.group(1), cells[4], SECTION_ACTIONS, reason))
        if not found:
            raise ValueError(f"{SECTION_ACTIONS} の行から Server Action 名を読めません: {cells[0][:80]}")
    return result


def parse_budget_list(markdown: str) -> list[BudgetRow]:
    """§10.4(画面)と §10.5(Server Action)を読み、1項目1行の一覧を返す。

    見出しや行の形が想定と違うときは黙って飛ばさず `ValueError`。
    """
    return _parse_pages(markdown) + _parse_actions(markdown)
