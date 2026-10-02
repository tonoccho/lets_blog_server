<?php
/**
 * Plugin Name: Lets Blog
 * Description: Lets Blog とやり取りするためのプラグイン。通信は wp-cli(wp letsblog)だけで行い、REST API のルートは登録しない。
 * Version: 1.0.0
 * License: GPL-2.0-or-later
 * License URI: https://www.gnu.org/licenses/gpl-2.0.html
 * Text Domain: letsblog
 */

if (!defined('ABSPATH') && !defined('LETSBLOG_PLUGIN_TESTING')) {
    exit;
}

/** プラグイン自体のバージョン(上のプラグインヘッダの Version と揃える)。 */
const LETSBLOG_PLUGIN_VERSION = '1.0.0';

/** アプリとプラグインのやり取りの取り決めのバージョン。互換性のない変更をしたときだけ上げる。 */
const LETSBLOG_PROTOCOL_VERSION = 1;

if (defined('WP_CLI') && WP_CLI) {
    /**
     * Lets Blog 用の wp-cli コマンド。
     */
    class Letsblog_CLI_Command
    {
        /**
         * プラグインのバージョンとプロトコルのバージョンを JSON で返す。
         *
         * ## EXAMPLES
         *
         *     wp letsblog status
         */
        public function status($args, $assoc_args)
        {
            WP_CLI::line(json_encode([
                'plugin_version' => LETSBLOG_PLUGIN_VERSION,
                'protocol_version' => LETSBLOG_PROTOCOL_VERSION,
            ]));
        }
    }

    WP_CLI::add_command('letsblog', 'Letsblog_CLI_Command');
}
