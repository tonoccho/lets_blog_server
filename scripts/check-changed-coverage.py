#!/usr/bin/env python3
"""このブランチで変更したプロダクションコードの分岐カバレッジ(C1/C2)を検査する。

`CLAUDE.md` → **Test-First Implementation** → Coverage の実行機構。基準は 90%。
対象は「このIssueで変更した本番コード」であり、リポジトリ全体ではない。

集計はファイル単位ではなく、**このブランチが実際に変更・追加した行に対応する分岐だけ**
に絞る(#1230)。ファイルを触っただけで、そのファイルに残る無関係な既存コード
(レガシーハンドラ等)の分岐まで合算すると、変更した箇所は100%カバーでも
ファイル全体では基準を下回ることがある(#1063 で実際に発生)。`git diff` の hunk から
変更行番号を取り、JaCoCo は `<line>` 要素の `mb`/`cb`、jest は `branchMap` の
座標をその行番号でフィルタしてから合算する。

読むもの:
  - JVM      : services/<svc>/build/reports/jacoco/test/jacocoTestReport.xml の BRANCH counter
  - フロント : apps/web/coverage/coverage-final.json の branch map

JaCoCo の BRANCH は `&&` / `||` がバイトコード上で別分岐に落ちるため条件網羅(C2)を、
jest(v8) の branch map も同様にコンパイル後の分岐を数える。

使い方:
    python3 scripts/check-changed-coverage.py [--base origin/develop] [--threshold 90]

レポートを必須にするのは、ランナーが実際に走査するツリー(`MEASURABLE_PATTERNS`)に
限る。プロダクションコードではあるが計測手段が存在しない層は対象外として報告する(#988)。
計測可能なツリーの中にあっても、実行時コードを一切 emit しない型定義のみのモジュールは
計測エントリ自体が存在しえないため、別区分として報告したうえで免除する(#1116)。

終了コード 0 = 基準を満たす / 1 = 満たさない・計測可能なのにレポートが無い。

単体テスト:
    python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'
"""

import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".claude", "hooks"))
from paths import is_production  # noqa: E402

ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# カバレッジを計測できるツリー(#988)。
#
# `paths.is_production()` と、この一覧は**別の軸**である。前者は
# 「テストファーストとフェーズ分離の対象か」を決める分類器(#983 の利用者決定)であり、
# 後者は「jest / JaCoCo がそもそも実行して数えられるか」を決める。
# 両者を同一視していたために、プロダクションではあるが計測手段が存在しないファイルに
# レポートを要求してしまい、`gh pr create` が原理的に通らなくなっていた(#942・#935)。
#
# 計測できるのは、実際にランナーが走査するソースツリーだけである:
#   - JaCoCo   : services/**/src、packages/**/src(Gradle の test タスクが対象にする範囲)
#   - jest     : apps/*/src(例えば apps/extension/jest.config.js は
#                `roots: ['<rootDir>/src']` と `collectCoverageFrom: ['src/**/*.ts', ...]` で、
#                src/ の外を最初から計測対象外にしている)
#
# この一覧の外にあるプロダクションコード — apps/*/webviews/ の素の .js、
# compose 上の Node プロセスとして動くだけの infra/e2e-stubs/**、
# src/ の外の next.config.ts — には、そもそもユニットテストのランナーが無い。
# これらは受け入れテストのレイヤー(Gherkin シナリオ + docker-compose)が検証しており、
# 「ユニットテストで到達できない層には数値目標を置かず受け入れテストに委ねる」という
# 既存の規約(docs/COVERAGE_TARGETS.md の extension.ts・各 Panel 生成部の扱い)と同じ
# 位置づけになる。したがってレポート必須の対象から外す。
#
# 免除するのはカバレッジゲートだけであることに注意。これらのファイルは引き続き
# `paths.is_production()` でプロダクションと分類され、フェーズ分離とテストファースト
# (先に落ちる受け入れシナリオを書くこと)は従来どおり要求される。
MEASURABLE_PATTERNS = [
    r"^services/[^/]+/src/",
    r"^packages/[^/]+/src/",
    r"^apps/[^/]+/src/",
]

MEASURABLE_RE = [re.compile(p) for p in MEASURABLE_PATTERNS]

# `packages/**/src` は Gradle サブプロジェクト(JaCoCo が走査する範囲)を想定した
# パターンだが、`packages/api-client`(`@lets-blog/api-client`、orval が生成する
# TypeScript クライアント)も字面上は同じパターンにマッチしてしまう(#1228)。
# このツリーには Gradle も jest も配線されておらず(`package.json` の scripts は
# `generate` / `typecheck` のみ)、原理的にレポートが出ない。
#
# `MEASURABLE_PATTERNS` 自体を「Gradle サブプロジェクトの一覧」に絞ると、将来
# packages/ 配下に増える非JVMパッケージのたびに同じ穴が再発する。そこで
# 「計測可能パターンにマッチするが実際には計測手段が無い」ツリーを明示的な
# 例外として持ち、`is_measurable()` の判定でそれを優先する。
#
# `apps/extension/src/extension.ts`(コマンド登録)・各 `apps/*/src/**/*Panel.ts` の
# 生成部・`apps/*/src/**/*CompletionProvider.ts` は VSCode 拡張ホストに依存する層で、
# `docs/COVERAGE_TARGETS.md`(「VSCode拡張ホストに依存する層」の表)がそもそも
# 数値目標を置かないと明記している。jest はいずれの変更でも拡張ホスト(VSCode API)を
# 起動できないため到達できず、原理的に 0% のまま基準を満たせない(#1206 で実際に
# `glab mr create` が常にブロックされた、#1272)。
#
# 粒度はファイル単位(#1230 が進める変更行単位への一般化とは独立)。`.tsx` は対象外 —
# `apps/web/src/app/**/*Panel.tsx` のように jest で実測される Panel コンポーネントが
# 多数あり、拡張子を `\.ts$` で厳密に区切らないとそれらまで誤って免除してしまう。
UNMEASURABLE_OVERRIDE_PATTERNS = [
    r"^packages/api-client/",
    r"^apps/extension/src/extension\.ts$",
    r"^apps/[^/]+/src/.*Panel\.ts$",
    r"^apps/[^/]+/src/.*CompletionProvider\.ts$",
]

UNMEASURABLE_OVERRIDE_RE = [re.compile(p) for p in UNMEASURABLE_OVERRIDE_PATTERNS]


def is_measurable(path):
    """カバレッジレポートが出る仕組みのあるツリーなら True。

    プロダクションコードかどうかは判定しない(それは `paths.is_production()` の役割)。
    """
    if any(r.search(path) for r in UNMEASURABLE_OVERRIDE_RE):
        return False
    return any(r.search(path) for r in MEASURABLE_RE)


# 型定義のみのモジュールの判定(#1116)。
#
# `apps/*/src/` には、実行時コードを一切生成しない TypeScript モジュールがある
# (`export type` / `export interface` だけのファイル)。tsc が出力する JS が空になるため
# istanbul は計測エントリを作らず、`coverage-final.json` にキーが現れない。
# これを「テストをカバレッジ付きで回し忘れた」ケースと同じ失敗にすると、型を1行足しただけで
# Merge Request が開けなくなる(#1104 で実際に発生した)。
#
# 判定はソースの正規表現による当て推量ではなく、**TypeScript 自身に emit させて**
# 出力が空であることを確かめる。istanbul が計測するのはまさにその emit 結果なので、
# 「計測エントリが存在しえない」ことの直接の根拠になる。
#
# `ts.transpileModule` は単一ファイルの構文変換(`isolatedModules` 相当)で、型情報を使わない。
# そのぶん**保守的側に外れる**という性質がある:
#   - 値として使われうる import は残す(副作用 import `import './polyfill';` は消えない)
#   - enum / const enum は実体を出力する
#   - 値と型が同名で衝突するような曖昧な場合は値として扱う
# つまり誤判定は「実行時コードがある」= レポートを要求する側へ倒れる。
# 判定できないとき(node が無い、typescript を解決できない、ファイルが読めない、
# `.ts` / `.tsx` ではない)も同様に型のみとはみなさず、従来どおりレポートを要求する。
#
# 宣言ファイル(`.d.ts` / `.d.mts` / `.d.cts`)だけは emit 結果を見ない。tsc は宣言ファイルから
# JS を1バイトも出力しないので、istanbul の計測エントリは定義上存在しえない。
# しかも `ts.transpileModule` に `x.d.ts` という `fileName` をそのまま渡すと TypeScript が
# `Debug Failure. Output generation failed` を投げるため、emit 結果で判定しようとすると
# **すべての宣言ファイルが内容に関係なく免除されない**(実在する
# `apps/web/src/types/next-auth.d.ts` がこれに当たった)。
# そこで宣言ファイルは `fileName` から `.d` を落として TypeScript にパースさせ、
# **構文エラーが1件も無いこと**を確認したうえで型のみとみなす。拡張子だけで通すのではなく、
# 読めること・パースできることを実際に確かめるので、抜け穴にはならない。
#
# 起動コストは node + typescript のロードが支配的で、実測 0.15 秒(1ファイル)〜
# 0.25 秒(20ファイルまとめて、1回の node 起動で処理)。しかもこの判定は
# **レポートが見つからなかったファイルがある場合にしか**走らないので、
# ゲートの常用経路(全ファイルにレポートがある)には一切コストがかからない。
# `.d.mts` / `.d.cts` は `.ts` で終わらないため個別に挙げる。
TYPE_ONLY_EXTENSIONS = (".ts", ".tsx", ".d.mts", ".d.cts")

# 判定を諦めるまでの秒数。`guard.py` の `check_pr_coverage()` はこのスクリプト全体を
# 120 秒で打ち切るので、**必ずそれより先に**諦める必要がある。外側が先に切れると
# `TimeoutExpired` がフックまで抜け、「判定できなかった(=レポートを要求する)」という
# 通常の失敗ではなく例外で終わってしまう。
TYPE_ONLY_TIMEOUT_SECONDS = 30

# emit 結果が「空」とみなせる形。ESNext モジュールとして emit すると、
# 実行時の中身が無いモジュールは空文字か `export {};` だけになる。
TYPE_ONLY_EMIT = ("", "export{};")

# stdin から {root, files, resolveDirs, emptyEmits} を受け取り、実行時コードを emit しない
# ファイルの一覧を JSON で返す。typescript はリポジトリ内の node_modules から解決する。
TYPE_ONLY_HELPER_JS = r"""
const fs = require('fs');
const path = require('path');
let input;
try {
  input = JSON.parse(fs.readFileSync(0, 'utf8'));
} catch (e) {
  process.exit(2);
}
const root = input.root;
const dirs = [];
for (const f of input.files) dirs.push(path.dirname(path.resolve(root, f)));
for (const d of input.resolveDirs || []) dirs.push(d);
let ts = null;
for (const d of dirs) {
  try {
    ts = require(require.resolve('typescript', { paths: [d] }));
    break;
  } catch (e) {
    /* 次の候補を試す */
  }
}
if (!ts) process.exit(3);
const compilerOptions = {
  target: ts.ScriptTarget.ES2020,
  module: ts.ModuleKind.ESNext,
  jsx: ts.JsxEmit.React,
  removeComments: true,
  isolatedModules: true,
};
const DECLARATION = /\.d\.(ts|mts|cts)$/;
const typeOnly = [];
for (const f of input.files) {
  let source;
  try {
    source = fs.readFileSync(path.resolve(root, f), 'utf8');
  } catch (e) {
    continue; // 読めないファイルは型のみとみなさない(厳しい側へ倒す)
  }
  const base = path.basename(f);
  const isDeclaration = DECLARATION.test(base);
  // 宣言ファイルの fileName をそのまま渡すと transpileModule が Debug Failure を投げるので、
  // `.d` を落とした名前でパースさせる。ここでの関心は emit ではなく「パースできるか」。
  const fileName = isDeclaration ? base.replace(DECLARATION, '.$1') : base;
  let result;
  try {
    result = ts.transpileModule(source, {
      compilerOptions,
      fileName,
      reportDiagnostics: isDeclaration,
    });
  } catch (e) {
    continue; // 変換できないファイルも型のみとみなさない
  }
  if (isDeclaration) {
    // 宣言ファイルは tsc が JS を出力しないので、パースできた時点で計測エントリは存在しえない。
    if ((result.diagnostics || []).length === 0) typeOnly.push(f);
    continue;
  }
  const stripped = result.outputText.replace(/\s+/g, '');
  if (input.emptyEmits.indexOf(stripped) !== -1) typeOnly.push(f);
}
process.stdout.write(JSON.stringify(typeOnly));
"""


def typescript_resolve_dirs(root):
    """typescript を探す候補ディレクトリ。リポジトリ内の各アプリと root を見る。"""
    dirs = [ROOT, root]
    apps = os.path.join(ROOT, "apps")
    if os.path.isdir(apps):
        dirs += [os.path.join(apps, d) for d in sorted(os.listdir(apps))]
    return [d for d in dirs if os.path.isdir(d)]


def emits_no_runtime_code(paths, root=ROOT):
    """`paths` のうち、TypeScript が実行時コードを一切 emit しないファイルの集合を返す。

    判定できない場合は空集合寄り(=型のみとみなさない)に倒す。理由は上のコメント。
    """
    candidates = [p for p in paths if p.endswith(TYPE_ONLY_EXTENSIONS)]
    if not candidates:
        return set()
    payload = json.dumps(
        {
            "root": root,
            "files": candidates,
            "resolveDirs": typescript_resolve_dirs(root),
            "emptyEmits": list(TYPE_ONLY_EMIT),
        }
    )
    try:
        proc = subprocess.run(
            ["node", "-e", TYPE_ONLY_HELPER_JS],
            input=payload,
            capture_output=True,
            text=True,
            timeout=TYPE_ONLY_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError):
        return set()  # node が無い・起動できない・時間切れ
    if proc.returncode != 0:
        return set()  # typescript を解決できない等
    try:
        result = json.loads(proc.stdout)
    except (TypeError, ValueError):
        return set()
    if not isinstance(result, list):
        return set()
    allowed = set(candidates)
    return {p for p in result if p in allowed}


def run(args):
    out = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def changed_production_files(base):
    merge_base = run(["git", "merge-base", base, "HEAD"]) or base
    diff = run(["git", "diff", "--name-only", "--diff-filter=ACMR", merge_base, "HEAD"])
    if diff is None:
        return None
    files = [p for p in diff.splitlines() if p.strip()]
    return [p for p in files if is_production(p)]


def changed_lines_by_file(base):
    """{リポジトリ相対パス: 変更・追加された行番号の集合} を `git diff` の hunk から得る。

    削除だけの行はカバレッジと無関係なので含めない(要件1)。hunk ヘッダ
    `@@ -a[,b] +c[,d] @@` の `+c[,d]` 側だけを見る。`d` を省略した1行の hunk は
    `d=1` と同義(unified diff の記法)。`d=0` は追加行を持たない純粋な削除hunk
    なので、行番号を採らずスキップする。
    """
    merge_base = run(["git", "merge-base", base, "HEAD"]) or base
    diff = run(["git", "diff", "-U0", "--diff-filter=ACMR", merge_base, "HEAD"])
    if diff is None:
        return {}
    result = {}
    current = None
    hunk_re = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line[4:].strip()
            if path == "/dev/null":
                current = None
                continue
            if path.startswith("b/"):
                path = path[2:]
            current = path
            result.setdefault(current, set())
            continue
        if current is None:
            continue
        m = hunk_re.match(line)
        if not m:
            continue
        start = int(m.group(1))
        count = int(m.group(2)) if m.group(2) is not None else 1
        if count == 0:
            continue  # 追加行を持たない純粋な削除hunk
        result[current].update(range(start, start + count))
    return result


def jacoco_branches(changed_lines):
    """{リポジトリ相対パス: (missed, covered)} を JaCoCo XML から、変更行に絞って集める。

    ファイル単位の `counter[@type='BRANCH']`(集計済みの合計)ではなく、`<line>`
    要素ごとの `mb`(missed branches)/`cb`(covered branches)を、変更行の集合に
    含まれる `nr` のものだけ合算する(要件2)。`changed_lines` に無いファイルや、
    変更行に分岐が無いファイルは (0, 0) を返す — 呼び出し側がそれを
    「対象の分岐がない」として除外する。
    """
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
                lines = changed_lines.get(rel) or set()
                missed = covered = 0
                for line_el in sourcefile.findall("line"):
                    nr = line_el.get("nr")
                    if nr is None:
                        continue
                    try:
                        nr_int = int(nr)
                    except ValueError:
                        continue
                    if nr_int not in lines:
                        continue
                    missed += int(line_el.get("mb", 0))
                    covered += int(line_el.get("cb", 0))
                result[rel] = (missed, covered)
    return result


def _branch_location_overlaps(loc, lines):
    """jest の branchMap の座標(`{start:{line}, end:{line}}`)が変更行と重なるか。"""
    if not loc:
        return False
    start = (loc.get("start") or {}).get("line")
    if start is None:
        return False
    end = (loc.get("end") or {}).get("line")
    if end is None:
        end = start
    if end < start:
        start, end = end, start
    return any(n in lines for n in range(start, end + 1))


def jest_branches(changed_lines):
    """{リポジトリ相対パス: (missed, covered)} を jest の coverage-final.json から、
    変更行に絞って集める。

    `branchMap` の各分岐(`locations`、無ければ `loc`)の座標が変更行の集合と
    重なるものだけを分子・分母に含める(要件2)。
    """
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
            lines = changed_lines.get(rel) or set()
            branch_map = entry.get("branchMap") or {}
            covered = missed = 0
            for branch_id, counts in (entry.get("b") or {}).items():
                meta = branch_map.get(branch_id) or {}
                locations = meta.get("locations") or ([meta["loc"]] if meta.get("loc") else [])
                for i, hit in enumerate(counts):
                    loc = locations[i] if i < len(locations) else (locations[-1] if locations else None)
                    if not _branch_location_overlaps(loc, lines):
                        continue
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

    # 計測手段が無いツリーはゲートの対象外にする(理由は MEASURABLE_PATTERNS のコメント)。
    # 黙って捨てず、何を対象外にしたかを必ず出力する。
    unmeasurable = [p for p in changed if not is_measurable(p)]
    changed = [p for p in changed if is_measurable(p)]
    if unmeasurable:
        print("次の変更ファイルはカバレッジ計測対象外です(受け入れテストで検証する層):")
        for path in unmeasurable:
            print("  - %s" % path)
        print("")
    if not changed:
        print("計測可能なプロダクションコードの変更はありません。カバレッジ判定をスキップします。")
        return 0

    changed_lines_map = changed_lines_by_file(base)
    coverage = {}
    coverage.update(jacoco_branches(changed_lines_map))
    coverage.update(jest_branches(changed_lines_map))

    missing, rows, total_missed, total_covered = [], [], 0, 0
    for path in changed:
        if path not in coverage:
            missing.append(path)
            continue
        missed, covered = coverage[path]
        if missed + covered == 0:
            continue  # 変更行に分岐が無いファイル(#1230: ファイル全体ではなく変更行のみを見る)
        rows.append((path, covered, missed, 100.0 * covered / (missed + covered)))
        total_missed += missed
        total_covered += covered

    # レポートが無いファイルのうち、実行時コードを emit しないものを切り分ける(#1116)。
    # 「計測すべき分岐が存在しない」ことと「テストを回し忘れた」ことは別の事象であり、
    # 前者だけを免除する。実行時コードを1行でも持つファイルは従来どおり落ちる。
    if missing:
        type_only = emits_no_runtime_code(missing)
        missing = [p for p in missing if p not in type_only]
        if type_only:
            # `apps/*/webviews/` のような「計測できないが受け入れテストで検証する層」とは
            # 別の区分として出す。型のみのモジュールには検証すべき実行時の振る舞いが無い。
            print("次の変更ファイルは実行時コードを持たない型定義のみのモジュールです(検証すべき分岐がありません):")
            for path in sorted(type_only):
                print("  - %s" % path)
            print("")

    if missing:
        print("次の変更ファイルのカバレッジレポートが見つかりません:")
        for path in missing:
            print("  - %s" % path)
        print("\nテストをカバレッジ付きで実行してから再度確認してください:")
        print("  ./gradlew :services:<svc>:test jacocoTestReport")
        print("  cd apps/web && npm run test:coverage")
        return 1

    if not rows:
        print("分岐を持つ変更はありませんでした。カバレッジ判定をスキップします。")
        return 0

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
