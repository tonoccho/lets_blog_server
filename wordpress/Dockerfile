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

COPY provision-agent /var/www/provision-agent
COPY start.sh /usr/local/bin/start.sh
RUN chmod +x /usr/local/bin/start.sh

ENTRYPOINT ["start.sh"]
