#!/usr/bin/env python3
"""3秒予算の対象一覧とシナリオの照合(#1477、AC-PERF-004)。

`docs/ACCEPTANCE_CRITERIA.md` §10.4 / §10.5 の一覧と、`apps/web/e2e/features/**/*.feature` の
シナリオが宣言するタグを突き合わせ、次のどれかがあれば失敗(exit 1)する。

- 「予算対象」の行に、それを検証するシナリオが無い
  (受け入れシナリオから到達できない Server Action は例外: 一覧の名前の後ろに
  `(受け入れシナリオから到達できない: <理由>)` と注記する。理由が空、予算対象でない行への注記、
  注記したのにシナリオがある、はいずれも失敗)
- シナリオが、一覧に無いものを検証すると宣言している
- シナリオが、「予算対象」でない行(予算対象外 / 非同期ハンドオフ待ち)を検証すると宣言している

## シナリオの宣言(タグ)

検証対象はシナリオ(またはシナリオアウトライン)の直前のタグ行で宣言する:

    @budget-page:/projects/[id]/plan        §10.4 の画面。ルートは一覧の表記のまま
    @budget-action:updateProjectNameAction  §10.5 の Server Action。関数名

1つのシナリオが複数を宣言してよい。機能(`機能:`)・背景・`例:` の直前のタグは宣言として
数えず、置き間違いとして失敗にする(効かない宣言を黙って受け付けない)。

## 再試行タグ(#1554)

利用者の決定(2026-10-02)で、3秒予算のシナリオは再試行で通れば合格とする。再試行は
`@response-budget` を持つ feature だけに、feature 単位の `@retries:2` で付ける。付け忘れ、
値の違い、ほかの feature やシナリオ単位への `@retries:` はいずれも失敗にする。

## 使い方

    python3 scripts/check-response-budget-coverage.py                 # 既定: 画面と Server Action の両方
    python3 scripts/check-response-budget-coverage.py --pages-only    # 画面だけ(段階導入用)
    python3 scripts/check-response-budget-coverage.py --actions-only  # Server Action だけ

既定は全件を見る。`--pages-only` / `--actions-only` は、段階的に導入する間に片方だけを緑にするための
もので、最終的な検証は既定で行う。

終了コード: 0 一致 / 1 不一致 / 2 一覧を読めない。
"""

import argparse
import os
import re
import sys
from dataclasses import dataclass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from response_budget_list import BudgetRow, parse_budget_list  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
LIST_DOC = os.path.join("docs", "ACCEPTANCE_CRITERIA.md")
FEATURES_DIR = os.path.join("apps", "web", "e2e", "features")

_BUDGET_TAG = re.compile(r"^@budget-(page|action):(.+)$")

# Gherkin のキーワード(日本語・英語)。長いものを先に並べて前方一致を取る。
_SCENARIO = ("シナリオアウトライン", "シナリオテンプレート", "シナリオ", "Scenario Outline", "Scenario Template", "Scenario", "Example")
_OTHER = ("機能", "Feature", "背景", "Background", "ルール", "Rule", "例", "Examples", "Scenarios")


def _starts_with_keyword(line: str, keywords) -> bool:
    return any(line.startswith(k + ":") for k in keywords)


@dataclass(frozen=True)
class Declaration:
    """シナリオが宣言した検証対象。"""

    kind: str
    ident: str
    path: str
    line: int
    scenario: str


def scan_feature_text(text: str, path: str):
    """1つの `.feature` から宣言を集める。`(宣言のリスト, エラーメッセージのリスト)` を返す。"""
    declarations: list[Declaration] = []
    errors: list[str] = []
    pending: list[tuple[int, str, str]] = []  # (行番号, kind, ident)

    def misplaced(why: str) -> None:
        for lineno, kind, ident in pending:
            errors.append(f"{path}:{lineno}: @budget-{kind}:{ident} は{why}にあり、宣言として数えられません")
        pending.clear()

    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@"):
            for token in line.split():
                match = _BUDGET_TAG.match(token)
                if match:
                    pending.append((lineno, match.group(1), match.group(2)))
            continue
        if _starts_with_keyword(line, _SCENARIO):
            name = line.split(":", 1)[1].strip()
            for tag_line, kind, ident in pending:
                declarations.append(Declaration(kind, ident, path, tag_line, name))
            pending.clear()
        elif _starts_with_keyword(line, _OTHER):
            misplaced(f"シナリオ以外({line.split(':', 1)[0]})の直前")
        else:
            misplaced("ステップ行の直前(シナリオの直前ではない場所)")
    misplaced("ファイルの末尾")
    return declarations, errors


def collect_declarations(features_root: str, repo_root: str = REPO_ROOT):
    declarations: list[Declaration] = []
    errors: list[str] = []
    for directory, _dirs, files in sorted(os.walk(features_root)):
        for name in sorted(files):
            if not name.endswith(".feature"):
                continue
            full = os.path.join(directory, name)
            with open(full, encoding="utf-8") as fh:
                found, problems = scan_feature_text(fh.read(), os.path.relpath(full, repo_root))
            declarations += found
            errors += problems
    return declarations, errors


_RETRIES_TAG = re.compile(r"^@retries:(.*)$")
RETRIES_TAG = "@retries:2"
BUDGET_FEATURE_TAG = "@response-budget"


def check_retries_text(text: str, path: str) -> list[str]:
    """1つの `.feature` の再試行タグを検査し、問題の文言のリストを返す(空なら適合)。

    - `@response-budget` を feature 単位で持つのに `@retries:2` が無い
    - `@retries:` の値が 2 でない、または `@response-budget` の無い feature にある
    - シナリオ単位の `@retries:`(playwright-bdd では効かない置き場所)
    """
    feature_tags: list[str] = []
    scenario_retries: list[tuple[int, str]] = []
    pending: list[tuple[int, str]] = []
    seen_feature = False
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@"):
            pending += [(lineno, t) for t in line.split() if t.startswith("@")]
            continue
        if not seen_feature and _starts_with_keyword(line, ("機能", "Feature")):
            seen_feature = True
            feature_tags = [t for _, t in pending]
        else:
            scenario_retries += [(n, t) for n, t in pending if _RETRIES_TAG.match(t)]
        pending = []
    scenario_retries += [(n, t) for n, t in pending if _RETRIES_TAG.match(t)]

    problems: list[str] = []
    is_budget = BUDGET_FEATURE_TAG in feature_tags
    retries = [t for t in feature_tags if _RETRIES_TAG.match(t)]
    if is_budget and not retries:
        problems.append(f"{path}: {BUDGET_FEATURE_TAG} の feature に feature 単位の {RETRIES_TAG} がありません")
    if not is_budget and retries:
        problems.append(f"{path}: {BUDGET_FEATURE_TAG} を持たない feature に {', '.join(retries)} があります(再試行は予算シナリオだけ)")
    if is_budget:
        problems += [f"{path}: {t} は {RETRIES_TAG} ではありません(再試行は最大2回)" for t in retries if t != RETRIES_TAG]
    problems += [
        f"{path}:{n}: {t} はシナリオ単位にあります(再試行は feature 単位の {RETRIES_TAG} だけ)" for n, t in scenario_retries
    ]
    return problems


def collect_retries_problems(features_root: str, repo_root: str = REPO_ROOT) -> list[str]:
    problems: list[str] = []
    for directory, _dirs, files in sorted(os.walk(features_root)):
        for name in sorted(files):
            if name.endswith(".feature"):
                full = os.path.join(directory, name)
                with open(full, encoding="utf-8") as fh:
                    problems += check_retries_text(fh.read(), os.path.relpath(full, repo_root))
    return problems


_SCOPES = {"all": ("page", "action"), "pages": ("page",), "actions": ("action",)}
_LABEL = {"page": "画面", "action": "Server Action"}


def check(rows: list[BudgetRow], declarations: list[Declaration], scope: str = "all") -> list[str]:
    """一覧とシナリオの不一致を、人が読む文言のリストで返す(空なら一致)。"""
    kinds = _SCOPES[scope]
    listed = {(r.kind, r.ident): r for r in rows if r.kind in kinds}
    declared = {(d.kind, d.ident): d for d in sorted(declarations, key=lambda d: (d.path, d.line)) if d.kind in kinds}
    problems: list[str] = []

    for (kind, ident), row in sorted(listed.items()):
        if row.unreachable and not row.is_budget_target:
            problems.append(
                f"{_LABEL[kind]} {ident}: 到達できない注記があるのに予算対象ではありません"
                f"(一覧の分類は「{row.classification}」。注記は予算対象の行にだけ付ける)"
            )
        elif row.is_budget_target and not row.unreachable and (kind, ident) not in declared:
            problems.append(
                f"{_LABEL[kind]} {ident}: 予算対象なのに予算を検証するシナリオがありません "
                f"(@budget-{kind}:{ident} を付けたシナリオが要る)"
            )
    for (kind, ident), decl in sorted(declared.items()):
        where = f"{decl.path}:{decl.line}"
        row = listed.get((kind, ident))
        if row is not None and row.unreachable and row.is_budget_target:
            problems.append(
                f"{where}: {_LABEL[kind]} {ident} は一覧で「到達できない」と注記されていますが、"
                f"シナリオ「{decl.scenario}」が宣言しています(到達できるようになったなら注記を外す)"
            )
        elif row is None:
            problems.append(f"{where}: {_LABEL[kind]} {ident} は一覧にありません(シナリオ「{decl.scenario}」)")
        elif not row.is_budget_target:
            problems.append(
                f"{where}: {_LABEL[kind]} {ident} は予算対象ではありません"
                f"(一覧の分類は「{row.classification}」。シナリオ「{decl.scenario}」)"
            )
    return problems


def main(argv=None, repo_root: str = REPO_ROOT) -> int:
    parser = argparse.ArgumentParser(description="3秒予算の対象一覧とシナリオの照合")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--pages-only", action="store_const", dest="scope", const="pages", help="画面の初回表示だけを照合する")
    group.add_argument("--actions-only", action="store_const", dest="scope", const="actions", help="Server Action だけを照合する")
    parser.set_defaults(scope="all")
    args = parser.parse_args(argv)

    try:
        with open(os.path.join(repo_root, LIST_DOC), encoding="utf-8") as fh:
            rows = parse_budget_list(fh.read())
    except (OSError, ValueError) as err:
        print(f"対象一覧を読めません: {err}")
        return 2

    declarations, problems = collect_declarations(os.path.join(repo_root, FEATURES_DIR), repo_root)
    problems += check(rows, declarations, args.scope)
    problems += collect_retries_problems(os.path.join(repo_root, FEATURES_DIR), repo_root)
    if problems:
        print(f"3秒予算の対象一覧とシナリオが一致しません({len(problems)}件):")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    in_scope = [r for r in rows if r.is_budget_target and r.kind in _SCOPES[args.scope]]
    unreachable = sum(1 for r in in_scope if r.unreachable)
    print(
        f"OK: 予算対象 {len(in_scope)} 件のうち {len(in_scope) - unreachable} 件にシナリオがあり、"
        f"到達できない {unreachable} 件は一覧に理由があります。一覧にない宣言もありません(範囲: {args.scope})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
