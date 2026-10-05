# GitLab 開発ワークフローのセットアップ

このリポジトリの開発ワークフロー（Issue・ボード・Merge Request）はセルフホストの
**GitLab Community Edition** 上で回る。この文書は、新しいマシンで、あるいは環境を作り直した
ときに、その環境をゼロから再現するための手順である。

製品そのものの開発環境（`docker compose` まわり）は
[GETTING_STARTED.md](GETTING_STARTED.md) を参照。こちらは**ワークフロー用の道具立て**だけを扱う。

---

## 接続先

| 項目 | 値 |
| --- | --- |
| Web / API | `https://server.tonoccho.local/gitlab` |
| Git (SSH) | `ssh://git@server.tonoccho.local:2222/seiji/lets_blog_server.git` |
| エディション | GitLab Community Edition |
| 既定ブランチ | `develop` |

### 知らないと詰まる3点

この環境には、標準的な GitLab とは違う条件が3つある。どれも黙って失敗するので先に挙げる。

1. **GitLab がサブパス配下にある。** ルートの `https://server.tonoccho.local/` は別のページで、
   API も `/api/v4` ではなく `/gitlab/api/v4` にある。`glab` の `subfolder` 設定が要る。
2. **証明書が自己署名。** 社内 CA (`tonoccho.local Local CA`) が発行している。`glab` の
   `ca_cert` 設定が要る。
3. **`sudo` が使えない**（パスワードを要求される）。`apt` でのインストールはできないので、
   `glab` はユーザー領域に置く。

---

## 1. `glab` を入れる

`sudo` を使わずに済ませるため、公式リリースの tarball を `~/.local/bin` に展開する。
`~/.local/bin` が `PATH` に入っていることを確認すること。

```bash
VERSION=1.116.0
BASE="https://gitlab.com/api/v4/projects/gitlab-org%2Fcli/packages/generic/glab/${VERSION//./%2E}"

cd "$(mktemp -d)"
curl -fsSL -o glab.tar.gz  "$BASE/glab_${VERSION//./%2E}_linux_amd64%2Etar%2Egz"
curl -fsSL -o checksums.txt "$BASE/checksums%2Etxt"

# 必ず照合する。ここを飛ばすと、後段の全ての検証が意味を失う。
grep "linux_amd64.tar.gz" checksums.txt
sha256sum glab.tar.gz

tar -xzf glab.tar.gz
install -m 0755 bin/glab ~/.local/bin/glab
glab version
```

最新版は次で確認できる。

```bash
curl -sS "https://gitlab.com/api/v4/projects/gitlab-org%2Fcli/releases?per_page=1" | jq -r '.[0].tag_name'
```

---

## 2. 自己署名 CA を信頼する

`sudo` が無いためシステムの信頼ストアには入れられない。ユーザー領域に置き、`glab` と `git` に
明示的に指定する。

サーバが送ってくる証明書チェーンからルート CA を取り出す。

```bash
mkdir -p ~/.local/share/ca-certificates
openssl s_client -connect server.tonoccho.local:443 -servername server.tonoccho.local -showcerts </dev/null 2>/dev/null \
  | awk '/-----BEGIN CERTIFICATE-----/{n++} n==2' \
  | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' \
  > ~/.local/share/ca-certificates/tonoccho-local-ca.crt
```

### フィンガープリントを必ず突き合わせる

**この CA は、まだ検証できていない TLS 接続から取得している**（TOFU: trust on first use）。
中間者がいれば、その中間者の CA を信頼してしまう。取得したものが本物かを、サーバ側の CA
ファイルと突き合わせて確認すること。

```bash
openssl x509 -in ~/.local/share/ca-certificates/tonoccho-local-ca.crt \
  -noout -subject -issuer -dates -fingerprint -sha256
```

2026-09-03 時点の実測値:

```
subject   = C=JP, O=tonoccho.local, CN=tonoccho.local Local CA
issuer    = 同上（自己署名）
notAfter  = Aug 30 20:25:09 2036 GMT
SHA256    = 96:4B:2B:9E:7B:78:CA:67:B6:05:4E:37:15:DE:CC:A3:0C:B9:B3:B0:C8:73:0F:AE:6E:E6:CE:53:08:38:40:8F
```

値が違う場合、CA が再発行されたか、接続が乗っ取られている。**先に進まず、サーバ側を確認すること。**

### 検証が通ることを確かめる

```bash
openssl s_client -connect server.tonoccho.local:443 -servername server.tonoccho.local \
  -CAfile ~/.local/share/ca-certificates/tonoccho-local-ca.crt </dev/null 2>/dev/null \
  | grep 'Verify return code'
# => Verify return code: 0 (ok)
```

`git` にも登録しておく（git 操作は SSH 経由なので必須ではないが、HTTPS を使う道具のため）。

```bash
git config --global "http.https://server.tonoccho.local/.sslCAInfo" \
  "$HOME/.local/share/ca-certificates/tonoccho-local-ca.crt"
```

> **`skip_tls_verify` は使わないこと。** `glab config set skip_tls_verify true` にすれば
> 手っ取り早く動くが、それは証明書の検証そのものを止める設定であり、中間者攻撃を検知
> できなくなる。CA を正しく指定すれば検証は通るので、握り潰す理由が無い。

---

## 3. `glab` のホスト設定

サブパス構成と CA を登録する。**トークンを入れる前に済ませること** — `glab auth login` は
API を叩くので、この設定が無いと失敗する。

```bash
H=server.tonoccho.local
CA="$HOME/.local/share/ca-certificates/tonoccho-local-ca.crt"

glab config set api_protocol https  --host "$H"
glab config set subfolder   gitlab  --host "$H"   # ← サブパス構成。これが要
glab config set ca_cert     "$CA"   --host "$H"   # ← 自己署名 CA
glab config set git_protocol ssh    --host "$H"
```

---

## 4. アクセストークン

https://server.tonoccho.local/gitlab/-/user_settings/personal_access_tokens で発行する。

**必要なスコープは `api` のみ。** git 操作は SSH で行うため、`read_repository` /
`write_repository` は不要。

```bash
glab auth login --hostname server.tonoccho.local
```

対話で聞かれる内容:

| プロンプト | 回答 |
| --- | --- |
| How would you like to authenticate? | **Token** |
| Paste your authentication token | 発行した PAT（入力は非表示） |
| Choose default git protocol | **SSH** |

このコマンドは端末を要求する。非対話の環境では `--stdin` でファイルから読ませるか、
`GITLAB_TOKEN` 環境変数で渡す。

### 確認

```bash
glab auth status
```

次が全て出れば完了。

```
✓ Logged in to server.tonoccho.local as <user>
✓ REST API Endpoint: https://server.tonoccho.local/gitlab/api/v4/
✓ Subfolder: gitlab
✓ Git operations ... ssh
```

キーリングが動いていない環境では、トークンは `~/.config/glab-cli/config.yml` に平文で
保存される。`glab auth status` の出力が保存先を明示するので、そこで確認すること。
平文保存を避けたい場合は、保存せず `GITLAB_TOKEN` で渡す運用にできる。

---

## 5. ラベルとボード

ステータスと優先度のラベル、および Issue ボードの列を作る。

```bash
scripts/setup-gitlab-board.sh
```

**このスクリプトが、ラベル構成の唯一の定義である。** 名前・色・説明を変えたいときはここを
直す。冪等なので、既にある要素はスキップされる。何度実行しても同じ状態になる。

作られるもの:

| 種類 | 値 |
| --- | --- |
| ステータス | `status::Inbox` → `Backlog` → `Ready` → `In Progress` → `Review` → `QA` → `Done` |
| 優先度 | `priority::P0` / `priority::P1` / `priority::P2` |
| ボード | 既定ボード `Development` に、ステータス7つを列として追加 |

GitLab がプロジェクト作成時に自動で作る既定ボードに列を足す。2枚目を作ると、プロジェクトを
開いたとき最初に表示されるボードが空のままになるため。

ステータスの意味と遷移のしかたは
[`.claude/CLAUDE.md`](../.claude/CLAUDE.md) の **How status is represented** /
**How to change status** が唯一の定義である。ここには書き写さない。

### 整合性の確認

```bash
scripts/check-issue-labels.sh
```

全ての open Issue が `status::` と `priority::` をちょうど1つずつ持つことを検査する。
GitLab CE のラベルに排他性は無い（スコープ付きラベルは Premium）ので、Web UI で手作業した
あとは実行しておくとよい。

---

## 6. マージ設定

プロジェクト側で次が設定されている。変更すると履歴の形が変わる。

| 項目 | 値 | 理由 |
| --- | --- | --- |
| `merge_method` | `ff` | squash コミットを fast-forward で入れる。`merge` だと squash コミットの**上にマージコミットが積まれ**、1 Issue = 1 コミットの線形履歴が崩れる |
| `squash_option` | `always` | Web UI からのマージも squash になる |

```bash
glab api "projects/:id" | jq '{merge_method, squash_option}'
```

---

## 7. GitHub へのミラー

移行元の GitHub リポジトリ `tonoccho/lets_blog_server`（削除していない。遅くとも 2026-10-01 から public で、2026-10-05 に再確認した）を、
**GitLab → GitHub の一方向 push ミラー**として維持する（#1256）。GitLab がコード・Issue・
MR の source of truth であることは変わらない。GitHub 側の変更が GitLab へ戻ることはなく、
GitHub 上での Issue・PR・CI の運用も行わない（[ADR-0010](adr/0010-github-to-gitlab-migration.md)
決定4・決定7）。

公開範囲はコードとコミット履歴のみである（#1251）。GitHub 側の Issue・Wiki・Projects・
Discussions・Pull Request は機能を無効化して公開しない（無効化はデータを削除しないので、
移行前の Issue は保全される。#1032）。公開が先行し、秘密情報スキャンは公開後に実施した。
露出していた実資格情報2件（Keycloak クライアントシークレットと E2E アカウントのパスワード）は
2026-10-01 にローテーション済みで、2026-10-05 の再スキャン（gitleaks v8.30.1、#1251 の
note 14803・14806）の未解決の検出は 0 件である。2026-10-05 に資格情報なしの `git clone` の
成功も再確認した。GitLab は引き続き source of truth で、非公開のままである。

### 7.1 前提: `main` を保護ブランチにする

GitLab CE では正規表現によるブランチ絞り込み（Mirror specific branches）が使えず（Premium
機能）、ミラー側で選べる絞り込みは `only_protected_branches` のみである。これを効かせるため、
`develop` と同じ条件で `main` も保護ブランチに追加しておく。

| 項目 | 値 |
| --- | --- |
| push | Maintainers（access level 40） |
| merge | Maintainers（access level 40） |
| `allow_force_push` | `false` |

作成は Web UI の Settings → Repository → Protected branches → Add protected branch で、
Branch に `main`、Allowed to merge と Allowed to push and merge に Maintainers を選び、
Allowed to force push をオフにする。作成後に確認する:

```bash
glab api "projects/:id/protected_branches" | jq '[.[] | {name, allow_force_push}]'
```

`main` の保護は、`develop` と同様に直接 push を Maintainers に制限する。#1274 のリリース
push（develop を main へマージし semver タグを付ける）は Owner の `seiji` が行うため影響しない。

### 7.2 GitHub トークンを発行する

GitHub 側で fine-grained personal access token を発行する。**利用者が GitHub の Web UI で
発行し、次の 7.3 のミラー設定画面に直接入力する。** トークンの値は、リポジトリにも docs にも
エージェントとの会話にも出さない。

| 項目 | 値 |
| --- | --- |
| Resource owner | `tonoccho` |
| Repository access | Only select repositories → `tonoccho/lets_blog_server` |
| Contents | Read and write |
| Workflows | **Read and write** |
| Metadata | Read-only（自動で付く） |
| それ以外 | No access |

<!-- github-mirror-note:start -->
**Workflows がなぜ要るか。** GitHub は `.github/workflows/` 配下のファイルを作成・変更・
**削除**するコミットの push を、Workflows 権限の無いトークンでは拒否する。

- develop の初回同期（GitHub 側 HEAD → GitLab の develop 先頭）には、CI 設定を削除した
  コミット（!1027、ADR-0010 決定4）が含まれ、`.github/workflows/*.yml` の削除を push する。
- GitHub の `main` の木には現時点で `.github/workflows/` が残っている。#1274 で develop を
  main へマージすると、main 上でも同じ削除が push される。

「ADR-0010 で `.github/` を削除済みだから Workflows 権限は要らない」という判断は誤りだった
（#1256 のコメントで訂正済み）。削除そのものが Workflows 権限を要求する。
<!-- github-mirror-note:end -->

（この注記は GitHub ミラー先の事情を説明するものであり、このリポジトリ自身に削除済みの
ディレクトリを復活させる案内ではない。`.claude/hooks/test_workflow_docs.py` の
`NoReferencesToDeletedPaths` が、マーカーの外に出た言及を引き続き検出する。）

有効期限は利用者が発行時に決める。期限切れ時の再発行手順は 7.6 を参照。

### 7.3 GitLab 側にミラーを設定する

GitLab の Web UI: Settings → Repository → Mirroring repositories → Add new mirror repository。

| 項目 | 値 |
| --- | --- |
| Git repository URL | `https://github.com/tonoccho/lets_blog_server.git` |
| Mirror direction | Push |
| Authentication method | Username and Password |
| Username | `tonoccho` |
| Password | 7.2 で発行したトークン |
| Mirror only protected branches | 有効 |
| Keep divergent refs | 無効（既定の GitLab push ミラーの動作。強制 push・履歴書き換えが
  GitHub 側にあっても上書きしてよいという利用者決定に一致する） |

API から確認する場合（ミラー URL の応答はユーザー名までマスクされる。トークンは含まれない）:

```bash
glab api "projects/:id/remote_mirrors" | \
  jq '.[] | {id, url, enabled, only_protected_branches, keep_divergent_refs, update_status, last_error}'
```

### 7.4 ブランチ・タグの初回整合

- `develop`・`main` は、ミラーの初回同期で GitHub 側へ fast-forward で反映される。手作業の
  push は不要。
- **GitHub にだけ残っているブランチ**（`main` / `develop` 以外）は、保護ブランチのみの
  ミラーが触れないため、消えずに残る。GitHub の Web UI で手動で削除する。
- 同期の前に、GitHub 側のブランチとタグの一覧を控えておく。GitHub にだけ存在するタグが
  あれば扱いを利用者が決める。

### 7.5 タグは絞り込めない（常に全件ミラーされる）

`only_protected_branches: true` にしていても、GitLab の push ミラーは**タグを全件 GitHub へ
送る**。GitLab 側にタグの絞り込みオプションは無い（gitlab-org/gitlab#24873、#457680。CE・
Premium を問わず未実装）。

帰結として、**GitLab 上で作ったタグは、どのコミットを指していても GitHub に公開される。**
運用規約として、GitLab に使い捨てのタグを作らない（動作確認用の一時タグを除き、確認後は
GitLab・GitHub 双方から削除する）。#1274 が付ける semver タグは、この経路でそのまま
GitHub 側のリリースバージョンになる。

### 7.6 同期状況の確認・失敗時の対処・トークン再発行

**状況の確認:**

```bash
glab api "projects/:id/remote_mirrors" | \
  jq '.[] | {update_status, last_update_started_at, last_successful_update_at, last_error}'
```

| フィールド | 意味 |
| --- | --- |
| `update_status` | `finished` なら直近の同期は完了している |
| `last_successful_update_at` | 最後に成功した同期時刻 |
| `last_error` | `null` でなければ同期が失敗している。理由がここに入る |

反映は push から最大5分、`only_protected_branches` が有効なら最大1分。即時ではない。

**GitLab の Web UI は同期完了後も「Updating」と表示され続けることがある**（画面を再読み込み
するまで更新されない、2026-09-13 に実機で確認）。UI の表示だけで判断せず、上記 API で
`update_status` / `last_error` を確認すること。

同期を今すぐ走らせたい場合は、Web UI の「Update now」ボタン、または API で:

```bash
glab api "projects/:id/remote_mirrors/<id>/sync" --method POST
```

**`last_error` が入っている場合の対処:**

- 認証エラー（トークン期限切れ・権限不足）→ 下記「トークンの再発行」の手順で新しいトークンに
  差し替える。
<!-- github-mirror-note:start -->
- `.github/workflows/` を含む差分で拒否される場合 → トークンの Workflows 権限が
  「Read and write」になっているか確認する（7.2）。
<!-- github-mirror-note:end -->
- 到達性の問題（GitLab コンテナから `github.com:443` に出られない）→ ネットワーク側の調査に
  切り分ける。この環境では 2026-09-13 時点で到達可能（HTTP 200）であることを確認済み。

**トークンの再発行手順:**

1. GitHub の Web UI で新しい fine-grained PAT を、7.2 と同じ権限（Contents: Read and write、
   Workflows: Read and write）で発行する。
2. GitLab の Web UI: Settings → Repository → Mirroring repositories → 対象ミラーの
   Edit → Password 欄に新しいトークンを入力して保存する（URL やユーザー名は変えない）。
3. 「Update now」で同期を走らせる。
4. `glab api "projects/:id/remote_mirrors"` で `last_error: null` を確認する。
5. 確認できたら、GitHub 側で旧トークンを Revoke する。

---

## トラブルシュート

| 症状 | 原因 |
| --- | --- |
| `404 Not Found` (API) | `subfolder` が未設定。`/api/v4` を見にいっている |
| `x509: certificate signed by unknown authority` | `ca_cert` が未設定、またはパスが違う |
| `401 Unauthorized` | トークン未設定、期限切れ、スコープ不足（`api` が要る） |
| `glab: command not found` | `~/.local/bin` が `PATH` に無い |
| `glab auth login` が固まる | 端末が無い環境で対話ログインしている。`--stdin` か `GITLAB_TOKEN` を使う |
| `glab mr create` だけ `404 Project Not Found`（`glab issue` 系・`glab mr list` は動く）。エラー URL のプロジェクトパスに `gitlab/` が二重に入っている（例: `projects/gitlab/seiji/lets_blog_server`） | `api_host` に `/gitlab` を直接書いてしまっている（例: `api_host: server.tonoccho.local/gitlab`）。`subfolder` は既に別項目として存在するので、二重に持たせると `mr create` のプロジェクト解決だけがこれを壊す（#1091）。`glab config set api_host server.tonoccho.local --host server.tonoccho.local` で `api_host` から `/gitlab` を外し、`subfolder` 側だけに持たせる。手順3の `glab config set` をそのまま実行していれば発生しない |

---

## 関連

- [`.claude/CLAUDE.md`](../.claude/CLAUDE.md) — ワークフローの規約。ステータスの表現と遷移、
  マージ方式、依存の判定基準
- `scripts/setup-gitlab-board.sh` — ラベルとボードの定義
- `scripts/check-issue-labels.sh` — ラベル整合性の検査
- `scripts/issue-dependency-status.sh` — Ready/Backlog 判定の入力を取得する
- [`adr/0010-github-to-gitlab-migration.md`](adr/0010-github-to-gitlab-migration.md) —
  GitHub を一方向ミラーとして残す方針の記録
