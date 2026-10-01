"""`unittest.main()` より後にテストクラスを定義したファイルが無いことの検証(#1453)。

`if __name__ == "__main__": unittest.main()` がクラス定義より前にあると、直接実行
(`python3 test_x.py`)では後続のクラスが未定義のまま収集され、テストが静かに減る。
`discover` 経由では全件収集されるため、CI/リリース検証では気づけない。

検出ロジックは純関数 `classes_defined_after_main` に切り出し、インライン文字列で
red/green を確認する。リポジトリ走査はその薄い呼び出しである。
"""

import ast
import os
import subprocess
import unittest
import warnings

HOOKS_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(HOOKS_DIR))
SCAN_DIRS = [HOOKS_DIR, os.path.join(REPO_ROOT, "scripts")]


def _is_main_guard(node: ast.stmt) -> bool:
    if not isinstance(node, ast.If):
        return False
    test = node.test
    return (
        isinstance(test, ast.Compare)
        and isinstance(test.left, ast.Name)
        and test.left.id == "__name__"
        and len(test.comparators) == 1
        and isinstance(test.comparators[0], ast.Constant)
        and test.comparators[0].value == "__main__"
    )


def _calls_unittest_main(node: ast.If) -> bool:
    for stmt in node.body:
        for sub in ast.walk(stmt):
            if (
                isinstance(sub, ast.Call)
                and isinstance(sub.func, ast.Attribute)
                and sub.func.attr == "main"
                and isinstance(sub.func.value, ast.Name)
                and sub.func.value.id == "unittest"
            ):
                return True
    return False


def classes_defined_after_main(source: str) -> list[str]:
    """`unittest.main()` を呼ぶ main ガードより後(モジュール直下)のクラス名を返す。"""
    tree = ast.parse(source)
    found: list[str] = []
    seen_main = False
    for node in tree.body:
        if _is_main_guard(node) and _calls_unittest_main(node):
            seen_main = True
        elif seen_main and isinstance(node, ast.ClassDef):
            found.append(node.name)
    return found


VIOLATING = '''
import unittest

class A(unittest.TestCase):
    def test_a(self):
        pass

if __name__ == "__main__":
    unittest.main()

class B(unittest.TestCase):
    def test_b(self):
        pass

class C(unittest.TestCase):
    pass
'''

HEALTHY = '''
import unittest

class A(unittest.TestCase):
    pass

class B(unittest.TestCase):
    pass

if __name__ == "__main__":
    unittest.main()
'''

NO_MAIN = '''
import unittest

class A(unittest.TestCase):
    pass
'''

MAIN_WITHOUT_UNITTEST = '''
class A:
    pass

if __name__ == "__main__":
    print("hi")

class B:
    pass
'''

OTHER_GUARD = '''
if __name__ == "other":
    unittest.main()

class B:
    pass
'''


class ClassesDefinedAfterMain(unittest.TestCase):
    def test_violation_is_reported_by_class_name(self):
        self.assertEqual(classes_defined_after_main(VIOLATING), ["B", "C"])

    def test_healthy_source_returns_empty(self):
        self.assertEqual(classes_defined_after_main(HEALTHY), [])

    def test_source_without_main_returns_empty(self):
        self.assertEqual(classes_defined_after_main(NO_MAIN), [])

    def test_main_guard_not_calling_unittest_main_is_ignored(self):
        self.assertEqual(classes_defined_after_main(MAIN_WITHOUT_UNITTEST), [])

    def test_other_name_comparison_is_ignored(self):
        self.assertEqual(classes_defined_after_main(OTHER_GUARD), [])


class RepositoryTestFilesRunAllTestsWhenExecutedDirectly(unittest.TestCase):
    def test_no_class_is_defined_after_unittest_main(self):
        violations = []
        scanned = 0
        for directory in SCAN_DIRS:
            for name in sorted(os.listdir(directory)):
                if not (name.startswith("test_") and name.endswith(".py")):
                    continue
                path = os.path.join(directory, name)
                scanned += 1
                with open(path, encoding="utf-8") as f:
                    classes = classes_defined_after_main(f.read())
                for cls in classes:
                    violations.append(
                        f"{os.path.relpath(path, REPO_ROOT)}: class {cls} is defined "
                        "after unittest.main(); move the __main__ block to the end of the file"
                    )
        self.assertGreater(scanned, 0)
        self.assertEqual(violations, [], "\n" + "\n".join(violations))


def invalid_escape_warnings(source: str, filename: str) -> list[str]:
    """`source` をコンパイルして出る SyntaxWarning(無効なエスケープ等)のメッセージを返す。"""
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        ast.parse(source, filename=filename)
    return [
        f"{filename}:{w.lineno}: {w.message}"
        for w in caught
        if issubclass(w.category, SyntaxWarning)
    ]


class InvalidEscapeWarnings(unittest.TestCase):
    def test_invalid_escape_is_reported_with_file_and_line(self):
        found = invalid_escape_warnings('x = "\\."\n', "sample.py")
        self.assertEqual(len(found), 1)
        self.assertTrue(found[0].startswith("sample.py:1:"))

    def test_raw_string_is_clean(self):
        self.assertEqual(invalid_escape_warnings('x = r"\\."\n', "sample.py"), [])

    def test_no_tracked_python_file_has_an_invalid_escape(self):
        tracked = subprocess.run(
            ["git", "ls-files", "*.py"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.split()
        self.assertGreater(len(tracked), 0)
        violations = []
        for rel in tracked:
            with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
                violations.extend(invalid_escape_warnings(f.read(), rel))
        self.assertEqual(violations, [], "\n" + "\n".join(violations))


if __name__ == "__main__":
    unittest.main()
