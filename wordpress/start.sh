#!/bin/sh
set -e

# 内部限定プロビジョニングエージェント(ポート9000、lbs-net内部からのみ到達可能。nginxには公開しない)
php -S 0.0.0.0:9000 -t /var/www/provision-agent /var/www/provision-agent/index.php &

exec apache2-foreground
