#!/bin/sh
set -e

# 内部限定プロビジョニングエージェント(ポート9000、lbs-net内部からのみ到達可能。nginxには公開しない)
# プラグイン/テーマzipアップロード(bulk-management/upload)がデフォルトのphp.ini上限(2M/8M)を
# 超えて$_POST/$_FILESが空になり「パラメータが不正です」になっていたため引き上げる
php -d upload_max_filesize=500M -d post_max_size=500M \
    -S 0.0.0.0:9000 -t /var/www/provision-agent /var/www/provision-agent/index.php &

exec apache2-foreground
