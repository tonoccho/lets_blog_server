#!/usr/bin/env python3
"""このブランチで変更したプロダクションコードの分岐カバレッジ(C1/C2)を検査する。

`CLAUDE.md` → **Test-First Implementation** → Coverage の実行機構。基準は 90%。
対象は「このIssueで変更した本番コード」であり、リポジトリ全体ではない。

読むもの:
  - JVM      : services/<svc>/build/reports/jacoco/test/jacocoTestReport.xml の BRANCH counter
  - フロント : apps/web/coverage/coverage-final.json の branch map

JaCoCo の BRANCH は `&&` / `||` がバイトコード上で別分岐に落ちるため条件網羅(C2)を、
jest(v8) の branch map も同様にコンパイル後の分岐を数える。

使い方:
    python3 scripts/check-changed-coverage.py [--base origin/develop] [--threshold 90]

終了コード 0 = 基準を満たす / 1 = 満たさない・測定できない。
"""

import argparse
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".claude", "hooks"))
from paths import is_production  # noqa: E402

ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def run(args):
    out = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def changed_production_files(base):
    merge_base = run(["git", "merge-base", base, "HEAD"]) or base
    diff = run(["git", "diff", "--name-only", "--diff-filter=ACMR", merge_base, "HEAD"])
    if diff is None:
        return None
    files = [p for p in diff.splitlines() if p.strip()]
    return [p for p in files if is_production(p) and p.endswith((".java", ".kt", ".ts", ".tsx", ".js", ".jsx"))]


def jacoco_branches():
    """{リポジトリ相対パス: (missed, covered)} を JaCoCo XML から集める。"""
    result = {}
    services_dir = os.path.join(ROOT, "services")
    roots = []
    if os.path.isdir(services_dir):
        roots += [os.path.join("services", d) for d in os.listdir(services_dir)]
    packages_dir = os.path.join(ROOT, "packages")
    if os.path.isdir(packages_dir):
        roots += [os.path.join("packages", d) for d in os.listdir(packages_dir)]

    for module in roots:
        xml = os.path.join(ROOT, module, "build", "reports", "jacoco", "test", "jacocoTestReport.xml")
        if not os.path.exists(xml):
            continue
        try:
            tree = ET.parse(xml)
        except ET.ParseError:
            continue
        for package in tree.getroot().iter("package"):
            pkg = package.get("name", "")
            for sourcefile in package.findall("sourcefile"):
                name = sourcefile.get("name", "")
                lang = "kotlin" if name.endswith(".kt") else "java"
                rel = os.path.join(module, "src", "main", lang, pkg, name)
                counter = sourcefile.find("counter[@type='BRANCH']")
                missed = int(counter.get("missed", 0)) if counter is not None else 0
                covered = int(counter.get("covered", 0)) if counter is not None else 0
                result[rel] = (missed, covered)
    return result


def jest_branches():
    """{リポジトリ相対パス: (missed, covered)} を jest の coverage-final.json から集める。"""
    result = {}
    for app in ("apps/web", "apps/extension", "apps/mcp-server"):
        report = os.path.join(ROOT, app, "coverage", "coverage-final.json")
        if not os.path.exists(report):
            continue
        try:
            with open(report, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, json.JSONDecodeError):
            continue
        for abs_path, entry in data.items():
            try:
                rel = os.path.relpath(os.path.realpath(abs_path), ROOT)
            except ValueError:
                continue
            covered = missed = 0
            for counts in (entry.get("b") or {}).values():
                for hit in counts:
                    if hit > 0:
                        covered += 1
                    else:
                        missed += 1
            result[rel] = (missed, covered)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=os.environ.get("COVERAGE_BASE", "origin/develop"))
    parser.add_argument("--threshold", type=float, default=90.0)
    args = parser.parse_args()

    base = args.base
    if run(["git", "rev-parse", "--verify", base]) is None:
        fallback = base.split("/")[-1]
        if run(["git", "rev-parse", "--verify", fallback]) is None:
            print("基準ブランチ %s が見つかりません。カバレッジを判定できません。" % base)
            return 1
        base = fallback

    changed = changed_production_files(base)
    if changed is None:
        print("git diff に失敗しました。カバレッジを判定できません。")
        return 1
    if not changed:
        print("変更されたプロダクションコードはありません。カバレッジ判定をスキップします。")
        return 0

    coverage = {}
    coverage.update(jacoco_branches())
    coverage.update(jest_branches())

    missing, rows, total_missed, total_covered = [], [], 0, 0
    for path in changed:
        if path not in coverage:
            missing.append(path)
            continue
        missed, covered = coverage[path]
        if missed + covered == 0:
            continue  # 分岐を持たないファイル
        rows.append((path, covered, missed, 100.0 * covered / (missed + covered)))
        total_missed += missed
        total_covered += covered

    if missing:
        print("次の変更ファイルのカバレッジレポートが見つかりません:")
        for path in missing:
            print("  - %s" % path)
        print("\nテストをカバレッジ付きで実行してから再度確認してください:")
        print("  ./gradlew :services:<svc>:test jacocoTestReport")
        print("  cd apps/web && npm run test:coverage")
        return 1

    total = total_missed + total_covered
    rate = 100.0 * total_covered / total if total else 100.0

    print("変更したプロダクションコードの分岐カバレッジ (C1/C2, 基準 %.0f%%)" % args.threshold)
    print("-" * 72)
    for path, covered, missed, pct in sorted(rows, key=lambda r: r[3]):
        flag = " " if pct >= args.threshold else "!"
        print("%s %6.1f%%  %4d/%-4d  %s" % (flag, pct, covered, covered + missed, path))
    print("-" * 72)
    print("合計: %.1f%% (%d/%d branches)" % (rate, total_covered, total))

    if rate < args.threshold:
        print("\n基準 %.0f%% を下回っています。テストを追加してください。" % args.threshold)
        print("到達できない分岐がある場合は、どの分岐か・なぜ到達できないかを実装報告に明記すること。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
