# OpenAPI クライアント生成

`packages/api-client`(`@lets-blog/api-client`)の TypeScript クライアントは、各サービスが公開する
OpenAPI spec から orval で生成する。この手順は `scripts/generate-api-client.sh` と
`config/orval.config.js` が参照する一次資料である(issue #555 / #739 / #811)。

## 全体の流れ

```
各サービス (springdoc) ──/v3/api-docs──▶ openapi/<service>.json ──orval──▶ packages/api-client/src/generated/
```

`scripts/generate-api-client.sh` が上の2ステップをまとめて実行する。

```bash
# 開発スタックを起動しておく(必須。下記「前提」参照)
docker compose up -d

# spec の取得 → orval によるクライアント生成
./scripts/generate-api-client.sh
```

## 前提

### 1. サービスが起動していること

spec は**実行中のサービスから取得する**。静的なファイルではないため、対象サービスが
起動していなければ取得できない。スクリプトは30回×2秒でリトライしたのち失敗する。

### 2. gateway 経由では取得できない

**gateway は対象外**である。理由は2つ:

- gateway 自身はコントローラを持たず、springdoc も導入していない
  (`services/gateway/build.gradle` に springdoc 依存が無い)
- gateway のルート表(`services/gateway/src/main/resources/application.yml`)は
  `/v3/api-docs` を下流サービスへルーティングしない

したがって「gateway に向けて実行すれば全サービス分が取れる」ということはない。
各サービスから個別に取得する必要がある。

### 3. サービスはホストへポートを公開していない

`docker-compose.yml` では、どのアプリケーションサービスも `ports:` を持たない
(ホストへ出ているのは reverse-proxy のみ)。そのため
`curl http://localhost:8080/v3/api-docs` はホストからは通らない。

スクリプトは既定で **`docker exec <コンテナ名> curl http://localhost:8080/v3/api-docs`** を使い、
コンテナ内から取得する。開発スタックが起動していればそのまま動く。

### 4. 必要なコマンド

`curl` / `jq` / `docker` / `npx`(Node.js)。

### 5. orval のバージョンは固定されている

スクリプトはリポジトリルートで `npx` を呼ぶが、ルートには `package.json` も `node_modules` も
無い。素の `npx orval` は**毎回レジストリの latest を取りに行く**ため、生成物をコミットしている
以上「実行した日によって差分が出る」ことになる。

そこでスクリプトは `ORVAL_VERSION`(現在 **8.27.0**)で CLI を固定し、
`npx --yes "orval@${ORVAL_VERSION}"` として呼ぶ(#1006)。

- `apps/web/package.json` の `@orval/core` / `@orval/fetch` も同じ 8.27.0 を**厳密指定**する。
  この一致は `apps/web/dependency-advisories.test.ts` が検査している
- 上げるときはスクリプトと `apps/web/package.json` を**同時に**上げ、生成物の差分をレビューする

## 取得先の指定

| サービス | コンテナ名 | URL 上書き用の環境変数 |
|---|---|---|
| log-writer | `lbs-log-writer` | `LOG_WRITER_URL` |
| media | `lbs-media` | `MEDIA_SERVICE_URL` |
| ai | `lbs-ai` | `AI_SERVICE_URL` |
| content | `lbs-content` | `CONTENT_SERVICE_URL` |
| analytics | `lbs-analytics` | `ANALYTICS_SERVICE_URL` |
| project | `lbs-project` | `PROJECT_SERVICE_URL` |
| publishing | `lbs-publishing` | `PUBLISHING_SERVICE_URL` |
| platform | `lbs-platform` | `PLATFORM_SERVICE_URL` |
| identity | `lbs-identity` | `IDENTITY_SERVICE_URL` |

環境変数を設定した場合は、`docker exec` ではなく**その URL に対してホストから直接** `curl` する
(ベースURLのみを指定する。`/v3/api-docs` はスクリプトが付ける)。ポートを公開している、
リモートで動かしている、といった場合に使う。

```bash
# 例: ai だけ別ホストの spec を使う
AI_SERVICE_URL=http://192.0.2.10:8080 ./scripts/generate-api-client.sh
```

> **注意**: 全サービスに同じ URL を設定すると、同じ spec が10回別名で保存される。
> これは以前**エラーにならずに通ってしまう**失敗モードだった(#811)。現在はスクリプトが
> 取得後に内容の重複を検査し、複数サービスが同一の spec を返した場合は失敗する。

## servers の正規化

springdoc が返す `servers[0].url` は**リクエストのホストから生成される**ため、
どこから取得したかによって `http://ai:8080` になったり `http://localhost:8080` になったりする。

生成コードは orval の `output.baseUrl` を使うため**動作には影響しない**が、再生成のたびに
無意味な差分が出てレビューのノイズになる。そこでスクリプトは保存前に
`servers[].url` を **`http://localhost:8080`** へ固定する(`description` 等の他のキーは保つ)。

この値自体に意味は無い。「取得経路によらず同じ値にする」ことだけが目的である。

## コミットするもの

| パス | コミットする | 備考 |
|---|---|---|
| `openapi/*.json` | **する** | 10サービス分。生成の入力であり、差分レビューの対象 |
| `packages/api-client/src/generated/**` | **する** | 生成物だが、利用側がビルドなしで使えるようコミットしている |
| `packages/api-client/src/index.ts` | **する**(手書き) | 生成物を再エクスポートする。**orval は上書きしない**ので、手当ては保たれる(#809) |

`packages/api-client/src/index.ts` は生成対象外である(`config/orval.config.js` の全ターゲットの
`output.target` は `packages/api-client/src/generated/<service>` 配下)。サービスを追加したら
このファイルへ再エクスポートを手で足す必要がある。名前衝突の解消方針は同ファイルの
コメントを参照。

## サービスを追加するとき

1. `scripts/generate-api-client.sh` の `SERVICES` に
   `"<service>|<container>|${<SERVICE>_URL:-}"` を1行足す
2. `config/orval.config.js` に対応するターゲット定義を足す
3. `packages/api-client/src/index.ts` に再エクスポートを足す(内部ブリッジ `/api/internal/**` は除く)
4. 本ドキュメントの表を更新する

## トラブルシュート

**`Failed to fetch OpenAPI spec after 30 attempts`**
対象サービスが起動していないか、springdoc が応答していない。
`docker compose ps` で状態を確認し、`docker logs lbs-<service>` を見る。

**`複数のサービスが同一の spec を返しました`**
環境変数の設定ミスで、複数サービスの取得先が同じサービスを指している。
上の表と実際に設定した環境変数を突き合わせる。

**`npx orval` が `ETARGET  No matching version found for @orval/...` で失敗する**
orval は多数の `@orval/*` サブパッケージへ**厳密なバージョン指定**で依存しており、その一部が
未公開のまま新バージョンが公開されることがある(2026-09-03 の 8.28.0 が実例)。
スクリプトはバージョンを固定しているのでこの経路では起きないが、`ORVAL_VERSION` を
上げるときは `npm view orval@<version> dependencies` の各サブパッケージが公開済みか確かめる。

**`openapi/*.json` に大きな差分が出る**
実装の変更が反映された正しい差分か、取得経路の違いによるノイズかを確認する。
`servers` はスクリプトが正規化するのでノイズにはならない。それ以外で
`content` のメディアタイプ(`application/json` / `*/*`)や `operationId` の有無が
まとめて変わる場合は、コミット済みの spec が古い springdoc 設定で生成されている可能性がある。
