# Keycloak realm定義

認証基盤([ADR-0002](../docs/adr/0002-keycloak-oidc.md))。realm設定をKeycloak管理コンソールの
GUI手作業に依存させると再構築できなくなるため、`realm-export.json` をGit管理し、
コンテナ起動時に自動import(`--import-realm`)する。**Keycloak管理コンソールでの変更は
このファイルへの反映を忘れると次回のコンテナ再作成(ボリューム削除を伴う場合)で失われる。**

## realm `letsblog` の内容

**クライアント**

| client_id | 種別 | フロー | 用途 |
|---|---|---|---|
| `letsblog-web` | confidential | Authorization Code + PKCE | Web管理画面(NextAuth) |
| `letsblog-vscode` | public | Device Authorization Grant | VSCode拡張 |
| `letsblog-services` | confidential | Client Credentials | サービス間呼び出し |

**realmロール**: `admin` / `editor` / `viewer`(細粒度のPermissionはKeycloakに載せず、
identity-serviceがDBで保持する。[#561](https://github.com/tonoccho/lets_blog_server/issues/561)参照)

**有効化済みのKeycloak標準機能**: OTP (TOTP)・パスワードリセットメール・パスワードポリシー

SMTP設定は開発環境向けに `penpot-mailcatch`(MailCatcher。認証不要、`http://localhost:1080`で
受信メールを確認可能)を向いている。本番相当の環境では別途SMTP設定を上書きする必要がある。

## 開発用クライアントシークレットについて

`letsblog-web` / `letsblog-services` の `secret` は `dev-only-...-change-me` という
明示的なプレースホルダ値になっている。これはこのリポジトリ全体がローカル開発専用の構成
(`docker-compose.yml`の`penpot-backend`が`PENPOT_DATABASE_PASSWORD: penpot`を直接埋め込んで
いるのと同様の考え方)であり、本番運用を想定していないため。本番相当の環境で使う場合は
Keycloak管理コンソール(またはAdmin REST API)でクライアントシークレットを再生成すること。

## `realm-export.json` の再生成手順

クライアント/ロール/realm設定を変更する必要がある場合、以下の手順で再生成する
(Keycloak管理コンソールでの変更をそのままエクスポートする)。

```bash
# 1. 一時的なKeycloak+PostgreSQLを起動(既存のdocker-compose環境とは別に)
docker network create kc-temp-net
docker run -d --name kc-temp-postgres --network kc-temp-net \
  -e POSTGRES_DB=keycloak -e POSTGRES_USER=keycloak -e POSTGRES_PASSWORD=keycloak \
  postgres:15
docker run -d --name kc-temp --network kc-temp-net \
  -e KC_DB=postgres -e KC_DB_URL=jdbc:postgresql://kc-temp-postgres:5432/keycloak \
  -e KC_DB_USERNAME=keycloak -e KC_DB_PASSWORD=keycloak \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin_temp_pw \
  -e KC_HOSTNAME_STRICT=false -e KC_HTTP_ENABLED=true -p 18080:8080 \
  quay.io/keycloak/keycloak:26.7.2 start-dev

# 2. 既存のrealm-export.jsonを一時コンテナへimportし、そこから作業を始める
#    (kc.sh import --dir ... で反映するか、管理コンソール http://localhost:18080/ から
#    手作業で調整する)

# 3. 変更を反映したら、DBに対して直接exportする(HTTPサーバーとは独立して動作する)
docker exec kc-temp /opt/keycloak/bin/kc.sh export --dir /tmp/kc-export --realm letsblog \
  --db postgres --db-url jdbc:postgresql://kc-temp-postgres:5432/keycloak \
  --db-username keycloak --db-password keycloak
docker cp kc-temp:/tmp/kc-export/letsblog-realm.json ./keycloak/realm-export.json

# 4. 後片付け
docker rm -f kc-temp kc-temp-postgres
docker network rm kc-temp-net
```

**エクスポート後、コミット前に必ず以下を確認・修正すること(実際に生成された秘密情報を
そのままコミットしない):**

1. `components["org.keycloak.keys.KeyProvider"]` を丸ごと削除する。ここにはrealmの
   署名/暗号化用のRSA秘密鍵・AES/HMAC鍵が平文で含まれる。Keycloakはこのcomponentが
   存在しない場合、起動時に自動的に新しい鍵を生成するため、削除しても問題なく動作する
   (`docker exec <container> curl .../protocol/openid-connect/certs` で鍵が
   自動生成されることを確認できる)。
2. `letsblog-web` / `letsblog-services` の `secret` フィールドを、上記の
   「開発用クライアントシークレットについて」に記載した固定プレースホルダ値に
   戻す(実際に生成されたランダムなシークレットのままコミットしない)。

## 動作確認

```bash
docker compose up -d keycloak
docker compose logs keycloak | grep -i realm   # "Realm 'letsblog' imported" または
                                                # "already exists. Import skipped" が出ることを確認

curl -sk https://localhost/auth/realms/letsblog/.well-known/openid-configuration | jq .issuer
# => "https://localhost/auth/realms/letsblog"
```
