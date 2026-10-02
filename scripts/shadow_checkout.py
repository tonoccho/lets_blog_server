"""コミットされないローカル状態(`.env` / `certs/`)が無い作業ツリーでも、
スクリプトの単体テストを偽の失敗にしないための補助(#1291)。

`rebuild-acceptance-env.sh` / `setup-shared-host-proxy.sh` は、自身の位置から
`REPO_ROOT` を求めて `$REPO_ROOT/.env` や `$REPO_ROOT/certs/` を読む。これらは
`.gitignore` 対象なので、`git worktree add` した作業ツリーや新規チェックアウトには無い。
スクリプトは本番と同じ実物を使いたい(偽物に差し替えると検出力が落ちる)ので、
**足りないものだけダミーで補った影のチェックアウト**を一時ディレクトリに作り、
その中のスクリプトを実行する。

前提が揃っている作業ツリー(メイン、`release-verify-tag.py` の隔離 clone)では
`needs_shadow()` が False を返し、呼び出し側は今までどおり実物を直接実行する。
つまり実行されるテストの件数も検出力も変わらず、スキップも発生しない。
ダミーは「ファイルが在る」ことだけを満たす値で、テストが検証する振る舞い
(配置・検証順序・ヘルパー呼び出し)はその中身に依存しない。
"""

import os

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, ".."))

# ローカルにだけ在る前提と、無いときに置くダミー(リポジトリ直下からの相対パス)。
DUMMY_FILES = {
    ".env": (
        "MYSQL_ROOT_PASSWORD=dummy\n"
        "KEYCLOAK_ADMIN_USERNAME=admin\n"
        "KEYCLOAK_ADMIN_PASSWORD=admin\n"
    ),
    "certs/localhost.crt": "dummy certificate\n",
    "certs/localhost.key": "dummy key\n",
}


def missing(root=REPO_ROOT, wanted=None):
    """`wanted`(既定は全部)のうち、`root` に実在しないものの相対パス。"""
    names = DUMMY_FILES if wanted is None else wanted
    return [rel for rel in names if not os.path.exists(os.path.join(root, rel))]


def needs_shadow(wanted=None, root=REPO_ROOT):
    return bool(missing(root, wanted))


def make(dest, wanted=None, root=REPO_ROOT):
    """`root` の影を `dest` に作り、足りない `wanted` だけダミーで補う。返り値は `dest`。

    `scripts/` は実体のディレクトリにして中身を個別にシンボリックリンクする。
    スクリプトは `BASH_SOURCE` の親から REPO_ROOT を求めるので、`scripts/` 自体を
    リンクにすると REPO_ROOT が実物へ戻ってしまい、ダミーが見えなくなる。
    実物の修正はリンク越しにそのまま反映される。
    """
    lacking = missing(root, wanted)
    dummy_tops = {rel.split("/")[0] for rel in lacking}
    for entry in os.listdir(root):
        if entry in (".git", "scripts") or entry in dummy_tops:
            continue
        os.symlink(os.path.join(root, entry), os.path.join(dest, entry))
    # 一部だけ欠けたディレクトリ(例: certs/ に .crt だけ)は、在るものを引き継ぐ。
    for top in dummy_tops:
        real = os.path.join(root, top)
        if os.path.isdir(real):
            os.makedirs(os.path.join(dest, top), exist_ok=True)
            for entry in os.listdir(real):
                os.symlink(os.path.join(real, entry), os.path.join(dest, top, entry))
    scripts_dst = os.path.join(dest, "scripts")
    os.makedirs(scripts_dst)
    for entry in os.listdir(os.path.join(root, "scripts")):
        os.symlink(os.path.join(root, "scripts", entry), os.path.join(scripts_dst, entry))
    for rel in lacking:
        path = os.path.join(dest, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(DUMMY_FILES[rel])
    return dest
