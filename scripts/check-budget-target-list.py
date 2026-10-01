#!/usr/bin/env python3
"""3秒予算の対象一覧(`docs/ACCEPTANCE_CRITERIA.md` §10)とコードの照合(#1544)。

コード上の全ページ(`apps/web/src/app/**/page.tsx`)と全 Server Action
(`apps/web/src` の `export async function *Action`)が §10.4 / §10.5 に載っていて、
一覧にあってコードに無い行も無いことを確かめる。食い違いがあれば非0で終わる:

    python3 scripts/check-budget-target-list.py

画面や Server Action を足したのに一覧へ行を足し忘れると落ちる。一覧の見出しの件数
(「全N ページ」「全N件」)が実際の件数と違うときも落ちる。

単体テスト: `python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'`
(`scripts/test_check_budget_target_list.py`。実リポジトリの一致もそこで検査する)。

文書の読み方(この形を変えるときはここも直す):
- §10.4 の表は、行の先頭セルが `` `<route>` の初回表示 ``。
- §10.5 の表は、行の先頭セルに `` `<name>Action` `` が1つ以上。
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.abspath(os.path.join(HERE, ".."))

DOC = "docs/ACCEPTANCE_CRITERIA.md"
APP_DIR = "apps/web/src/app"
SRC_DIR = "apps/web/src"

ACTION_DEF = re.compile(r"export\s+async\s+function\s+(\w+Action)\b")
PAGES_HEADING = re.compile(r"^### 10\.4 .*?全(\d+)ページ", re.M)
ACTIONS_HEADING = re.compile(r"^### 10\.5 .*?全(\d+)件", re.M)
SECTION_PAGES = re.compile(r"^### 10\.4 .*?$(.*?)(?=^### |\Z)", re.M | re.S)
SECTION_ACTIONS = re.compile(r"^### 10\.5 .*?$(.*?)(?=^### |\Z)", re.M | re.S)
PAGE_CELL = re.compile(r"^\|\s*`(/[^`]*)`\s*の初回表示", re.M)
ACTION_NAME = re.compile(r"`(\w+Action)`")


def _is_test_path(path):
    return "__tests__" in path.split(os.sep) or re.search(r"\.(test|spec)\.[jt]sx?$", path) is not None


def _walk(base):
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            yield os.path.join(dirpath, name)


def code_pages(root):
    """`app/**/page.tsx` のルート(ルートグループ `(x)` は URL に現れないので除く)。"""
    base = os.path.join(root, APP_DIR)
    routes = set()
    for path in _walk(base):
        if os.path.basename(path) != "page.tsx":
            continue
        rel = os.path.relpath(os.path.dirname(path), base)
        segments = [] if rel == "." else [s for s in rel.split(os.sep) if not re.fullmatch(r"\(.*\)", s)]
        routes.add("/" + "/".join(segments))
    return routes


def code_actions(root):
    """`apps/web/src` 配下(テストを除く)の `export async function *Action` の名前。"""
    names = set()
    for path in _walk(os.path.join(root, SRC_DIR)):
        if not re.search(r"\.[jt]sx?$", path) or _is_test_path(path):
            continue
        with open(path, encoding="utf-8") as f:
            names.update(ACTION_DEF.findall(f.read()))
    return names


def listed_pages(doc):
    """§10.4 に載っているルート。節が無ければ None。"""
    m = SECTION_PAGES.search(doc)
    return None if m is None else set(PAGE_CELL.findall(m.group(1)))


def listed_actions(doc):
    """§10.5 に載っている Server Action。節が無ければ None。"""
    m = SECTION_ACTIONS.search(doc)
    if m is None:
        return None
    names = set()
    for line in m.group(1).splitlines():
        if line.startswith("|"):
            first_cell = line.split("|")[1]
            names.update(ACTION_NAME.findall(first_cell))
    return names


def heading_counts(doc):
    """(§10.4 見出しの件数, §10.5 見出しの件数)。無ければ None。"""
    p = PAGES_HEADING.search(doc)
    a = ACTIONS_HEADING.search(doc)
    return (int(p.group(1)) if p else None, int(a.group(1)) if a else None)


def find_problems(root):
    doc_path = os.path.join(root, DOC)
    if not os.path.isfile(doc_path):
        return [f"{DOC} が見つかりません"]
    with open(doc_path, encoding="utf-8") as f:
        doc = f.read()

    problems = []
    pages_listed = listed_pages(doc)
    actions_listed = listed_actions(doc)
    if pages_listed is None:
        problems.append(f"{DOC} に §10.4(画面の初回表示)の節が読めません")
    if actions_listed is None:
        problems.append(f"{DOC} に §10.5(Server Action)の節が読めません")
    if problems:
        return problems

    pages_code = code_pages(root)
    actions_code = code_actions(root)
    for label, in_code, in_doc, section in (
        ("ページ", pages_code, pages_listed, "§10.4"),
        ("Server Action", actions_code, actions_listed, "§10.5"),
    ):
        for item in sorted(in_code - in_doc):
            problems.append(f"{label} {item} はコードにあるのに {section} の一覧に載っていません")
        for item in sorted(in_doc - in_code):
            problems.append(f"{label} {item} は {section} の一覧にあるのにコードにありません")

    heading_pages, heading_actions = heading_counts(doc)
    if heading_pages != len(pages_listed):
        problems.append(f"§10.4 の見出しの件数 {heading_pages} が一覧の件数 {len(pages_listed)} と違います")
    if heading_actions != len(actions_listed):
        problems.append(f"§10.5 の見出しの件数 {heading_actions} が一覧の件数 {len(actions_listed)} と違います")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=DEFAULT_ROOT, help="リポジトリのルート(既定: このスクリプトの親)")
    args = parser.parse_args(argv)

    problems = find_problems(args.root)
    if problems:
        print("3秒予算の対象一覧とコードが一致しません:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        print(f"一覧は {DOC} §10.4 / §10.5。行を足す・消す・見出しの件数を直す。", file=sys.stderr)
        return 1
    print("3秒予算の対象一覧とコードは一致しています")
    return 0


if __name__ == "__main__":
    sys.exit(main())
