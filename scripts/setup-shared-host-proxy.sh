#!/bin/bash
# issue #1038: ホストの 80/443 を占有している別スタックのリバースプロキシ(infra-proxy)へ
# https://localhost の vhost を配置し、lets_blog_server の reverse-proxy へ中継させる。
#
# なぜ必要か:
#
# 受け入れテストの baseURL は https://localhost にハードコードされている
# (Keycloak の redirect_uri が固定登録されており実行時に差し替えられない)。
# ところがこの開発機では GitLab を提供する infra-proxy が先に 0.0.0.0:80 と 0.0.0.0:443 を
# 占有しており、reverse-proxy はポート公開に失敗する。**失敗はそこで止まらない。**
# Docker はネットワーク接続時にポート公開を行うため、公開に失敗したコンテナは
# どのネットワークにも所属しないまま running になり、web にも gateway にも到達できない。
#
# localhost も server.tonoccho.local も /etc/hosts でどちらも 127.0.0.1 に解決されるため、
# IP で分離することはできない。分離できる軸は Host ヘッダ / SNI だけであり、それは
# nginx の vhost が担う役割である。よって共存させる(採らなかった案は
# docs/ACCEPTANCE_TESTING.md に記録した)。
#
# 適用(既定)と点検(--check)を1つのスクリプトに置くのは、直し方と気づき方が同じ場所に
# あるべきだから。**点検は決して書き換えない** — 黙って直してしまうと「未適用だった」
# という事実そのものが観測できなくなる(scripts/setup-git-hooks.sh と同じ思想。#1039)。
#
# 危険性:
#
# 書き込み先は **GitLab を提供している nginx** の設定ディレクトリである。壊すと GitLab が
# 止まり、glab に依存する開発ワークフローごと進行不能になる。そのため:
#
#   - reload の前に必ず `docker exec <proxy> nginx -t` を通す
#   - nginx -t が落ちたら **reload せず、置いたファイルを撤去して**異常終了する
#   - 各ステップの前後で GitLab の生存を確認する
#
# 使い方:
#   bash scripts/setup-shared-host-proxy.sh            # 適用する(冪等)
#   bash scripts/setup-shared-host-proxy.sh --check    # 適用済みか点検する(書き換えない)
#
# 環境変数(既定値):
#   INFRA_DIR         /home/seiji/src/infra           配置先スタックのディレクトリ
#   PROXY_CONTAINER   infra-proxy                     配置先の nginx コンテナ名
#   LBS_NETWORK       <composeプロジェクト>_lbs-net   接続する docker ネットワーク
#   DOCKER_BIN        docker                          docker CLI
#   GITLAB_HEALTH_URL https://server.tonoccho.local/gitlab/  空にすると生存確認を省く
#   LBS_BASE_URL      https://localhost               空にすると到達確認を省く
#
# 終了コード: 0 = 適用済み / 1 = 未適用・適用に失敗 / 2 = 使い方や環境の誤り

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VHOST_NAME="20-localhost.conf"
VHOST_SRC="$REPO_ROOT/infra/shared-host/$VHOST_NAME"
CERT_SRC="$REPO_ROOT/certs/localhost.crt"
KEY_SRC="$REPO_ROOT/certs/localhost.key"

# issue #1043: GitLab 自身の vhost。上流(gitlab:80)の起動時解決を変数 + resolver に
# 直したもの。20-localhost.conf と同じ配置元・同じ検証手順で反映する。
GITLAB_VHOST_NAME="10-server.tonoccho.local.conf"
GITLAB_VHOST_SRC="$REPO_ROOT/infra/shared-host/$GITLAB_VHOST_NAME"

INFRA_DIR="${INFRA_DIR:-/home/seiji/src/infra}"
PROXY_CONTAINER="${PROXY_CONTAINER:-infra-proxy}"
# shellcheck source=lib/compose-project.sh
source "$REPO_ROOT/scripts/lib/compose-project.sh"
LBS_NETWORK="${LBS_NETWORK:-$(resolve_compose_project "$REPO_ROOT")_lbs-net}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
GITLAB_HEALTH_URL="${GITLAB_HEALTH_URL-https://server.tonoccho.local/gitlab/}"
LBS_BASE_URL="${LBS_BASE_URL-https://localhost}"

CONF_DST_DIR="$INFRA_DIR/proxy/conf.d"
CERT_DST_DIR="$INFRA_DIR/proxy/certs"
VHOST_DST="$CONF_DST_DIR/$VHOST_NAME"
CERT_DST="$CERT_DST_DIR/localhost.crt"
KEY_DST="$CERT_DST_DIR/localhost.key"
GITLAB_VHOST_DST="$CONF_DST_DIR/$GITLAB_VHOST_NAME"

MODE="apply"
case "${1:-}" in
    "") ;;
    --check) MODE="check" ;;
    -h|--help)
        sed -n '/^# 使い方:/,/^# 終了コード:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        echo "エラー: 不明なオプション: $1" >&2
        echo "  使い方: bash scripts/setup-shared-host-proxy.sh [--check]" >&2
        exit 2
        ;;
esac

# ---------------------------------------------------------------- 補助

# HTTP のステータスコードだけを返す。到達できなければ 000。
# curl が無い環境では busybox wget で代用し、どちらも無ければ空文字を返す
# (呼び出し側が「確認できなかった」と区別できるようにする)。
http_code() {
    local url="$1"
    if command -v curl >/dev/null 2>&1; then
        curl -sk -o /dev/null -w '%{http_code}' --max-time 10 "$url" 2>/dev/null
        return
    fi
    if command -v wget >/dev/null 2>&1; then
        if wget -q -O /dev/null --no-check-certificate --timeout=10 "$url" 2>/dev/null; then
            echo "200"
        else
            echo "000"
        fi
        return
    fi
    echo ""
}

is_2xx_or_3xx() {
    case "$1" in
        2??|3??) return 0 ;;
        *) return 1 ;;
    esac
}

# GitLab が生きているかを見る。各ステップの前後で呼ぶ。
# 「壊したかどうか」を後から推測せずに済むよう、変化した瞬間に落とす。
assert_gitlab_alive() {
    local when="$1"
    [ -n "$GITLAB_HEALTH_URL" ] || return 0
    local code
    code="$(http_code "$GITLAB_HEALTH_URL")"
    if [ -z "$code" ]; then
        echo "△ curl も wget も無いため GitLab の生存確認を省きました($when)"
        return 0
    fi
    if is_2xx_or_3xx "$code"; then
        echo "  ✓ GitLab 生存確認 ($when): $GITLAB_HEALTH_URL -> $code"
        return 0
    fi
    echo "✗ GitLab へ到達できません ($when): $GITLAB_HEALTH_URL -> $code" >&2
    return 1
}

# 撤去(rollback)したあとの "復旧 reload" を実行し、その成否と GitLab の生存を確認して
# 記録する(issue #1038 / #1044)。呼び出し元(reload 自体が失敗した分岐・適用後に
# GitLab が死んだ分岐のどちらも)は、置いたファイルを撤去した**その直後**であり、
# infra-proxy が元の設定を読み直せたのか、GitLab が実際に戻ったのかが分からない。
# 確認せずに exit 1 すると、呼び出し元には「撤去した」以上のことが伝わらない。
report_recovery() {
    local reload_failed=0
    if "$DOCKER_BIN" exec "$PROXY_CONTAINER" nginx -s reload >/dev/null 2>&1; then
        echo "  ✓ 復旧の reload に成功しました(元の設定に戻りました)" >&2
    else
        echo "✗ 復旧の reload にも失敗しました。" >&2
        reload_failed=1
    fi
    local gitlab_ok=1
    if ! assert_gitlab_alive "復旧後"; then
        gitlab_ok=0
    fi
    # reload 自体が失敗した場合はもちろん、reload には成功しても GitLab が戻って
    # 来ない場合(issue #1044)にも、確認せずに exit 1 すると呼び出し元には
    # 「失敗した」以上のことが伝わらない。どちらか一方でも異常なら手順を示す。
    if [ "$reload_failed" -eq 1 ] || [ "$gitlab_ok" -eq 0 ]; then
        if [ "$gitlab_ok" -eq 0 ]; then
            echo "  → GitLab が停止しています。手動で確認してください:" >&2
        else
            echo "  → 復旧 reload には失敗しましたが GitLab には到達できています。念のため手動で確認してください:" >&2
        fi
        echo "    $DOCKER_BIN exec $PROXY_CONTAINER nginx -t" >&2
        echo "    $DOCKER_BIN restart $PROXY_CONTAINER" >&2
    fi
}

docker_available() {
    command -v "$DOCKER_BIN" >/dev/null 2>&1 || [ -x "$DOCKER_BIN" ]
}

network_has_proxy() {
    "$DOCKER_BIN" network inspect "$LBS_NETWORK" \
        --format '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null \
        | tr ' ' '\n' | grep -qx "$PROXY_CONTAINER"
}

same_file() {
    [ -f "$1" ] && [ -f "$2" ] && cmp -s "$1" "$2"
}

# ---------------------------------------------------------------- 事前条件

for f in "$VHOST_SRC" "$GITLAB_VHOST_SRC" "$CERT_SRC" "$KEY_SRC"; do
    if [ ! -f "$f" ]; then
        echo "エラー: $f が見つかりません。" >&2
        if [ "$f" != "$VHOST_SRC" ]; then
            echo "  証明書は bash scripts/generate-certs.sh で作成できます。" >&2
        fi
        exit 2
    fi
done

if [ ! -d "$CONF_DST_DIR" ] || [ ! -d "$CERT_DST_DIR" ]; then
    echo "エラー: 配置先が見つかりません: $CONF_DST_DIR / $CERT_DST_DIR" >&2
    echo "  INFRA_DIR で別の場所を指定できます(現在: $INFRA_DIR)。" >&2
    exit 2
fi

if ! docker_available; then
    echo "エラー: docker CLI ($DOCKER_BIN) が使えません。" >&2
    exit 2
fi

# ---------------------------------------------------------------- 点検モード

if [ "$MODE" = "check" ]; then
    status=0

    if same_file "$VHOST_SRC" "$VHOST_DST"; then
        echo "✓ vhost が配置されています ($VHOST_DST)"
    elif [ -f "$VHOST_DST" ]; then
        status=1
        echo "✗ 配置済みの vhost がリポジトリの内容と一致しません ($VHOST_DST)"
    else
        status=1
        echo "✗ vhost が配置されていません ($VHOST_DST)"
    fi

    if same_file "$GITLAB_VHOST_SRC" "$GITLAB_VHOST_DST"; then
        echo "✓ GitLab vhost が配置されています ($GITLAB_VHOST_DST)"
    elif [ -f "$GITLAB_VHOST_DST" ]; then
        status=1
        echo "✗ 配置済みの GitLab vhost がリポジトリの内容と一致しません ($GITLAB_VHOST_DST)"
    else
        status=1
        echo "✗ GitLab vhost が配置されていません ($GITLAB_VHOST_DST)"
    fi

    for pair in "$CERT_SRC:$CERT_DST" "$KEY_SRC:$KEY_DST"; do
        src="${pair%%:*}"; dst="${pair##*:}"
        if same_file "$src" "$dst"; then
            echo "✓ 証明書が配置されています ($dst)"
        else
            status=1
            echo "✗ 証明書が配置されていない、または内容が違います ($dst)"
        fi
    done

    if network_has_proxy; then
        echo "✓ $PROXY_CONTAINER は $LBS_NETWORK に接続されています"
    else
        status=1
        echo "✗ $PROXY_CONTAINER が $LBS_NETWORK に接続されていません"
        echo "    → 接続が無いと vhost は lbs-reverse-proxy を名前解決できません。"
    fi

    if [ -n "$LBS_BASE_URL" ]; then
        code="$(http_code "$LBS_BASE_URL/")"
        if [ -z "$code" ]; then
            echo "△ curl も wget も無いため $LBS_BASE_URL への到達確認を省きました"
        elif is_2xx_or_3xx "$code"; then
            echo "✓ $LBS_BASE_URL/ へ到達できます (http_code=$code)"
        else
            status=1
            echo "✗ $LBS_BASE_URL/ へ到達できません (http_code=$code)"
        fi
    fi

    if [ "$status" -ne 0 ]; then
        echo
        echo "  適用: bash scripts/setup-shared-host-proxy.sh"
        echo "  背景と手順: docs/ACCEPTANCE_TESTING.md"
    fi
    exit "$status"
fi

# ---------------------------------------------------------------- 適用モード

echo "共有ホスト構成を適用します(#1038)"
echo "  配置先スタック : $INFRA_DIR"
echo "  プロキシ       : $PROXY_CONTAINER"
echo "  ネットワーク   : $LBS_NETWORK"
echo

if ! assert_gitlab_alive "適用前"; then
    echo "  → 先に GitLab を復旧させてください。壊れた状態からの適用は原因を見分けられません。" >&2
    exit 1
fi

# 撤去のために「自分が作ったファイル」と「上書きしたファイルの退避先」を覚えておく。
CREATED=()
BACKUP_DIR="$(mktemp -d)"
BACKED_UP=()

cleanup_backup_dir() {
    rm -rf "$BACKUP_DIR"
}
trap cleanup_backup_dir EXIT

place() {
    local src="$1" dst="$2"
    if same_file "$src" "$dst"; then
        echo "  = 変更なし: $dst"
        return 0
    fi
    if [ -f "$dst" ]; then
        cp -p "$dst" "$BACKUP_DIR/$(basename "$dst")" || return 1
        BACKED_UP+=("$dst")
        echo "  ↻ 上書き: $dst(退避済み)"
    else
        CREATED+=("$dst")
        echo "  + 配置: $dst"
    fi
    cp "$src" "$dst"
}

# nginx -t が落ちたときに、置いたものを無かったことにする。
# 撤去しないと、infra-proxy が次に再起動した瞬間に壊れた設定を読み、GitLab が上がらない。
# その時点では誰もこのスクリプトのことを覚えていない。
rollback() {
    echo "  ⟲ 配置を撤去します" >&2
    local f
    for f in "${CREATED[@]:-}"; do
        [ -n "$f" ] && rm -f "$f"
    done
    for f in "${BACKED_UP[@]:-}"; do
        [ -n "$f" ] && cp -p "$BACKUP_DIR/$(basename "$f")" "$f"
    done
}

echo "1) 証明書と vhost を配置します"
if ! place "$CERT_SRC" "$CERT_DST" \
    || ! place "$KEY_SRC" "$KEY_DST" \
    || ! place "$VHOST_SRC" "$VHOST_DST" \
    || ! place "$GITLAB_VHOST_SRC" "$GITLAB_VHOST_DST"; then
    echo "✗ ファイルの配置に失敗しました。" >&2
    rollback
    exit 1
fi

echo "2) $PROXY_CONTAINER を $LBS_NETWORK へ接続します"
if network_has_proxy; then
    echo "  = 接続済み"
else
    if ! "$DOCKER_BIN" network connect "$LBS_NETWORK" "$PROXY_CONTAINER"; then
        echo "✗ ネットワークへ接続できませんでした($LBS_NETWORK)。" >&2
        echo "  lets_blog_server スタックが起動しているか確認してください:" >&2
        echo "    docker network ls | grep lbs-net" >&2
        rollback
        exit 1
    fi
    echo "  + 接続しました"
fi

# **reload の前に必ず検証する。** ここを飛ばすと設定ミスがそのまま GitLab の停止になる。
echo "3) 設定を検証します(nginx -t)"
if ! "$DOCKER_BIN" exec "$PROXY_CONTAINER" nginx -t; then
    echo "✗ nginx -t が失敗しました。reload せずに撤去します(GitLab を落とさないため)。" >&2
    rollback
    exit 1
fi

echo "4) 設定を反映します(nginx -s reload)"
if ! "$DOCKER_BIN" exec "$PROXY_CONTAINER" nginx -s reload; then
    echo "✗ reload に失敗しました。撤去して元の設定に戻します。" >&2
    rollback
    # ここへ来る可能性は低い(nginx -t を通過済み)。しかし来たときこそ infra-proxy は
    # 新しい設定を読み込みかけて失敗した直後であり、**GitLab が生きているか分からない**。
    report_recovery
    exit 1
fi

if ! assert_gitlab_alive "適用後"; then
    echo "✗ 適用後に GitLab へ到達できなくなりました。撤去して元に戻します。" >&2
    rollback
    # ここは「GitLab が死んだ」と検出した**その場所**である。撤去はしたが、それが
    # 実際に GitLab を復旧させたのかを確認せずに exit 1 すると、呼び出し元には
    # 「撤去した」以上のことが伝わらない(issue #1044)。
    report_recovery
    exit 1
fi

if [ -n "$LBS_BASE_URL" ]; then
    code="$(http_code "$LBS_BASE_URL/")"
    if [ -z "$code" ]; then
        echo "△ curl も wget も無いため $LBS_BASE_URL への到達確認を省きました"
    elif is_2xx_or_3xx "$code"; then
        echo "  ✓ $LBS_BASE_URL/ へ到達できます (http_code=$code)"
    else
        echo "✗ $LBS_BASE_URL/ へ到達できません (http_code=$code)" >&2
        echo "  infra-proxy 側は生きています。lets_blog_server 側を確認してください:" >&2
        echo "    docker compose -f docker-compose.yml -f docker-compose.shared-host.yml up -d reverse-proxy" >&2
        echo "    docker inspect lbs-reverse-proxy --format '{{json .NetworkSettings.Networks}}'" >&2
        exit 1
    fi
fi

echo
echo "✓ 適用しました。受け入れテストを実行できます。"
echo "  スタックはポート公開なしで起動してください(80/443 は $PROXY_CONTAINER のもの):"
echo "    docker compose -f docker-compose.yml -f docker-compose.shared-host.yml up -d"
echo "  点検: bash scripts/setup-shared-host-proxy.sh --check"
