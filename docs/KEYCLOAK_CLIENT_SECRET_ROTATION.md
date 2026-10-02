# Keycloak クライアントシークレットのローテーション(#1551)

対象は `.env` の次の2つ。

| キー | Keycloak のクライアント | 使うサービス |
| --- | --- | --- |
| `KEYCLOAK_SERVICES_CLIENT_SECRET` | `letsblog-services` | identity / media / platform / publishing |
| `KEYCLOAK_WEB_CLIENT_SECRET` | `letsblog-web` | web |

## 背景

リポジトリの既定値(`dev-only-letsblog-*-secret-change-me`)は公開されており、誰でも知り得る。

- **新規構築**: `setup.sh` が2つをランダム値で生成する。`docker-compose.yml` が同じ値を keycloak
  コンテナに渡し、`infra/keycloak/realm-export.json` の `${VAR:既定値}` が realm import 時にそれを
  受け取るので、`.env` と realm は一致する。変更は不要。
- **既存環境**: `--import-realm` は既に存在する realm を上書きしない。`.env` だけを書き換えると
  Keycloak 側の secret と食い違い、`invalid_client` で web のログインとサービス間呼び出しが失敗する。
  以下の手順で **Keycloak と `.env` を両方** 揃える。
- 既定値のままかどうかは `bash scripts/check-env.sh` が警告する(`△` 行に2つのキー名が出る)。

## 手順

順序が重要。Keycloak 側を先に変え、`.env` を更新して、使うサービスを再起動する。
切り替えの間(数分)はログインとサービス間呼び出しが失敗する。

1. 新しい値を2つ生成する。

   ```bash
   openssl rand -hex 32   # KEYCLOAK_SERVICES_CLIENT_SECRET 用
   openssl rand -hex 32   # KEYCLOAK_WEB_CLIENT_SECRET 用
   ```

2. Keycloak 側のクライアント secret を、**生成した値を指定して**更新する(`kcadm.sh` は
   管理者資格情報で認証する。ここでは `.env` の `KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD`)。

   ```bash
   docker exec -it lbs-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
     --server http://localhost:8080/auth --realm master --user "<KEYCLOAK_ADMIN_USERNAME>"
   for c in letsblog-services letsblog-web; do
     id=$(docker exec lbs-keycloak /opt/keycloak/bin/kcadm.sh get clients -r letsblog \
           -q clientId=$c --fields id --format csv --noquotes)
     echo "$c = $id"
   done
   docker exec lbs-keycloak /opt/keycloak/bin/kcadm.sh update clients/<letsblog-services の id> \
     -r letsblog -s 'secret=<手順1の1つ目>'
   docker exec lbs-keycloak /opt/keycloak/bin/kcadm.sh update clients/<letsblog-web の id> \
     -r letsblog -s 'secret=<手順1の2つ目>'
   ```

   管理コンソール(`https://localhost/auth/` → realm `letsblog` → Clients → 該当クライアント →
   Credentials → Regenerate)で再生成してもよい。その場合は表示された値を手順3で `.env` に写す。

3. `.env` の2つの値を同じ値に更新する(権限は `600` のまま)。

4. 使うサービスを再作成する。`restart` ではなく `up -d` を使う(環境変数は再作成で反映される)。

   ```bash
   docker compose up -d web identity media platform publishing
   ```

   `keycloak` コンテナは再起動しなくてよい(secret は DB 上の realm に保存済みで、環境変数は
   realm 未作成の初回 import でしか読まれない)。ただし `.env` と揃えておけば、
   次に keycloak コンテナを再作成しても食い違わない。

5. 確認する。

   ```bash
   bash scripts/check-env.sh                  # 既定値の警告が消えている
   docker compose ps                          # 5サービスが healthy
   ```

   さらに web でログインできること、media → identity の呼び出し(画像生成など)が
   成功することを確認する。

## 失敗したとき

`invalid_client` / `unauthorized_client` は `.env` と Keycloak の secret の食い違いである。
Keycloak 側の Credentials タブの値と `.env` を突き合わせ、手順2か3をやり直す。
元の値に戻す場合は、控えておいた旧値で手順2・3を行う。

## 関連

- `setup.sh`(`generate_env_file`)・`scripts/check-env.sh`・`docker-compose.yml`(`keycloak` の `environment`)
- `infra/keycloak/realm-export.json`(`secret` の `${VAR:既定値}`)
