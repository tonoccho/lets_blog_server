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

/** 受け取った内容(JSON)とそのハッシュを保存する WordPress の option 名。 */
const LETSBLOG_OPTION_SYNC_PAYLOAD = 'letsblog_sync_payload';
const LETSBLOG_OPTION_SYNC_HASH = 'letsblog_sync_hash';

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
                'sync_hash' => get_option(LETSBLOG_OPTION_SYNC_HASH, null),
            ]));
        }

        /**
         * Lets Blog から受け取ったタグ定義・統合 CSS・プレフィックス等(JSON)を DB に保存する。
         * 記事の表示時は保存済みの内容だけを使い、Lets Blog サーバーとは通信しない。
         *
         * ## OPTIONS
         *
         * --file=<path>
         * : 内容(JSON オブジェクト)を書いたファイル。
         *
         * [--hash=<sha256>]
         * : 内容の SHA-256(16進)。指定したとき、受け取った内容と食い違えば保存せず失敗する。
         *
         * ## EXAMPLES
         *
         *     wp letsblog sync --file=/tmp/payload.json --hash=3b4c...
         */
        public function sync($args, $assoc_args)
        {
            $file = $assoc_args['file'] ?? null;
            if (!is_string($file) || $file === '') {
                WP_CLI::error('--file を指定してください');
                return;
            }
            $payload = is_readable($file) ? file_get_contents($file) : false;
            if (!is_string($payload)) {
                WP_CLI::error("ファイルを読めません: $file");
                return;
            }
            $expected = $assoc_args['hash'] ?? null;
            $hash = hash('sha256', $payload);
            if (is_string($expected) && $expected !== '' && !hash_equals($expected, $hash)) {
                WP_CLI::error("ハッシュが一致しません(期待 {$expected}、実際 {$hash})");
                return;
            }
            $decoded = json_decode($payload, true);
            if (!is_array($decoded) || array_is_list($decoded)) {
                WP_CLI::error('内容が JSON オブジェクトではありません');
                return;
            }
            update_option(LETSBLOG_OPTION_SYNC_PAYLOAD, $payload, false);
            update_option(LETSBLOG_OPTION_SYNC_HASH, $hash, false);
            WP_CLI::line(json_encode(['sync_hash' => $hash]));
        }
    }

    WP_CLI::add_command('letsblog', 'Letsblog_CLI_Command');
}
