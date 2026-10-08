# 鮮度確認(scripts/check-image-freshness.py, #1686)が読む、ソース内容のスタンプ。
# ビルド本体とは独立したステージで、ここの COPY はビルドコンテキスト全体(ソースパス表)と
# 同じ集合にすること(単体テスト DockerfileStampStages が照合する)。最終イメージの末尾に
# 1層足すだけなので、ビルドキャッシュを壊さない。
FROM wordpress:php8.3-apache AS stamp
WORKDIR /ctx
COPY . /ctx
# 追跡されない node_modules(packages/ 配下の npm install 済み)は数えない。
RUN cd /ctx && find . -name node_modules -prune -o -type f -print | sed 's|^\./||' | LC_ALL=C sort \
    | while IFS= read -r f; do \
        printf '%s  %s\n' "$({ printf 'blob %s\0' "$(wc -c < "$f")"; cat "$f"; } | sha1sum | cut -d' ' -f1)" "$f"; \
    done | sha1sum | cut -d' ' -f1 > /lbs-source-stamp

FROM wordpress:php8.3-apache

# WP-CLI・MySQLクライアント(DB作成用)を追加する。
# 公式イメージ標準のdocker-entrypoint.sh(ルート直下への単一WordPressインストールを前提とする)は使わず、
# サブディレクトリ単位で独立したWordPressコアを都度インストールするため、
# 起動処理は start.sh(内部限定のプロビジョニングエージェント + Apache)に置き換える。
RUN apt-get update \
    && apt-get install -y --no-install-recommends default-mysql-client curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -o /usr/local/bin/wp -fsSL https://raw.githubusercontent.com/wp-cli/builds/gh-pages/phar/wp-cli.phar \
    && chmod +x /usr/local/bin/wp \
    && mkdir -p /var/www/html/sites

# mysqlコンテナは自己署名証明書のTLSを提供しており、mysql/mariadbクライアントの既定(SSL優先)では
# 「self-signed certificate in certificate chain」で接続に失敗する。runCommand経由の生mysqldump/mysql呼び出しは
# 個別に--skip-sslを渡しているが、`wp db reset`等のwp-cliのDBサブコマンドは--skip-sslを引数として
# 受け付けないため、クライアント既定設定として無効化しておく(wp-cli側は--defaultsを渡した時のみ読み込む)。
RUN printf '[client]\nskip-ssl\n' > /root/.my.cnf

# managed WordPress のアップロード上限(issue #1243)。
# このイメージは php.ini を持たず、PHP のビルトイン既定値(2M/8M)がそのまま実効値になる。
# conf.d へ置くことで Apache SAPI(実際にサイトを配信する側)にも効かせる。
# start.sh の `php -d ...` はポート9000のエージェント専用で、こちらには効かない。
COPY uploads.ini /usr/local/etc/php/conf.d/zz-uploads.ini

COPY provision-agent /var/www/provision-agent
COPY letsblog-plugin /var/www/letsblog-plugin
COPY start.sh /usr/local/bin/start.sh
RUN chmod +x /usr/local/bin/start.sh

# 鮮度確認用のソース内容スタンプ(#1686)。末尾に置き、上の層のキャッシュに影響させない。
COPY --from=stamp /lbs-source-stamp /etc/lbs-source-stamp

ENTRYPOINT ["start.sh"]
