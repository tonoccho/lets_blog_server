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

## トラブルシュート

| 症状 | 原因 |
| --- | --- |
| `404 Not Found` (API) | `subfolder` が未設定。`/api/v4` を見にいっている |
| `x509: certificate signed by unknown authority` | `ca_cert` が未設定、またはパスが違う |
| `401 Unauthorized` | トークン未設定、期限切れ、スコープ不足（`api` が要る） |
| `glab: command not found` | `~/.local/bin` が `PATH` に無い |
| `glab auth login` が固まる | 端末が無い環境で対話ログインしている。`--stdin` か `GITLAB_TOKEN` を使う |

---

## 関連

- [`.claude/CLAUDE.md`](../.claude/CLAUDE.md) — ワークフローの規約。ステータスの表現と遷移、
  マージ方式、依存の判定基準
- `scripts/setup-gitlab-board.sh` — ラベルとボードの定義
- `scripts/check-issue-labels.sh` — ラベル整合性の検査
- `scripts/issue-dependency-status.sh` — Ready/Backlog 判定の入力を取得する
