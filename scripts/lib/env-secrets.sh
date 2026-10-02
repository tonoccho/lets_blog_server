# .env.example の値から「自動生成してよい秘密値か」を判定し、値を生成する共有ライブラリ
# (issue #960 / #1254)。`source` して使う。setup.sh(初回の .env 生成)と
# update.sh(--fill-secrets)が同じ分類を使うための単一の定義で、キー名の一覧は持たない。
#
# 分類は .env.example 側の値そのものが契約:
#   - 値が "changeme_" で始まる  -> 何でもよい内部秘密値。生成してよい
#   - 値が空                    -> 利用者しか知り得ない外部の値(APIキー等)。生成しない
#   - それ以外                  -> 変更不要な既定値。生成しない
#
# 例外: APP_ENCRYPTION_KEY は "changeme_" ではなく、CredentialCipher(packages/lbs-common)が
# strict Base64として要求するダミーの有効値を既定にしている(check-env.shの特別扱いと同じ理由)。
# 例外: KEYCLOAK_SERVICES_CLIENT_SECRET / KEYCLOAK_WEB_CLIENT_SECRET は "dev-only-" で始まる
# 公開済みの既定値を持つ。ランダム値へ置き換える(#1551)。
APP_ENCRYPTION_KEY_PLACEHOLDER="UkVQTEFDRV9XSVRIX09QRU5TU0xfUkFORF9CNjRfMzI="

generate_value_for_key() {
  local key="$1"
  case "$key" in
    APP_ENCRYPTION_KEY) openssl rand -base64 32 ;;
    NEXTAUTH_SECRET) openssl rand -hex 32 ;;
    PENPOT_SECRET_KEY) openssl rand -base64 64 | tr -d '\n' ;;
    WP_PROVISION_TOKEN) openssl rand -hex 32 ;;
    KEYCLOAK_SERVICES_CLIENT_SECRET|KEYCLOAK_WEB_CLIENT_SECRET) openssl rand -hex 32 ;;
    *) openssl rand -base64 24 ;;
  esac
}

# .env.example の「キーと値」から、自動生成してよい秘密値なら 0、そうでなければ 1 を返す。
is_auto_generatable_secret() {
  local key="$1" value="$2"
  if [[ "$value" == changeme_* ]]; then
    return 0
  elif [ "$key" = "APP_ENCRYPTION_KEY" ] && [ "$value" = "$APP_ENCRYPTION_KEY_PLACEHOLDER" ]; then
    return 0
  elif [[ "$key" =~ ^KEYCLOAK_(SERVICES|WEB)_CLIENT_SECRET$ ]] && [[ "$value" == dev-only-* ]]; then
    return 0
  fi
  return 1
}
