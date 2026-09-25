#!/bin/bash
# issue #762: ホストから ./gradlew test を回す前に、テストの接続先MySQLが揃っているか確認する。
#
# 背景: 各サービスの src/test/resources/application-test.yml は接続先を
# jdbc:mysql://localhost:3306/... に固定している(ADR-0006: Testcontainersは使わず実MySQLを使う)。
# 前提が崩れていると、テストは中身と無関係な理由で大量に落ちる。
#
#   - ポートに何もいない → FlywaySqlUnableToConnectToDbException / ConnectException
#     (docker-compose.yml の mysql はホストにポートを公開していない)
#   - スキーマが無い → Unknown database 'lbs_project_test'
#     (infra/mysql/init/*.sh はデータボリュームが空のときしか走らないため、
#      スキーマが増えた既存環境では作られない)
#
# どちらも「環境要因の失敗」であり、本物の失敗を埋もれさせる。先に切り分ける。
#
# 注意: docker-compose.host-tests.yml が公開するのは 127.0.0.1(IPv4)だけ。
# localhost が ::1 にも解決され、JVMがIPv6を優先する環境では、ポートを公開していても
# jdbc:mysql://localhost:3306 が繋がらないことがある。その場合は接続先を
# 127.0.0.1 に固定するか、オーバーライド側で ::1 も公開する。
# 既定では TEST_DB_HOST=127.0.0.1 を見るので、このスクリプト自身はIPv6の影響を受けない。
#
# 使い方:
#   bash scripts/check-test-db.sh
#
# 終了コード: 0 = 揃っている / 1 = 揃っていない

set -uo pipefail

HOST="${TEST_DB_HOST:-127.0.0.1}"
PORT="${TEST_DB_PORT:-3306}"
USER="${TEST_DB_USER:-test_user}"
PASSWORD="${TEST_DB_PASSWORD:-test_pass}"

# application-test.yml が参照するスキーマの一覧。増減したらここも直すこと。
REQUIRED_SCHEMAS=(
    lbs_identity_test
    lbs_project_test
    lbs_content_test
    lbs_media_test
    lbs_ai_test
    lbs_analytics_test
    lbs_publishing_test
    lbs_platform_test
    lbs_log_test
)

remedy() {
    echo
    echo "  対処:"
    echo "    A) コンテナの中でテストを回す(接続先が自動で用意される。最も簡単)"
    echo "         bin/loop test api"
    echo
    echo "    B) ホストから ./gradlew で回す場合は、先にMySQLを 127.0.0.1:3306 へ公開する"
    echo "         docker compose -f docker-compose.yml -f docker-compose.host-tests.yml up -d mysql"
    if [ "$PORT" != "3306" ]; then
        echo "       ※ TEST_DB_PORT=${PORT} を指定しているが、上のオーバーライドが公開するのは 3306。"
        echo "         別ポートを使うなら docker-compose.host-tests.yml も合わせて直すこと。"
    fi
    echo
    echo "    スキーマだけが足りない場合は、初期化スクリプトを手動で再実行する(冪等):"
    echo "         docker compose exec mysql bash /docker-entrypoint-initdb.d/02-create-test-schemas.sh"
    echo
    echo "    詳細は docs/TEST_DOCUMENTATION.md の「テスト用MySQLの前提」を参照。"
}

echo "接続先: ${USER}@${HOST}:${PORT}"

# 1. ポートに何かいるか。mysqlクライアントが無い環境でもここまでは切り分けられる。
if ! timeout 5 bash -c "exec 3<>/dev/tcp/${HOST}/${PORT}" 2>/dev/null; then
    echo "✗ ${HOST}:${PORT} に接続できません。"
    echo "  この状態でテストを実行すると、DB依存テストが軒並み"
    echo "  FlywaySqlUnableToConnectToDbException / ConnectException で落ちます。"
    remedy
    exit 1
fi
echo "✓ ${HOST}:${PORT} に到達できます"

# 2. 資格情報とスキーマ。mysqlクライアントが無ければここは確認できないので、その旨を言う。
if ! command -v mysql >/dev/null 2>&1; then
    echo "△ mysqlクライアントが無いため、資格情報とスキーマの確認はスキップします。"
    echo "  コンテナ側から確認する場合:"
    echo "    docker compose exec mysql mysql -u${USER} -p${PASSWORD} -e 'SHOW DATABASES;'"
    exit 0
fi

if ! existing="$(mysql -h "$HOST" -P "$PORT" -u "$USER" -p"$PASSWORD" -N -B \
        -e 'SHOW DATABASES;' 2>/dev/null)"; then
    echo "✗ ${USER} でログインできません(パスワード違い、または別のMySQLが同じポートを使っています)。"
    echo "  127.0.0.1:${PORT} を使うコンテナが複数あると起こります(例: 開発スタックの mysql と"
    echo "  loop-engineering の lbs-test-db)。どちらか一方だけを起動してください。"
    remedy
    exit 1
fi
echo "✓ ${USER} でログインできます"

missing=()
for schema in "${REQUIRED_SCHEMAS[@]}"; do
    if ! grep -qxF "$schema" <<<"$existing"; then
        missing+=("$schema")
    fi
done

if [ "${#missing[@]}" -gt 0 ]; then
    echo "✗ 次のテスト用スキーマがありません:"
    printf '    %s\n' "${missing[@]}"
    echo "  この状態では該当サービスのテストが Unknown database で落ちます。"
    remedy
    exit 1
fi

echo "✓ テスト用スキーマ ${#REQUIRED_SCHEMAS[@]} 件がすべて存在します"
echo "✓ 前提は揃っています"
