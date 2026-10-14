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

- 参加するのは ai / media / platform だけ。既存の `lbs-net`(platform は `docker-socket-proxy-net` も)は保つ。
- ネットワーク名を変えるときは環境変数 `LBX_SHARED_NETWORK`(既定 `lbx-shared`)を設定する。
- media と platform の `PLANTUML_BASE_URL` は `http://lbx-plantuml:8080` になる。
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

extensions のコンテナは `ollama` / `comfyui` / `plantuml` という別名を持たない。LBS 自身も同梱をやめたので、
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
`docker-compose.extra-ca.yml` で、その証明書を ai / media / platform の信頼ストアへ**追加**する。

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

   重ねるのは ai / media / platform だけで、`/certificates/lbx-ca.crt` に読み取り専用でマウントし、
   temurin の `/__cacert_entrypoint.sh`(`USE_SYSTEM_CA_CERTS=1`)で JVM の cacerts へ追加する。
   公開 CA(api.openai.com など)は従来どおり通り、マウントした証明書以外の自己署名証明書は拒否される。
5. 管理画面 → システム設定の「extensions から取り込む」欄で、ポータルの URL に `https://<domain>:<port>`、経路に「別ホスト(公開 URL)」を選んで
   「取得」→「反映」を押す(`<port>` は extensions の `HTTPS_PORT`。ポータルの TLS は手順 1〜2 で信頼させた証明書で検証される)。
   手で入力する場合の代替は次のとおり:
   - `llm_ollama_base_url` = `https://ollama.<domain>:<port>/v1`
   - `comfyui_base_url` = `https://comfyui.<domain>:<port>`

   extensions の `/api/connections` の `openaiBaseUrl` は内部 URL なので使わない。`publicUrl` に `/v1` を付ける。
6. PlantUML は環境変数 `LBX_PLANTUML_URL=https://plantuml.<domain>:<port>` で media と platform に渡す
   (未設定なら従来の `http://plantuml:8080`)。

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

関連: [EXTERNAL_OLLAMA.md](EXTERNAL_OLLAMA.md) / [EXTERNAL_COMFYUI.md](EXTERNAL_COMFYUI.md) /
[DOCKER_COMPOSE_ARCHITECTURE.md](DOCKER_COMPOSE_ARCHITECTURE.md)
