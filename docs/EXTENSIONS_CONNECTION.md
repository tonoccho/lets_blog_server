# 同じ PC の lets_blog_server_extensions へつなぐ(#1762)

`seiji/lets_blog_server_extensions`(以下 extensions)は ComfyUI・Ollama・PlantUML などを compose の
プロジェクト名 `lbx` で動かし、外部 Docker ネットワーク `lbx-shared` に別名で参加している。
同じ PC で動かすときは、このネットワークへ LBS の ai / media / platform を参加させて名前で届かせる。
別ホストの extensions へは公開 URL でつなぐ(#1763、末尾の「別ホストの extensions へつなぐ」)。

| 別名(`lbx-shared` 上) | 用途 |
| --- | --- |
| `lbx-ollama:11434` | LLM(OpenAI 互換は `/v1`) |
| `lbx-comfyui:8188` | 画像生成 |
| `lbx-plantuml:8080` | PlantUML の描画 |
| `lbx-portal:3100` | extensions の portal(接続先の確認用) |

## 1. extensions を起動する

extensions のリポジトリで、共有ネットワークを作ってから起動する(スクリプトは extensions 側にある)。

```bash
scripts/create-shared-network.sh
docker compose -p lbx up -d
```

## 2. オーバーレイ付きで LBS を起動する

```bash
docker compose -f docker-compose.yml -f docker-compose.extensions.yml up -d
# docker-compose.shared-host.yml と併用する場合
docker compose -f docker-compose.yml -f docker-compose.shared-host.yml -f docker-compose.extensions.yml up -d
```

- 参加するのは ai / media / platform / publishing だけ。既存の `lbs-net`(platform は `docker-socket-proxy-net` も)は保つ。
- ネットワーク名を変えるときは環境変数 `LBX_SHARED_NETWORK`(既定 `lbx-shared`)を設定する。
- media と platform の `PLANTUML_BASE_URL` は `http://lbx-plantuml:8080` になる(`docker-compose.yml` 単体の既定値も同じ。#1784)。
- `lbx-shared` が無いホストでは重ねないこと(重ねなければ今までどおり起動する)。
  受け入れテスト環境のスクリプトはこのオーバーレイを使わない。

## 3. システム設定に接続先を入れる

管理画面 → システム設定の「extensions から取り込む」欄で、ポータルの URL に `http://lbx-portal:3100`、経路に「同じ PC(共有ネットワーク)」を
選んで「取得」を押す。Ollama / ComfyUI / Penpot の候補が「現在の値」と「取り込む値」で並ぶので、確認して「反映」を押すと保存される
(ポータルの URL も保存され、次回は入力済みになる)。検証で拒否された項目は保存されず、キーと理由が表示される(他の項目は保存される)。
同じ PC の Penpot は `publicUrl`(`https://penpot.<BASE_DOMAIN>:<port>`)を使うので、`BASE_DOMAIN` が `*.localhost` のままだとコンテナ内では
ループバックに解決され、Penpot だけが拒否される。

手で入力する場合の代替(システム設定の次の項目へ直接入れる):

- `llm_ollama_base_url` = `http://lbx-ollama:11434/v1`
- `comfyui_base_url` = `http://lbx-comfyui:8188`

PlantUML はオーバーレイが環境変数で設定するので入力は要らない。

## 4. 別名のない名前を使わない理由

extensions のコンテナは `ollama` / `comfyui` / `plantuml` という別名を持たない。LBS 自身も同梱をやめた(PlantUML は #1784 が最後)ので、
これらの名前は解決できない。必ず `lbx-` 付きの別名を使う。

## 5. プロジェクト単位の上書きでは指定できない

プロジェクト単位の接続先の上書きは `ConnectionDestinationGuard` が docker の内部アドレスを拒否するため、
`lbx-*` は指定できない。`lbx-*` はシステム設定でだけ使う(システム設定は同検査を通らない)。

## 6. 確かめ方

- extensions の接続先一覧: LBS のコンテナ内から `GET http://lbx-portal:3100/api/connections`
  (例: `docker compose exec platform curl -s http://lbx-portal:3100/api/connections`)。
- 管理画面の「連携サービスの状況」で LLM・ComfyUI・PlantUML が正常と表示されること。PlantUML のプレビューが描画されること。

## 別ホストの extensions へつなぐ(自己署名 HTTPS、#1763)

別ホストの extensions に届くのは、リバースプロキシの `https://<tool>.${BASE_DOMAIN}:${HTTPS_PORT}` だけである。
証明書は extensions の起動時に作られる自己署名(SAN は `BASE_DOMAIN` と `*.BASE_DOMAIN`、期限は825日)で、
LBS の JVM は既定の信頼ストアしか使わないため、そのままでは TLS の検証で失敗する。
`docker-compose.extra-ca.yml` で、その証明書を ai / media / platform / publishing の信頼ストアへ**追加**する。

### 手順

1. extensions のホストで証明書を取り出す(extensions のリポジトリで):

   ```bash
   docker compose cp proxy:/etc/nginx/certs/tls.crt ./lbx-ca.crt
   ```

2. LBS のホストの `certs/lbx-ca.crt` に置く(`certs/` は `.gitignore` 済み)。別の場所に置くなら
   環境変数 `LBX_CA_CERT_FILE` にパスを入れる。ファイルが無いまま起動すると Docker が同名のディレクトリを作るので、先に置く。
3. extensions の `BASE_DOMAIN` を、LBS のホストから引ける名前にする。`*.localhost` は LBS のコンテナ内では
   自分自身を指すので使えない。DNS か LBS のホストの hosts に `ollama.<domain>` `comfyui.<domain>`
   `plantuml.<domain>` を登録する。コンテナからも引く必要があるので、DNS で解決できないときは
   compose のオーバーライドで `extra_hosts` を足す。
4. オーバーレイを重ねて起動する:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.extra-ca.yml up -d
   ```

   重ねるのは ai / media / platform / publishing だけで、`/certificates/lbx-ca.crt` に読み取り専用でマウントし、
   temurin の `/__cacert_entrypoint.sh`(`USE_SYSTEM_CA_CERTS=1`)で JVM の cacerts へ追加する。
   公開 CA(api.openai.com など)は従来どおり通り、マウントした証明書以外の自己署名証明書は拒否される。
5. 管理画面 → システム設定の「extensions から取り込む」欄で、ポータルの URL に `https://<domain>:<port>`、経路に「別ホスト(公開 URL)」を選んで
   「取得」→「反映」を押す(`<port>` は extensions の `HTTPS_PORT`。ポータルの TLS は手順 1〜2 で信頼させた証明書で検証される)。
   手で入力する場合の代替は次のとおり:
   - `llm_ollama_base_url` = `https://ollama.<domain>:<port>/v1`
   - `comfyui_base_url` = `https://comfyui.<domain>:<port>`

   extensions の `/api/connections` の `openaiBaseUrl` は内部 URL なので使わない。`publicUrl` に `/v1` を付ける。
6. PlantUML は環境変数 `LBX_PLANTUML_URL=https://plantuml.<domain>:<port>` で media と platform に渡す
   (未設定なら `http://lbx-plantuml:8080`。LBS は PlantUML を同梱しないので #1784 でこの既定値に変えた。
   同じ PC の extensions ならそのまま届き、`docker-compose.extensions.yml` を重ねても同じ値になる。
   extensions も別途起動した PlantUML サーバーも無い環境では、図の描画はできない)。

### 取り直しが要るとき

- extensions 側で `BASE_DOMAIN` を変えたとき(SAN が変わる)。
- 証明書の期限(825日)が切れる前、または切れたとき。
- いずれも手順1・2で `lbx-ca.crt` を差し替え、オーバーレイ付きで ai / media / platform を再作成する(`up -d --force-recreate`)。

### 確かめ方

```bash
# 別名は証明書の CN。件数が重ねる前より減っていないことも見る
docker compose exec ai keytool -list -cacerts -storepass changeit | grep -ci trustedCertEntry
docker compose exec ai keytool -list -cacerts -storepass changeit | grep -i '<証明書の CN>'
```

管理画面の「連携サービスの状況」で LLM・ComfyUI・PlantUML が正常と表示されること。

## extensions の GitLab へつなぐ(ai / publishing、#1795)

ai と publishing は、`docker-compose.extensions.yml`(同じ PC)または `docker-compose.extra-ca.yml`(別ホスト)を重ねると
extensions の GitLab に届く。

| 場所 | 接続先 |
| --- | --- |
| 同じ PC | `http://lbx-gitlab`(`lbx-shared` 上の別名、HTTP 80) |
| 別ホスト | `https://gitlab.<domain>:<port>`(自己署名。上の手順で `lbx-ca.crt` を信頼させる) |

### Access Token の作り方

GitLab で対象ユーザーの「Preferences → Access Tokens」を開き、scope `api` の Personal Access Token を作って、
製品のプロジェクト設定のトークン欄へ入れる(トークンは作成時にしか表示されない)。

### clone URL

GitLab が返す clone URL は、`external_url` に基づく公開 URL(`https://gitlab.<domain>:<port>/...`)になる。
同じ PC でも `http://lbx-gitlab` にはならないので、clone する側はその公開 URL を引けて信頼できる必要がある。

### GitLab の到達性を連携サービスの状況に出す(#1796)

システム設定画面(管理者)の「GitLab連携」にある **GitLab ベースURL**(`system_settings` のキー `gitlab_base_url`、
例: `http://lbx-gitlab`)を設定すると、連携サービスの状況に GitLab の行が出る。システム全体に1つで、認証は持たず、
環境変数の既定値は無い。空欄で保存すると未設定に戻る。`lbx-*` のホスト名はそのまま指定できる
(システム設定の値は `ConnectionDestinationGuard` の検査を通さない)。

到達性は `GET <URL>/users/sign_in` を1回(再試行しない・タイムアウト 3 秒・リダイレクトは追従しない)で判定する。
`/-/readiness` は localhost 以外からの要求に 404 を返すので使わない。

| 状態 | 表示 | 理由の表示(管理者) |
| --- | --- | --- |
| URL が未設定(行は省かない) | 警告 | 「未設定(システム設定で設定してください)」 |
| 応答が 2xx | 正常 | なし |
| 2xx 以外(3xx・4xx・5xx) | エラー | 「GitLab が 2xx 以外を返しました(HTTP ステータス)」 |
| 接続できない・タイムアウト | エラー | 「GitLab に到達できません: 原因」 |

## extensions から lets_blog の Keycloak を OIDC の発行者として使う(#1805)

同じ PC の extensions(GitLab・Penpot・proxy)が、lets_blog の Keycloak(realm `letsblog`)でログインできるようにする。
ブラウザの SSO は同じ PC だけが対象で、別ホストのブラウザ SSO(`KC_HOSTNAME` の変更)は後続の Issue。
この節の手順は**利用者が実行する**。実装は稼働中の Keycloak に何もしない。

`docker-compose.extensions.yml` を重ねると、keycloak が `lbs-net` に加えて `lbx-shared` にも別名 `lbs-keycloak` で参加する
(重ねないときの構成は変わらない)。issuer は `KC_HOSTNAME` で固定のまま、`https://localhost/auth/realms/letsblog` で変わらない。

### 内部の JWKS と token の URL

extensions のコンテナから見ると、issuer の `https://localhost/auth/...` は自分自身を指す。そのため GitLab と Penpot は
discovery(`.well-known`)を使えず、エンドポイントを個別に指定する。

| 用途 | URL |
| --- | --- |
| authorization(ブラウザ向けの公開 URL) | `https://localhost/auth/realms/letsblog/protocol/openid-connect/auth` |
| token(`lbx-shared` の内部 URL) | `http://lbs-keycloak:8080/auth/realms/letsblog/protocol/openid-connect/token` |
| JWKS(`lbx-shared` の内部 URL) | `http://lbs-keycloak:8080/auth/realms/letsblog/protocol/openid-connect/certs` |

別ホストの extensions が API(client credentials)で使うときは、token と JWKS を lets_blog の公開 URL
(`https://<lets_blog のホスト>/auth/realms/letsblog/protocol/openid-connect/{token,certs}`)から取る。

### extensions の `.env` に渡す値

| クライアント | clientId | secret(lets_blog の `.env`) | リダイレクト URI | ログインできる人 |
| --- | --- | --- | --- | --- |
| GitLab | `lbx-gitlab` | `KEYCLOAK_GITLAB_CLIENT_SECRET` | `https://gitlab.<BASE_DOMAIN>:<HTTPS_PORT>/users/auth/openid_connect/callback` | lets_blog のアカウント全員 |
| Penpot | `lbx-penpot` | `KEYCLOAK_PENPOT_CLIENT_SECRET` | `https://penpot.<BASE_DOMAIN>:<HTTPS_PORT>/api/auth/oidc/callback` | lets_blog のアカウント全員 |
| proxy(ポータル・ComfyUI) | `lbx-proxy` | `KEYCLOAK_PROXY_CLIENT_SECRET` | `https://portal.<BASE_DOMAIN>:<HTTPS_PORT>/oauth2/callback` と `https://comfyui.<BASE_DOMAIN>:<HTTPS_PORT>/oauth2/callback` | `admin` ロールだけ |

- 各 secret は lets_blog の `.env` と同じ値を extensions の `.env` に写す。`setup.sh` の新規構築はランダム値を生成する。
- `<BASE_DOMAIN>` / `<HTTPS_PORT>` は extensions の `BASE_DOMAIN` / `HTTPS_PORT`。lets_blog の `.env` には
  `LBX_BASE_DOMAIN` / `LBX_HTTPS_PORT` として同じ値を置く(未設定なら `localhost` / `443`)。
  この 2 つと 3 つの secret は、`docker-compose.extensions.yml` を重ねたときだけ keycloak コンテナへ渡り、realm の import が受け取る。
- `admin` だけに絞るのは proxy のクライアントだけ。realm ロールをトークンの `roles` クレーム(配列)に載せるので、
  proxy 側で `roles` に `admin` が含まれることを確かめる。GitLab と Penpot はロールで制限しない。
- リダイレクト URI は、ポート付き(`:<HTTPS_PORT>`)とポート無しの両方を登録する。既定の 443 ではブラウザが `:443` を付けないため。
  kcadm で足すときも `redirectUris` に両方を入れる。
- 上のリダイレクト URI のパスは各ソフトの標準のコールバックを仮定している。extensions 側が別のパスを使うなら、
  Keycloak 側のクライアントの Valid redirect URIs を合わせる。

### 新しく構築する環境

`.env` に上の値があり、`-f docker-compose.yml -f docker-compose.extensions.yml` で起動すれば、初回の realm import で 3 つの
クライアントが作られる。

### 既存の Keycloak にクライアントを足す(kcadm、利用者が実行)

`--import-realm` は既存の realm を上書きしないので、稼働中の Keycloak には手で足す。`<…>` は自分の値に置き換える。

```bash
# 0. オーバーレイ付きで keycloak を作り直し、lbx-shared に参加させる(realm のデータは消えない)
docker compose -f docker-compose.yml -f docker-compose.extensions.yml up -d keycloak

# 1. 管理者でログインする
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080/auth --realm master --user "$KEYCLOAK_ADMIN_USERNAME" --password "$KEYCLOAK_ADMIN_PASSWORD"

# 2. クライアントを作る(gitlab・penpot・proxy で繰り返す。redirectUris は上の表のとおり)
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh create clients -r letsblog \
  -s clientId=lbx-gitlab -s enabled=true -s publicClient=false -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=false \
  -s secret="<KEYCLOAK_GITLAB_CLIENT_SECRET>" \
  -s 'redirectUris=["https://gitlab.<BASE_DOMAIN>:<HTTPS_PORT>/users/auth/openid_connect/callback","https://gitlab.<BASE_DOMAIN>/users/auth/openid_connect/callback"]' \
  -s 'attributes."pkce.code.challenge.method"=S256'

# 3. proxy だけ、realm ロールを roles クレームに載せるマッパーを足す
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh get clients -r letsblog -q clientId=lbx-proxy --fields id
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh create clients/<上で出た id>/protocol-mappers/models -r letsblog \
  -s name='realm roles (roles claim)' -s protocol=openid-connect -s protocolMapper=oidc-usermodel-realm-role-mapper \
  -s 'config."claim.name"=roles' -s 'config.multivalued=true' -s 'config."jsonType.label"=String' \
  -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=true' \
  -s 'config."userinfo.token.claim"=true' -s 'config."introspection.token.claim"=true'
```

secret を後から変えるときは `docs/KEYCLOAK_CLIENT_SECRET_ROTATION.md` と同じく、Keycloak 側と `.env` の両方を揃える。

## 認証(#1803、[ADR-0012](adr/0012-extensions-tools-keycloak-auth.md))

lets_blog から extensions の Ollama・ComfyUI・PlantUML への API 呼び出しは、lets_blog の Keycloak(realm `letsblog`)のトークン
(client credentials)で認証できる。**任意で、既定は無効**。

| 項目 | 内容 |
| --- | --- |
| クライアント | `letsblog-tools-client`(専用。`letsblog-services` は `realm-management` のロールを持つので流用しない) |
| `aud` / `azp` / `iss` | `lbx-tools` / `letsblog-tools-client` / lets_blog の公開 URL + `/auth/realms/letsblog` |
| lets_blog の有効化 | `.env` の `KEYCLOAK_TOOLS_CLIENT_SECRET` を設定する。設定したのにトークンが取れなければ、理由つきのエラーになる |
| extensions の有効化 | extensions の `.env` で有効にしたときだけ検証する。別ホストの extensions は、lets_blog の公開 URL から JWKS を取る |
| 送り先 | システム設定(と `PLANTUML_BASE_URL`)の宛先だけ。プロジェクト単位の上書き先には送らない |

- **GitLab API は、アクセストークンのまま**(上の「extensions の GitLab へつなぐ」)。Keycloak のトークンにはしない。
- GitLab と Penpot は、それぞれ自分の OIDC ログインで守る(上の「extensions から lets_blog の Keycloak を OIDC の発行者として使う」の節)。proxy では守らない。
- ポータルと ComfyUI の画面は `admin` だけ。GitLab と Penpot は、ログインできる lets_blog のアカウント全員。
- ブラウザの SSO は同じ PC だけが対象。API は別ホストも対象。

関連: [EXTERNAL_OLLAMA.md](EXTERNAL_OLLAMA.md) / [EXTERNAL_COMFYUI.md](EXTERNAL_COMFYUI.md) /
[DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md)
