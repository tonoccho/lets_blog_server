"""リポジトリ内パスを「テストコード / プロダクションコード / 中立」に分類する。

`CLAUDE.md` → **Test-First Implementation** のフェーズ分離を機械的に判定するための
唯一の分類器。`.claude/hooks/guard.py`(Claude Code フック)と
`scripts/git-hooks/pre-commit`(git フック)の両方がこれを import する。
分類規則を他所に書き写さないこと。

分類の基準は「製品の振る舞いを決めるか」であって、「`src/` 配下にあるか」ではない(#983)。
nginx の設定も、Keycloak のレルム定義も、compose のポート公開も、拡張の Webview も、
利用者が観測する振る舞いを直接決めるので**プロダクションコード**として扱う。
その帰結として、これらの変更にも「先に落ちる受け入れシナリオを書く」ことが要求される。
これは意図した厳しさである(#983 の利用者決定)。

中立とは「テストでもプロダクションでもない」ではなく、**意図して分類の外に置いたもの**である。
その範囲は下の `NEUTRAL_PATTERNS` が唯一の列挙であり、`is_declared_neutral()` で参照できる。
黙って中立に落ちるファイルが出ていないことは `test_paths.py` が検証する。

    python3 -m unittest discover -s .claude/hooks -t .claude/hooks -p 'test_*.py'
"""

import re

# テストコード。JVM の src/test・src/testFixtures、Gherkin の .feature、
# 各アプリの e2e 一式(apps/web は playwright-bdd、apps/extension は jest 上の
# Gherkin ランナー。#942)、jest の *.test.* / *.spec.* を含む。
#
# テストランナーの設定・セットアップ(jest.config / jest.setup / playwright.config)も
# テストコード側に置く。これらはテストの回り方だけを決め、出荷物の振る舞いには影響しない。
# プロダクション扱いにすると「テストの直し」がプロダクションフェーズに化けてしまう。
TEST_PATTERNS = [
    r"(^|/)src/test/",
    r"(^|/)src/testFixtures/",
    r"\.feature$",
    r"^apps/[^/]+/e2e/",
    r"\.test\.[jt]sx?$",
    r"\.spec\.[jt]sx?$",
    r"(^|/)__tests__/",
    r"(^|/)__mocks__/",
    r"(^|/)jest\.config\.[cm]?[jt]s$",
    r"(^|/)jest\.setup\.[cm]?[jt]sx?$",
    r"(^|/)playwright\.config\.[cm]?[jt]s$",
    # フック自身の Python 単体テスト(.claude/hooks/test_*.py)。
    # `.claude/` は中立だが、その中のテストはテストコードとして扱う。
    r"(^|/)test_[^/]+\.py$",
]

# 場所によらず常に中立にするもの。プロダクション扱いのディレクトリ配下にも
# ドキュメントは置かれる(例: infra/keycloak/README.md、webviews/vendor/prism/LICENSE)。
# 文書は製品の振る舞いを決めないので、ディレクトリ規則より優先して中立にする。
DOC_PATTERNS = [
    r"\.md$",
    r"(^|/)LICENSE$",
]

# プロダクションコード。上のテスト条件・ドキュメント条件に当たらず、
# 製品の振る舞いを決めるファイル。各グループの採否と理由:
#
#   apps|services|packages/*/src/
#       実装ソースツリー。従来からのプロダクション判定。
#   apps/*/webviews/
#       【採用】VSCode 拡張の Webview 実装(diagramGallery.js ほか)。
#       利用者が操作する UI そのもので、プロダクションでない根拠が無い(#983 利用者決定)。
#   apps/*/messages/
#       【採用】next-intl のメッセージカタログ。画面に出る文言そのもので、
#       受け入れシナリオはこの文言を検証する。
#   apps/*/<name>.html|.css、apps/*/manifest.json
#       【採用】src/ の外に置かれた出荷 UI シェルとプラグインマニフェスト
#       (apps/penpot-plugin/ui.html・styles.css・manifest.json)。webviews と同じ理由。
#   apps/*/{next,postcss,tailwind}.config.*
#       【採用】実行時とビルド出力を決める設定。next.config.ts は bodySizeLimit や
#       proxyClientMaxBodySize のような、利用者が観測できる制約を持つ。
#       (jest.config / jest.setup / playwright.config はテスト設定なので TEST_PATTERNS 側。)
#   infra/
#       【採用】nginx のルーティング、Keycloak のレルム定義、MySQL の初期化、
#       WordPress のプロビジョニング、e2e-stubs。いずれも動く系の振る舞いを決める。
#       e2e-stubs は検証用の環境だが、環境定義であることに変わりはない(#983 利用者決定)。
#   docker-compose*.yml(リポジトリ直下に限らず、入れ子の位置でも)
#       【採用】コンテナ構成・環境変数・公開ポート。host-tests / e2e-stubs 用の
#       compose も、環境定義という点で同じ扱い(#983 利用者決定)。
#       入れ子(scripts/keycloak-clean-boot/ など)も一律プロダクション(#1292)。
#       scripts/ が中立なのは道具立てのシェルなどの話で、compose は置き場所に
#       関わらず環境定義である。ルート直下にしか一致しないと、置いた場所が他の
#       パターンに当たるかで偶然決まってしまう。Dockerfile と同じく (^|/) で判定する。
#   Dockerfile / Dockerfile.*
#       【採用】実行時イメージそのものの定義。compose をプロダクションとしながら
#       compose が組み立てるイメージ定義を中立に残すのは一貫しない。
#   apps/*/docker-entrypoint.sh
#       【採用】Dockerfile の ENTRYPOINT/CMD から起動される apps/*/ 直下のスクリプト
#       (#1208)。npm ci を実行するかどうか、生成物の所有権をどうするかという、
#       実行時イメージの振る舞いそのものを決める。Dockerfile をプロダクション扱い
#       としながら、その ENTRYPOINT が呼ぶスクリプトだけ TEST/DOC/NEUTRAL のどれにも
#       当たらず黙って中立に落ちるのは一貫しない。
#   apps/*/setup.sh
#       【採用】Zip で配る導入スクリプト(apps/penpot-plugin/setup.sh・apps/mcp-server/setup.sh、
#       #1491)。利用者の手元でビルドを走らせる出荷物そのもので、Node のバージョン検査や
#       .env の用意といった利用者が観測する振る舞いを決める。リポジトリ直下の setup.sh
#       (中立、#1321)は利用者がホストで動かす運用スクリプトという別物。直下のものを
#       巻き込まないよう `^apps/[^/]+/setup\.sh$` と個別に指定する。
PRODUCTION_PATTERNS = [
    r"^apps/[^/]+/src/",
    r"^services/[^/]+/src/",
    r"^packages/[^/]+/src/",
    r"^apps/[^/]+/webviews/",
    r"^apps/[^/]+/messages/",
    r"^apps/[^/]+/[^/]+\.(html|css)$",
    r"^apps/[^/]+/manifest\.json$",
    r"^apps/[^/]+/(next|postcss|tailwind)\.config\.[cm]?[jt]s$",
    r"^infra/",
    r"(^|/)docker-compose(\.[^/]+)?\.ya?ml$",
    r"(^|/)Dockerfile(\.[^/]+)?$",
    r"^apps/[^/]+/docker-entrypoint\.sh$",
    r"^apps/[^/]+/setup\.sh$",
]

# 中立。テストと同じコミットに入れてもフェーズ分離違反にならず、
# テストファースト(先行するテスト変更)も要求されない。
# この列挙は分類そのものには使わない(分類は TEST / DOC / PRODUCTION で決まる)。
# 「意図して中立に置いた」ことの宣言であり、取りこぼしとの区別を可能にするためにある。
# 各グループの理由:
#
#   .claude/
#       ルールと執行機構そのもの。ルールを書き換えるコミットをブロックしないための
#       意図的な据え置き(#983 利用者決定)。
#   docs/・*.md・LICENSE・README
#       文書。製品の振る舞いを決めない。
#   scripts/・.gitlab/・.gitlab-ci.yml・config/
#       開発とCIのための道具立て。出荷物には入らない。
#       `.gitlab/` は Issue / MR テンプレートの置き場、`.gitlab-ci.yml` はパイプライン定義
#       (#1022)。`.github/` は #1027 で削除した。宣言も併せて外してある —
#       残しておくと、再び置かれたときに「意図して中立にした」ものとして黙って通る。
#   package.json / package-lock.json / build.gradle / settings.gradle / gradle*
#       依存マニフェストとビルド定義。テストとプロダクションの両方が同じファイルを共有するため、
#       プロダクション扱いにすると「テスト専用の依存を足すテストフェーズのコミット」が
#       フェーズ分離違反になり、通常の作業が成立しなくなる。加えて Dependabot による
#       バージョン更新には、先に書くべき振る舞いの変更が無い。
#       依存の妥当性はレビューと脆弱性スキャンで担保する。
#   tsconfig*.json / eslint.config.* / next-env.d.ts
#       型チェックと lint の設定。開発時の道具であって出荷物の振る舞いではない。
#   openapi/*.json
#       生成物。scripts/generate-api-client.sh が起動中のサービスから取得する。
#       元になる振る舞いは services/*/src/ 側で検証される。
#   apps/*/public/
#       静的アセット(svg など)。振る舞いを持たない。
#   .gitignore / .dockerignore / .vscodeignore / .env*.example / .node-version / .vscode/
#       リポジトリと開発環境のメタデータ。名前を一つずつ挙げる。
#       「名前が . で始まる」は中立の根拠にならない。dotfile が増えたときに
#       黙って中立へ落ちるのを防ぐため、ワイルドカード(\.[^/]+$)は置かない。
#       新しい dotfile はここに足す=そのとき分類を決める、という運用にする。
#   setup.sh(リポジトリ直下、個別指定。#1321)
#       利用者がホストで直接実行する導入スクリプト(#960)。処理は既に中立の
#       scripts/generate-certs.sh・check-env.sh・wait-for-stack-healthy.sh へ
#       委譲しており、どの Dockerfile / docker-compose*.yml の ENTRYPOINT/CMD
#       からも呼ばれない(grep で確認済み)。#1208 が apps/*/docker-entrypoint.sh
#       をプロダクション扱いにした根拠(実行時イメージの ENTRYPOINT として
#       出荷物の振る舞いを決める)は当てはまらず、`^scripts/` を中立とする根拠
#       (開発・運用の道具で出荷物に入らない)に当てはまる。ワイルドカードでは
#       なく `^setup\.sh$` の個別指定にするのは、直下の他のファイルを
#       まとめて中立化しないため。
#   update.sh(リポジトリ直下、個別指定。#1452。#1321 の3回目の再発)
#       setup.sh と同型: 利用者がホストで直接実行する運用スクリプト(#962)で、
#       update.sh:119 で既に中立の scripts/wait-for-stack-healthy.sh へ委譲して
#       いる。以下のいずれの Dockerfile / docker-compose*.yml の ENTRYPOINT/CMD
#       からも呼ばれない(2026-09-27 実測):
#         grep -rn "update\.sh" --include=Dockerfile* --include=docker-compose*.yml \
#           --include=*.sh --include=*.yml --include=*.yaml .
#         → update.sh 自身の usage 文と BASH_SOURCE ガード(L5-7, L21, L34)のみ
#         grep -rn -E "ENTRYPOINT|CMD" --include=Dockerfile* . | grep -i update
#         → 0件
#       よって #1208 の根拠は当てはまらず、`^scripts/` を中立とする根拠に当てはまる。
#       ワイルドカードにせず個別指定にする理由は setup.sh と同じ。
#   startup.sh(リポジトリ直下、個別指定。#961)
#       setup.sh / update.sh と同型: 利用者がホストで直接実行する運用スクリプトで、
#       中立の scripts/wait-for-stack-healthy.sh へ委譲する。Dockerfile /
#       docker-compose*.yml の ENTRYPOINT/CMD からは呼ばれない。
#   shutdown.sh(リポジトリ直下、個別指定。#1528)
#       startup.sh と同型: 利用者がホストで直接実行する運用スクリプトで、docker compose を
#       呼ぶだけ。Dockerfile / docker-compose*.yml の ENTRYPOINT/CMD からは呼ばれない。
#
#       代償: 直下にスクリプトが増えるたびに、この個別指定のリストへ追記しない限り
#       「黙って中立」に落ち、develop 上で test_every_tracked_file_is_classified が
#       事後的に落ちる(#1208 → #1321 → #1452 と3回発生)。この代償を merge 前の
#       時点で止める層が scripts/git-hooks/pre-commit の check_unclassified()
#       (#1452)であり、ステージされた未分類パスをコミット時点で拒否する。
NEUTRAL_PATTERNS = [
    r"^\.claude/",
    r"^docs/",
    r"^scripts/",
    r"^\.gitlab/",
    r"^\.gitlab-ci\.yml$",
    r"^config/",
    r"\.md$",
    r"(^|/)LICENSE$",
    r"(^|/)build\.gradle$",
    r"^settings\.gradle$",
    r"^gradle/",
    r"^gradlew(\.bat)?$",
    r"(^|/)package(-lock)?\.json$",
    r"(^|/)tsconfig[^/]*\.json$",
    r"(^|/)eslint\.config\.[cm]?[jt]s$",
    r"(^|/)next-env\.d\.ts$",
    r"^openapi/[^/]+\.json$",
    r"^apps/[^/]+/public/",
    r"(^|/)\.vscode/",
    r"(^|/)\.gitignore$",
    r"(^|/)\.dockerignore$",
    r"(^|/)\.vscodeignore$",
    r"(^|/)\.env\.example$",
    r"(^|/)\.env\.local\.example$",
    r"(^|/)\.node-version$",
    r"^setup\.sh$",
    r"^update\.sh$",
    r"^startup\.sh$",
    r"^shutdown\.sh$",
]

# Claude Code のサブエージェントが作る git worktree(#1036)。
# `.claude/worktrees/agent-<id>/` の下にリポジトリ全体のコピーが置かれる。
#
# 接頭辞を**剥がして**中身のパスとして分類する。除外(どの分類にも入れない)ではなく
# こちらを選ぶのは、万一 worktree 内のファイルがステージされたときに、本来のガード
# (フェーズ分離・テストファースト・テスト無効化の検査)が正しく働くようにするため。
#
# 剥がさないと `NEUTRAL_PATTERNS` の `^\.claude/` が先にマッチし、worktree 内の
# **あらゆるファイルが中立**になる。`.claude/` を中立にしているのはルールと執行機構
# そのものを守るためであって(#983)、worktree はその意図の対象外である。
WORKTREE_PREFIX = re.compile(r"^\.claude/worktrees/[^/]+/")


def strip_worktree(path: str) -> str:
    """エージェント worktree の接頭辞を剥がす。worktree でなければそのまま返す。"""
    return WORKTREE_PREFIX.sub("", path)


TEST_RE = [re.compile(p) for p in TEST_PATTERNS]
DOC_RE = [re.compile(p) for p in DOC_PATTERNS]
PRODUCTION_RE = [re.compile(p) for p in PRODUCTION_PATTERNS]
NEUTRAL_RE = [re.compile(p) for p in NEUTRAL_PATTERNS]


def is_test(path: str) -> bool:
    path = strip_worktree(path)
    return any(r.search(path) for r in TEST_RE)


def is_production(path: str) -> bool:
    path = strip_worktree(path)
    if is_test(path):
        return False
    if any(r.search(path) for r in DOC_RE):
        return False
    return any(r.search(path) for r in PRODUCTION_RE)


def is_declared_neutral(path: str) -> bool:
    """中立として意図的に列挙されているパスなら True。

    分類には使わない。「中立に落ちたファイルが、列挙された意図の結果か、
    それとも規則の取りこぼしか」を単体テストが区別するためにある。
    """
    path = strip_worktree(path)
    if is_test(path) or is_production(path):
        return False
    return any(r.search(path) for r in NEUTRAL_RE)


def classify(paths):
    """(テストコードのパス, プロダクションコードのパス) を返す。中立パスは捨てる。"""
    tests = [p for p in paths if is_test(p)]
    prod = [p for p in paths if is_production(p)]
    return tests, prod
