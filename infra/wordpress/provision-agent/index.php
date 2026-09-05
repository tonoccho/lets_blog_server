<?php
/**
 * WordPress自動プロビジョニング用の内部限定エージェント。
 * nginxには一切公開せず、lbs-net内部(Spring Boot APIコンテナ)からのみ呼び出される想定。
 * X-Provision-Token ヘッダを共有シークレット(WP_PROVISION_TOKEN)と照合して認証する。
 */

header('Content-Type: application/json');

function respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body, JSON_UNESCAPED_UNICODE);
    exit;
}

// ダッシュボードの稼働状況チェック用の軽量なヘルスチェック(issue #197)。
// エージェント自体の生死のみを返すため、他のエンドポイントと異なり認証を要求しない
// (lbs-net内部限定でnginxには公開されないネットワーク境界を前提とする)。
if (($_SERVER['REQUEST_URI'] ?? '') === '/health' && $_SERVER['REQUEST_METHOD'] === 'GET') {
    respond(200, ['status' => 'ok']);
}

$expectedToken = getenv('WP_PROVISION_TOKEN');
$providedToken = $_SERVER['HTTP_X_PROVISION_TOKEN'] ?? '';
if (!$expectedToken || !hash_equals($expectedToken, $providedToken)) {
    respond(403, ['error' => '認証に失敗しました']);
}

function isValidSlug(string $value): bool
{
    return (bool) preg_match('/^[a-z0-9-]+$/', $value);
}

function isValidDbName(string $value): bool
{
    return (bool) preg_match('/^[a-z0-9_-]+$/', $value);
}

/**
 * stdout/stderrをエラー表示用に1つの文字列へまとめる。porcelain出力の抽出には使わないこと
 * (wp-cliがstderrへPHP Warning等を出すことがあり、それが混ざるとID等の値が壊れるため)。
 */
function combinedOutput(string $stdout, string $stderr): string
{
    return trim($stdout . ($stderr !== '' ? "\n$stderr" : ''));
}

/**
 * escapeshellargで各トークンを個別にエスケープしてからシェルへ渡す(コマンドインジェクション対策)。
 * stdout/stderrは分離して返す。porcelain出力(投稿ID等)はstdoutのみから取り出すこと
 * (stderrにPHP Warning等が出た場合に、stdoutの値と混ざって壊れるのを防ぐため)。
 * @param string[] $args
 * @return array{0:int,1:string,2:string}
 */
function runCommand(array $args): array
{
    $command = implode(' ', array_map('escapeshellarg', $args));
    $descriptors = [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']];
    $process = proc_open($command, $descriptors, $pipes);
    if (!is_resource($process)) {
        return [1, '', 'proc_openに失敗しました'];
    }
    fclose($pipes[0]);
    $stdout = stream_get_contents($pipes[1]);
    $stderr = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    $exitCode = proc_close($process);
    return [$exitCode, trim($stdout), trim($stderr)];
}

/**
 * wp-cli(phar)実行時、PHP CLIのデフォルトmemory_limitではWordPressコア展開時に
 * メモリ不足になることがあるため、明示的に緩和した上で実行する。
 * @param string[] $args wp-cliへのサブコマンド以降の引数
 * @return array{0:int,1:string,2:string}
 */
function runWp(array $args): array
{
    return runCommand(array_merge(['php', '-d', 'memory_limit=512M', '/usr/local/bin/wp'], $args));
}

/**
 * 投稿本文の送信等、STDIN経由の入力が必要なwp-cli呼び出し用。
 * runWp/runCommandと同様に各トークンをescapeshellargで個別にエスケープしたコマンド文字列を
 * proc_openでSTDINパイプ付き実行する(exec()はSTDINを渡せないため)。
 * stdout/stderrは分離して返す(runCommandと同じ理由)。
 * @param string[] $args
 * @return array{0:int,1:string,2:string}
 */
function runWpWithStdin(array $args, string $stdin): array
{
    $command = implode(' ', array_map('escapeshellarg',
        array_merge(['php', '-d', 'memory_limit=512M', '/usr/local/bin/wp'], $args)));
    $descriptors = [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']];
    $process = proc_open($command, $descriptors, $pipes);
    if (!is_resource($process)) {
        return [1, '', 'proc_openに失敗しました'];
    }
    fwrite($pipes[0], $stdin);
    fclose($pipes[0]);
    $stdout = stream_get_contents($pipes[1]);
    $stderr = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    $exitCode = proc_close($process);
    return [$exitCode, trim($stdout), trim($stderr)];
}

/**
 * 指定slugのサイトディレクトリパスを返す。存在しなければnullを返す
 * (wp-cli系エンドポイントで共通の「サイト存在確認」に使う)。
 */
function resolveExistingSitePath(string $slug): ?string
{
    $sitePath = "/var/www/html/sites/$slug";
    return is_dir($sitePath) ? $sitePath : null;
}

/**
 * WordPressコアはデフォルトでSVG(image/svg+xml)をupload_mimesに含めないため、
 * SVG画像(記事投稿時にImageResizeServiceがImageIOでデコードできない形式として無変換で
 * 送ってくる)がwp_check_filetype_and_ext()に拒否され「このファイルタイプをアップロードする
 * 権限がありません」でメディアインポートが失敗する(issue #485)。他形式の挙動は変えず
 * SVGのみ許可するmu-pluginを配置する(--execはWordPressロード前に評価されadd_filter()が
 * 未定義のため使えない)。既に配置済みなら何もしない(冪等)。
 */
function ensureSvgUploadMuPlugin(string $sitePath): void
{
    $muPluginsDir = "$sitePath/wp-content/mu-plugins";
    $muPluginFile = "$muPluginsDir/letsblog-allow-svg-upload.php";
    if (file_exists($muPluginFile)) {
        return;
    }
    if (!is_dir($muPluginsDir)) {
        mkdir($muPluginsDir, 0755, true);
    }
    $contents = <<<'PHP'
<?php
add_filter('upload_mimes', function ($mimes) {
    $mimes['svg'] = 'image/svg+xml';
    return $mimes;
});
add_filter('wp_check_filetype_and_ext', function ($data, $file, $filename, $mimes) {
    if (empty($data['type'])) {
        $check = wp_check_filetype($filename, $mimes);
        $data['ext'] = $check['ext'];
        $data['type'] = $check['type'];
    }
    return $data;
}, 10, 4);
PHP;
    file_put_contents($muPluginFile, $contents);
}

/**
 * core download以降の失敗時に呼び出す。既に作成済みのディレクトリ・DBを
 * (存在すれば)削除してから、通常のrespond()と同じ形式でエラーを返す。
 * rm -rf/DROP DATABASE IF EXISTSはいずれも冪等なため、/deprovisionとの二重実行でも問題ない。
 */
function cleanupAndRespond(
    int $status,
    array $body,
    string $sitePath,
    string $dbName,
    string $dbHost,
    string $rootPassword
): void {
    runCommand(['rm', '-rf', $sitePath]);
    runCommand(['mysql', '--skip-ssl', '-h', $dbHost, '-uroot', "-p$rootPassword", '-e', "DROP DATABASE IF EXISTS `$dbName`;"]);
    respond($status, $body);
}

const ALLOWED_LOCALES = ['ja', 'en_US', 'en_GB', 'zh_CN', 'zh_TW', 'ko_KR', 'fr_FR', 'de_DE', 'es_ES', 'pt_BR'];

$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
$rawBody = file_get_contents('php://input');
$input = json_decode($rawBody === false ? '' : $rawBody, true);
if (!is_array($input)) {
    $input = [];
}

$dbHost = getenv('WORDPRESS_DB_HOST') ?: 'mysql';
$dbUser = getenv('WORDPRESS_DB_USER') ?: 'lbs_app';
$dbPassword = getenv('WORDPRESS_DB_PASSWORD') ?: '';
$rootPassword = getenv('MYSQL_ROOT_PASSWORD') ?: '';

if ($path === '/provision' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $dbName = (string) ($input['dbName'] ?? '');
    $title = (string) ($input['title'] ?? $slug);
    $adminUser = (string) ($input['adminUser'] ?? '');
    $adminEmail = (string) ($input['adminEmail'] ?? '');
    $adminPassword = (string) ($input['adminPassword'] ?? '');
    $locale = (string) ($input['locale'] ?? 'ja');

    if (!isValidSlug($slug) || !isValidDbName($dbName) || $adminUser === '' || $adminEmail === '' || $adminPassword === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }

    if (!in_array($locale, ALLOWED_LOCALES, true)) {
        respond(400, ['error' => 'ロケールが無効です']);
    }

    $sitePath = "/var/www/html/sites/$slug";
    if (is_dir($sitePath)) {
        respond(409, ['error' => "サイト '$slug' は既に存在します"]);
    }

    $siteUrl = "https://localhost/sites/$slug";

    [$code, $out, $err] = runCommand(['mkdir', '-p', $sitePath]);
    if ($code !== 0) {
        respond(500, ['error' => 'ディレクトリ作成に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }

    $createDbSql = sprintf(
        "CREATE DATABASE IF NOT EXISTS `%s`; GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'%%'; FLUSH PRIVILEGES;",
        $dbName, $dbName, $dbUser
    );
    [$code, $out, $err] = runCommand(['mysql', '--skip-ssl', '-h', $dbHost, '-uroot', "-p$rootPassword", '-e', $createDbSql]);
    if ($code !== 0) {
        runCommand(['rm', '-rf', $sitePath]);
        respond(500, ['error' => 'データベース作成に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }

    // core downloadはロケール指定なし(デフォルトen_US)で行う。--locale=$localeを直接指定すると、
    // 該当バージョンの翻訳済みコアパッケージがwordpress.org側にまだ存在しない場合に
    // "The requested locale (...) was not found." で失敗することがあるため、
    // 未翻訳コアのダウンロード → 言語パックの個別インストール(下記)の2段階に分離する。
    [$code, $out, $err] = runWp(['core', 'download', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'WordPressコアのダウンロードに失敗しました', 'detail' => combinedOutput($out, $err)], $sitePath, $dbName, $dbHost, $rootPassword);
    }

    [$code, $out, $err] = runWp([
        'config', 'create',
        "--path=$sitePath",
        "--dbname=$dbName",
        "--dbuser=$dbUser",
        "--dbpass=$dbPassword",
        "--dbhost=$dbHost",
        '--allow-root',
    ]);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'wp-config.php作成に失敗しました', 'detail' => combinedOutput($out, $err)], $sitePath, $dbName, $dbHost, $rootPassword);
    }

    // このWordPressインスタンスは常駐wordpressコンテナ内でのみ動作し、外部からは
    // 常にTLS終端済みのreverse-proxy経由、内部からは信頼されたlbs-net経由でのみアクセスされる。
    // 生の(TLS終端前の)HTTPアクセスが発生し得ないため、is_ssl()を常にtrueとして扱ってよい。
    // これによりApplication Passwords認証(is_ssl()必須)がSpring Boot APIからの
    // 内部プレーンHTTP呼び出しでも機能する。
    $configPath = "$sitePath/wp-config.php";
    $configContents = file_get_contents($configPath);
    if ($configContents !== false) {
        $configContents = preg_replace(
            '/<\?php/',
            "<?php\n\$_SERVER['HTTPS'] = 'on';\n",
            $configContents,
            1
        );
        file_put_contents($configPath, $configContents);
    }

    [$code, $out, $err] = runWp([
        'core', 'install',
        "--path=$sitePath",
        "--url=$siteUrl",
        "--title=$title",
        "--admin_user=$adminUser",
        "--admin_password=$adminPassword",
        "--admin_email=$adminEmail",
        '--skip-email',
        '--allow-root',
    ]);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'WordPressのインストールに失敗しました', 'detail' => combinedOutput($out, $err)], $sitePath, $dbName, $dbHost, $rootPassword);
    }

    // 言語パックのインストール・有効化はcore install(DBテーブル作成)後に行う。wp language core install
    // はサイトが導入済み(DBテーブルが存在する)であることを前提とするため、core install前には実行できない。
    // core install自体の--localeは、対象言語パックが未導入だと黙って無視されてしまうため使わず、
    // 導入後に--activateで確実に有効化する。
    if ($locale !== 'en_US') {
        [$code, $out, $err] = runWp(['language', 'core', 'install', $locale, '--activate', "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            cleanupAndRespond(500, ['error' => '言語パックのインストールに失敗しました', 'detail' => combinedOutput($out, $err)], $sitePath, $dbName, $dbHost, $rootPassword);
        }
    }

    // パーマリンクを「投稿名」構造にする(デフォルトの「基本」のままでは
    // /wp-json/ のようなpretty permalink形式のREST APIパスが404になるため必須)。
    [$code, $out, $err] = runWp(['rewrite', 'structure', '/%postname%/', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'パーマリンク設定に失敗しました', 'detail' => combinedOutput($out, $err)], $sitePath, $dbName, $dbHost, $rootPassword);
    }

    // wp-cliはCLI SAPIで動作するためapache_get_modules()でmod_rewriteを検出できず、
    // --hardを指定しても.htaccessの自動生成を拒否する(WP-CLI固有の既知の制約)。
    // そのため標準的なWordPress用リライトルールを直接書き込む。サブディレクトリ設置のため
    // RewriteBase/RewriteRuleの遷移先には /sites/{slug}/ を明示する。
    $htaccess = "# BEGIN WordPress\n"
        . "<IfModule mod_rewrite.c>\n"
        . "RewriteEngine On\n"
        . "RewriteBase /sites/$slug/\n"
        . "RewriteRule ^index\\.php$ - [L]\n"
        . "RewriteCond %{REQUEST_FILENAME} !-f\n"
        . "RewriteCond %{REQUEST_FILENAME} !-d\n"
        . "RewriteRule . /sites/$slug/index.php [L]\n"
        . "</IfModule>\n"
        . "# END WordPress\n";
    file_put_contents("$sitePath/.htaccess", $htaccess);

    [$code, $out, $err] = runWp([
        'user', 'application-password', 'create',
        "--path=$sitePath",
        $adminUser, 'letsblog', '--porcelain', '--allow-root',
    ]);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'アプリケーションパスワードの発行に失敗しました', 'detail' => combinedOutput($out, $err)], $sitePath, $dbName, $dbHost, $rootPassword);
    }
    $applicationPassword = $out;

    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);

    respond(200, [
        'url' => $siteUrl,
        'adminUser' => $adminUser,
        'applicationPassword' => $applicationPassword,
    ]);
}

/**
 * DBには登録されていないが、ディレクトリ・DBとしては既に構築済みのWordPressサイトを
 * 取り込むためのエンドポイント(issue #317)。/provisionと異なり新規構築は行わず、
 * 既存の管理ユーザーに対して新しいApplication Passwordを発行するのみ。
 */
if ($path === '/adopt' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $adminUser = (string) ($input['adminUser'] ?? '');

    if (!isValidSlug($slug) || $adminUser === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['user', 'list', "--search=$adminUser", '--fields=ID,user_login', '--format=json', "--path=$sitePath", '--allow-root']);
    $existing = $code === 0 ? (json_decode($out, true) ?: []) : [];
    $matched = false;
    foreach ($existing as $user) {
        if (strcasecmp((string) $user['user_login'], $adminUser) === 0) {
            $matched = true;
            break;
        }
    }
    if (!$matched) {
        respond(404, ['error' => "ユーザー '$adminUser' がサイト '$slug' に見つかりません"]);
    }

    [$code, $out, $err] = runWp([
        'user', 'application-password', 'create',
        "--path=$sitePath",
        $adminUser, 'letsblog', '--porcelain', '--allow-root',
    ]);
    if ($code !== 0) {
        respond(500, ['error' => 'アプリケーションパスワードの発行に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }

    respond(200, [
        'url' => "https://localhost/sites/$slug",
        'adminUser' => $adminUser,
        'applicationPassword' => $out,
    ]);
}

const ALLOWED_SYNC_TARGETS = ['themes', 'plugins', 'media', 'db'];
// mediaのみ実際のディレクトリ名(uploads)が公開名と異なるため、対応表を持つ
const SYNC_TARGET_DIRS = ['themes' => 'themes', 'plugins' => 'plugins', 'media' => 'uploads'];

if ($path === '/sync' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $fromSlug = (string) ($input['fromSlug'] ?? '');
    $fromDbName = (string) ($input['fromDbName'] ?? '');
    $toSlug = (string) ($input['toSlug'] ?? '');
    $toDbName = (string) ($input['toDbName'] ?? '');
    $targets = is_array($input['targets'] ?? null) ? array_values($input['targets']) : [];

    if (!isValidSlug($fromSlug) || !isValidSlug($toSlug) || !isValidDbName($fromDbName) || !isValidDbName($toDbName)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    if ($fromSlug === $toSlug) {
        respond(400, ['error' => '同期元と同期先には異なるサイトを指定してください']);
    }
    if (empty($targets) || !empty(array_diff($targets, ALLOWED_SYNC_TARGETS))) {
        respond(400, ['error' => 'targetsが不正です(themes/plugins/media/dbのいずれかを指定してください)']);
    }

    $fromPath = "/var/www/html/sites/$fromSlug";
    $toPath = "/var/www/html/sites/$toSlug";
    if (!is_dir($fromPath) || !is_dir($toPath)) {
        respond(404, ['error' => '同期元または同期先のサイトが見つかりません']);
    }

    $backupDir = "/var/www/html/backups/$toSlug";
    runCommand(['mkdir', '-p', $backupDir]);
    $timestamp = date('Ymd-His');

    foreach (SYNC_TARGET_DIRS as $target => $dirName) {
        if (!in_array($target, $targets, true)) {
            continue;
        }
        $fromContentPath = "$fromPath/wp-content/$dirName";
        $toContentPath = "$toPath/wp-content/$dirName";
        if (!is_dir($fromContentPath)) {
            continue;
        }
        runCommand(['tar', '-czf', "$backupDir/{$target}-{$timestamp}.tar.gz", '-C', "$toPath/wp-content", $dirName]);
        runCommand(['rm', '-rf', $toContentPath]);
        [$code, $out, $err] = runCommand(['cp', '-r', $fromContentPath, $toContentPath]);
        if ($code !== 0) {
            respond(500, ['error' => "{$target}の同期に失敗しました", 'detail' => combinedOutput($out, $err)]);
        }
    }

    if (in_array('db', $targets, true)) {
        // 上書きされる側(同期先)のバックアップを先に取得しておく
        runCommand(['sh', '-c',
            'mysqldump --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
                . ' ' . escapeshellarg($toDbName) . ' > ' . escapeshellarg("$backupDir/db-{$timestamp}.sql")]);

        // 同期元・同期先で異なりうるテーブルプレフィックスを解決する(issue #516)。
        // WordPressのプレフィックスはインストール時にランダム化されることがあり、また
        // SSH管理サイトからの/db-importで同期先のプレフィックスが同期元に合わせて後から
        // 変更されていることもあるため、同期元・同期先で実際のテーブル名が異なりうる。
        [$fromPrefixCode, $fromPrefixOut, ] = runWp(['config', 'get', 'table_prefix', "--path=$fromPath", '--allow-root']);
        $fromPrefix = ($fromPrefixCode === 0 && preg_match('/^[A-Za-z0-9_]+$/', trim($fromPrefixOut)))
            ? trim($fromPrefixOut) : 'wp_';
        [$toPrefixCode, $toPrefixOut, ] = runWp(['config', 'get', 'table_prefix', "--path=$toPath", '--allow-root']);
        $toPrefix = ($toPrefixCode === 0 && preg_match('/^[A-Za-z0-9_]+$/', trim($toPrefixOut)))
            ? trim($toPrefixOut) : 'wp_';

        // 各環境の管理者・プロジェクトメンバーアカウント(users/usermeta)は
        // ProjectUserSyncServiceが環境ごとに個別管理しているため、DB同期の対象から除外する
        $dumpCmd = 'mysqldump --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
            . ' --ignore-table=' . escapeshellarg("$fromDbName.{$fromPrefix}users")
            . ' --ignore-table=' . escapeshellarg("$fromDbName.{$fromPrefix}usermeta")
            . ' ' . escapeshellarg($fromDbName);
        $importCmd = 'mysql --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
            . ' ' . escapeshellarg($toDbName);

        // dashにはpipefailがなく、"$dumpCmd | $importCmd"のようにパイプで直結すると
        // mysqldump側が失敗してもmysql側の終了コードで上書きされ、失敗が握りつぶされる(issue #516)。
        // ダンプを一旦ファイルに書き出し、export/importそれぞれの終了コードを個別に検証する。
        $dumpFile = "$backupDir/db-sync-{$timestamp}.sql";
        [$code, $out, $err] = runCommand(['sh', '-c', $dumpCmd . ' > ' . escapeshellarg($dumpFile)]);
        if ($code !== 0) {
            respond(500, ['error' => 'DBのエクスポートに失敗しました', 'detail' => combinedOutput($out, $err)]);
        }

        // ダンプは同期元のテーブルプレフィックスのまま(CREATE TABLE等を書き換えていない)出力される。
        // プレフィックスが異なると、インポート時に同期先が実際に読んでいるテーブル(例: wp_options)ではなく
        // 別名の新規テーブル(例: jI7_options)が追加で作られるだけになり、WordPress側は何も変わって見えない
        // (issue #516)。同期先が実際に使用しているプレフィックスへ書き換えてからインポートする。
        if ($fromPrefix !== $toPrefix) {
            runCommand(['sed', '-i', "s/`{$fromPrefix}/`{$toPrefix}/g", $dumpFile]);
        }

        [$code, $out, $err] = runCommand(['sh', '-c', $importCmd . ' < ' . escapeshellarg($dumpFile)]);
        if ($code !== 0) {
            respond(500, ['error' => 'DBのインポートに失敗しました', 'detail' => combinedOutput($out, $err)]);
        }

        runCommand(['rm', '-f', $dumpFile]);

        // コピー元のURLがwp_options等に焼き込まれたままになるため、コピー先自身のURLへ書き戻す
        $fromUrl = "https://localhost/sites/$fromSlug";
        $toUrl = "https://localhost/sites/$toSlug";
        [$code, $out, $err] = runWp(['search-replace', $fromUrl, $toUrl, '--all-tables', "--path=$toPath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'URL書き換え(search-replace)に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
    }

    runCommand(['chown', '-R', 'www-data:www-data', $toPath]);
    respond(200, ['status' => 'ok']);
}

/**
 * SSH管理サイト(同期元)から取得したDBダンプ(wp db export)をアップロードし、managedサイトの
 * DBへインポートする(issue #511)。SSH管理サイトはこのコンテナと同一ホストにいないため、
 * /syncのようにファイルパスを直接指定した`cp`/`mysqldump | mysql`パイプが使えず、
 * Java側で一度ダンプを取得しmultipartでアップロードする方式にしている。
 * DB/メディアのみ対応(テーマ/プラグインはSSH管理サイトからは同期不可。ProjectEnvironmentSyncService参照)。
 * メディアは/media-importで別途扱う。
 */
if ($path === '/db-import' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($_POST['slug'] ?? '');
    $dbName = (string) ($_POST['dbName'] ?? '');
    $fromUrl = (string) ($_POST['fromUrl'] ?? '');
    $fromPrefix = (string) ($_POST['fromPrefix'] ?? '');

    if (!isValidSlug($slug) || !isValidDbName($dbName) || empty($_FILES['file'])) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    if ($fromPrefix !== '' && !preg_match('/^[A-Za-z0-9_]+$/', $fromPrefix)) {
        respond(400, ['error' => 'fromPrefixが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }

    $tmpPath = '/tmp/letsblog-dbimport-' . bin2hex(random_bytes(8)) . '.sql';
    if (!move_uploaded_file($_FILES['file']['tmp_name'], $tmpPath)) {
        respond(500, ['error' => 'アップロードファイルの一時保存に失敗しました']);
    }

    $backupDir = "/var/www/html/backups/$slug";
    runCommand(['mkdir', '-p', $backupDir]);
    $timestamp = date('Ymd-His');
    // 上書きされる側(同期先)のバックアップを先に取得しておく。
    // db reset/db import/db exportはWordPressの$wpdb(mysqli)を経由せずmysql/mysqldumpコマンドへ直接
    // シェルアウトするため、他のwp-cliコマンド(search-replace等)と異なりコンテナのmysqlクライアント既定
    // (SSL優先)の影響を受け自己署名証明書で失敗する。--defaultsで/root/.my.cnf(skip-ssl)を読み込ませる。
    runWp(['db', 'export', "$backupDir/db-{$timestamp}.sql", "--path=$sitePath", '--allow-root', '--defaults']);

    // 同期先自身のアカウント(wp_users/wp_usermeta相当)を退避する。各環境の管理者/プロジェクト
    // メンバーアカウントはProjectUserSyncServiceが環境ごとに個別管理しており、同期元のダンプは
    // 意図的にusers/usermetaを除外している(exportDatabase参照)。しかしdb resetはDB全体を削除するため、
    // 退避せずに進めると同期先には有効なアカウントテーブルが一つも残らなくなってしまう(issue #511)。
    [$origPrefixCode, $origPrefixOut, ] = runWp(['config', 'get', 'table_prefix', "--path=$sitePath", '--allow-root']);
    $originalPrefix = $origPrefixCode === 0 ? trim($origPrefixOut) : 'wp_';
    if (!preg_match('/^[A-Za-z0-9_]+$/', $originalPrefix)) {
        $originalPrefix = 'wp_';
    }
    $effectivePrefix = $fromPrefix !== '' ? $fromPrefix : $originalPrefix;
    $userBackupPath = '/tmp/letsblog-dbimport-users-' . bin2hex(random_bytes(8)) . '.sql';
    runWp(['db', 'export', $userBackupPath,
        '--tables=' . $originalPrefix . 'users,' . $originalPrefix . 'usermeta',
        "--path=$sitePath", '--allow-root', '--defaults']);

    // 同期先を完全にリセットしてからインポートする(旧テーブルを残さない)。
    [$resetCode, $resetOut, $resetErr] =
        runWp(['db', 'reset', '--yes', "--path=$sitePath", '--allow-root', '--defaults']);
    if ($resetCode !== 0) {
        respond(500, ['error' => 'DBのリセットに失敗しました', 'detail' => combinedOutput($resetOut, $resetErr)]);
    }

    [$code, $out, $err] = runWp(['db', 'import', $tmpPath, "--path=$sitePath", '--allow-root', '--defaults']);
    runCommand(['rm', '-f', $tmpPath]);
    if ($code !== 0) {
        respond(500, ['error' => 'DBのインポートに失敗しました', 'detail' => combinedOutput($out, $err)]);
    }

    // 退避したアカウントを、これから有効になるプレフィックス($effectivePrefix)へ付け替えて復元する。
    if (is_file($userBackupPath) && filesize($userBackupPath) > 0) {
        if ($effectivePrefix !== $originalPrefix) {
            // PHPのダブルクォート文字列内で`\``と書くとバックスラッシュが残ったまま(`\`\`)になり
            // sedのパターンが一致しなくなるため、バッククォートはエスケープせず生で書く。
            runCommand(['sed', '-i', "s/`{$originalPrefix}/`{$effectivePrefix}/g", $userBackupPath]);
        }
        [$userCode, $userOut, $userErr] =
            runWp(['db', 'import', $userBackupPath, "--path=$sitePath", '--allow-root', '--defaults']);
        if ($userCode !== 0) {
            respond(500, ['error' => '同期先アカウントの復元に失敗しました', 'detail' => combinedOutput($userOut, $userErr)]);
        }

        // usermetaの行はテーブル名だけでなく、meta_key自体にも旧プレフィックスが埋め込まれている
        // (例: wp_capabilities/wp_user_level)。WordPressは$wpdb->prefix(=新プレフィックス)を
        // 前置したmeta_keyを参照して権限判定するため、書き換えないとログインはできても権限が
        // 認識されずwp-adminが403になる(issue #511)。
        if ($effectivePrefix !== $originalPrefix) {
            $usermetaTable = $effectivePrefix . 'usermeta';
            $renameMetaKeysSql = 'UPDATE `' . $usermetaTable . '` SET meta_key = CONCAT('
                . "'" . $effectivePrefix . "', SUBSTRING(meta_key, LENGTH('" . $originalPrefix . "')+1)) "
                . "WHERE LEFT(meta_key, LENGTH('" . $originalPrefix . "')) = '" . $originalPrefix . "'";
            runCommand(['sh', '-c',
                'mysql --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
                    . ' ' . escapeshellarg($dbName) . ' -e ' . escapeshellarg($renameMetaKeysSql)]);
        }
    }
    runCommand(['rm', '-f', $userBackupPath]);

    // ダンプは同期元のテーブルプレフィックスのまま(CREATE TABLE等を書き換えていない)インポートされる。
    // WordPressのインストーラはセキュリティのためプレフィックスをランダム生成することがあり、
    // 同期元と同期先で異なりうるため、同期先のwp-config.phpのtable_prefixを同期元に合わせる
    // (ダンプ側のテーブル名やoption_name等のデータを書き換えるより単純で安全。issue #511)。
    if ($effectivePrefix !== $originalPrefix) {
        [$code, $out, $err] = runWp(['config', 'set', 'table_prefix', $effectivePrefix, "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'テーブルプレフィックスの設定に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
    }

    $toUrl = "https://localhost/sites/$slug";
    // 同期元(SSH管理サイト)のURLがwp_options等に焼き込まれたままになるため、同期先自身のURLへ書き戻す。
    // 末尾スラッシュの有無でsearch-replaceの完全一致に漏れが出ることがあるため両方の形で試す。
    if ($fromUrl !== '') {
        foreach (array_unique([$fromUrl, rtrim($fromUrl, '/')]) as $fromUrlVariant) {
            if ($fromUrlVariant === '') {
                continue;
            }
            [$code, $out, $err] = runWp(['search-replace', $fromUrlVariant, $toUrl, '--all-tables', "--path=$sitePath", '--allow-root']);
            if ($code !== 0) {
                respond(500, ['error' => 'URL書き換え(search-replace)に失敗しました', 'detail' => combinedOutput($out, $err)]);
            }
        }
    }
    // siteurl/homeはサイトの同一性(管理画面URL・ログインリダイレクト等)に直結するため、
    // search-replaceの一致漏れに関わらず必ず同期先自身のURLへ強制的に合わせる。
    runWp(['option', 'update', 'siteurl', $toUrl, "--path=$sitePath", '--allow-root']);
    runWp(['option', 'update', 'home', $toUrl, "--path=$sitePath", '--allow-root']);

    // 同期先はローカル/テスト環境に限られ本番になることはない(ProjectEnvironmentSyncServiceが
    // 同期先=productionを常に拒否する)ため、同期元の下書き・予約投稿・限定公開の状態を
    // そのまま持ち込むと確認しづらい。投稿・固定ページは一律公開状態にする。
    $postsTable = $effectivePrefix . 'posts';
    $publishSql = 'UPDATE `' . $postsTable . "` SET post_status = 'publish' "
        . "WHERE post_type IN ('post','page') AND post_status NOT IN ('publish','trash','auto-draft')";
    runCommand(['sh', '-c',
        'mysql --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
            . ' ' . escapeshellarg($dbName) . ' -e ' . escapeshellarg($publishSql)]);

    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);
    respond(200, ['status' => 'ok']);
}

/**
 * SSH管理サイト(同期元)から取得したメディア(wp-content/uploads)のtar.gzをアップロードし、
 * managedサイトへ展開する(issue #511)。/db-importと同じ理由でmultipartアップロード方式にしている。
 * アーカイブは`tar -czf ... -C <wp-content> uploads`形式(先頭に"uploads/"を含む)を前提とする。
 */
if ($path === '/media-import' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($_POST['slug'] ?? '');

    if (!isValidSlug($slug) || empty($_FILES['file'])) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }

    $tmpPath = '/tmp/letsblog-mediaimport-' . bin2hex(random_bytes(8)) . '.tar.gz';
    if (!move_uploaded_file($_FILES['file']['tmp_name'], $tmpPath)) {
        respond(500, ['error' => 'アップロードファイルの一時保存に失敗しました']);
    }

    $contentPath = "$sitePath/wp-content";
    $uploadsPath = "$contentPath/uploads";
    $backupDir = "/var/www/html/backups/$slug";
    runCommand(['mkdir', '-p', $backupDir]);
    $timestamp = date('Ymd-His');
    if (is_dir($uploadsPath)) {
        // 上書きされる側(同期先)のバックアップを先に取得しておく
        runCommand(['tar', '-czf', "$backupDir/media-{$timestamp}.tar.gz", '-C', $contentPath, 'uploads']);
        runCommand(['rm', '-rf', $uploadsPath]);
    }

    [$code, $out, $err] = runCommand(['tar', '-xzf', $tmpPath, '-C', $contentPath]);
    runCommand(['rm', '-f', $tmpPath]);
    if ($code !== 0) {
        respond(500, ['error' => 'メディアのインポートに失敗しました', 'detail' => combinedOutput($out, $err)]);
    }

    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);
    respond(200, ['status' => 'ok']);
}

/**
 * SSH管理サイト(同期元)から取得したテーマ(wp-content/themes)のtar.gzをアップロードし、
 * managedサイトへ展開する(issue #511)。/media-importと同じ理由・同じ方式。プラグインは対象外
 * (ProjectEnvironmentSyncService参照)。アーカイブは`tar -czf ... -C <wp-content> themes`形式
 * (先頭に"themes/"を含む)を前提とする。
 */
if ($path === '/theme-import' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($_POST['slug'] ?? '');

    if (!isValidSlug($slug) || empty($_FILES['file'])) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }

    $tmpPath = '/tmp/letsblog-themeimport-' . bin2hex(random_bytes(8)) . '.tar.gz';
    if (!move_uploaded_file($_FILES['file']['tmp_name'], $tmpPath)) {
        respond(500, ['error' => 'アップロードファイルの一時保存に失敗しました']);
    }

    $contentPath = "$sitePath/wp-content";
    $themesPath = "$contentPath/themes";
    $backupDir = "/var/www/html/backups/$slug";
    runCommand(['mkdir', '-p', $backupDir]);
    $timestamp = date('Ymd-His');
    if (is_dir($themesPath)) {
        // 上書きされる側(同期先)のバックアップを先に取得しておく
        runCommand(['tar', '-czf', "$backupDir/themes-{$timestamp}.tar.gz", '-C', $contentPath, 'themes']);
        runCommand(['rm', '-rf', $themesPath]);
    }

    [$code, $out, $err] = runCommand(['tar', '-xzf', $tmpPath, '-C', $contentPath]);
    runCommand(['rm', '-f', $tmpPath]);
    if ($code !== 0) {
        respond(500, ['error' => 'テーマのインポートに失敗しました', 'detail' => combinedOutput($out, $err)]);
    }

    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);
    respond(200, ['status' => 'ok']);
}

/**
 * サイトのカテゴリ一覧を取得し、parent(term_id)を対応するparentSlugへ解決したうえで返す。
 * 環境間のカテゴリ同一性・親子関係はterm_idではなくスラッグで判定するため、一括管理の
 * カテゴリ操作(作成/編集/削除)・一覧取得(/categories)はいずれもこの関数を経由する。
 * @return array<int, array{term_id:int,name:string,slug:string,parent:int,parentSlug:?string,description:string}>
 */
/**
 * category(親を持つ)・post_tag(親なし)のいずれのtaxonomyでも共用するterm一覧取得ヘルパー。
 * taxonomy='post_tag'の場合もparent(常に0)は取得するが、parentSlugは常にnullになる。
 */
function fetchTerms(string $sitePath, string $taxonomy): array
{
    [$code, $out, $err] = runWp(['term', 'list', $taxonomy, '--fields=term_id,name,slug,parent,description', '--format=json', "--path=$sitePath", '--allow-root']);
    $terms = $code === 0 ? (json_decode($out, true) ?: []) : [];
    $slugById = [];
    foreach ($terms as $term) {
        $slugById[(int) $term['term_id']] = $term['slug'];
    }
    foreach ($terms as &$term) {
        $parentId = (int) ($term['parent'] ?? 0);
        $term['parentSlug'] = $parentId > 0 ? ($slugById[$parentId] ?? null) : null;
    }
    unset($term);
    return $terms;
}

function findTermBySlug(array $terms, string $slug): ?array
{
    foreach ($terms as $term) {
        if (strcasecmp($term['slug'], $slug) === 0) {
            return $term;
        }
    }
    return null;
}

if ($path === '/categories' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }
    respond(200, ['categories' => array_values(fetchTerms($sitePath, 'category'))]);
}

if ($path === '/tags' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }
    respond(200, ['tags' => array_values(fetchTerms($sitePath, 'post_tag'))]);
}

if ($path === '/plugins' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }
    [$code, $out, $err] = runWp(['plugin', 'list', '--fields=name,status', '--format=json', "--path=$sitePath", '--allow-root']);
    respond(200, ['plugins' => $code === 0 ? (json_decode($out, true) ?: []) : []]);
}

if ($path === '/themes' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }
    [$code, $out, $err] = runWp(['theme', 'list', '--fields=name,status', '--format=json', "--path=$sitePath", '--allow-root']);
    respond(200, ['themes' => $code === 0 ? (json_decode($out, true) ?: []) : []]);
}

const ALLOWED_BULK_ACTIONS = [
    'category_create', 'category_edit', 'category_delete',
    'tag_create', 'tag_edit', 'tag_delete',
    'plugin_install', 'plugin_activate', 'plugin_deactivate', 'plugin_delete',
    'theme_install', 'theme_activate', 'theme_delete',
];

if ($path === '/bulk-management' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $action = (string) ($input['action'] ?? '');
    $value = (string) ($input['value'] ?? '');

    if (!isValidSlug($slug) || !in_array($action, ALLOWED_BULK_ACTIONS, true) || $value === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }

    if ($action === 'category_create' || $action === 'tag_create') {
        $taxonomy = $action === 'category_create' ? 'category' : 'post_tag';
        $categorySlug = (string) ($input['categorySlug'] ?? '');
        $categoryParentSlug = (string) ($input['categoryParentSlug'] ?? '');
        $categoryDescription = (string) ($input['categoryDescription'] ?? '');
        if ($categorySlug === '') {
            respond(400, ['error' => 'スラッグを指定してください']);
        }

        $terms = fetchTerms($sitePath, $taxonomy);
        if (findTermBySlug($terms, $categorySlug) !== null) {
            respond(200, ['status' => 'skipped']);
        }

        $createArgs = ['term', 'create', $taxonomy, $value, "--slug=$categorySlug", '--porcelain', "--path=$sitePath", '--allow-root'];
        if ($categoryDescription !== '') {
            $createArgs[] = "--description=$categoryDescription";
        }
        if ($taxonomy === 'category' && $categoryParentSlug !== '') {
            $parent = findTermBySlug($terms, $categoryParentSlug);
            if ($parent === null) {
                respond(500, ['error' => "親カテゴリ(slug: $categoryParentSlug)が見つかりません"]);
            }
            $createArgs[] = '--parent=' . $parent['term_id'];
        }

        [$code, $out, $err] = runWp($createArgs);
        if ($code !== 0) {
            respond(500, ['error' => '作成に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
        respond(200, ['status' => 'ok']);
    }

    if ($action === 'category_edit' || $action === 'tag_edit') {
        $taxonomy = $action === 'category_edit' ? 'category' : 'post_tag';
        $categoryTargetSlug = (string) ($input['categoryTargetSlug'] ?? '');
        $categorySlug = (string) ($input['categorySlug'] ?? '');
        $categoryParentSlug = (string) ($input['categoryParentSlug'] ?? '');
        $categoryDescription = (string) ($input['categoryDescription'] ?? '');
        if ($categoryTargetSlug === '' || $categorySlug === '') {
            respond(400, ['error' => '編集対象のスラッグと変更後のスラッグを指定してください']);
        }

        $terms = fetchTerms($sitePath, $taxonomy);
        $target = findTermBySlug($terms, $categoryTargetSlug);
        if ($target === null) {
            respond(500, ['error' => "対象(slug: $categoryTargetSlug)が見つかりません"]);
        }

        $updateArgs = ['term', 'update', $taxonomy, (string) $target['term_id'],
            "--name=$value", "--slug=$categorySlug", "--path=$sitePath", '--allow-root'];
        if ($categoryDescription !== '') {
            $updateArgs[] = "--description=$categoryDescription";
        }
        if ($taxonomy === 'category' && $categoryParentSlug !== '') {
            $parent = findTermBySlug($terms, $categoryParentSlug);
            if ($parent === null) {
                respond(500, ['error' => "親カテゴリ(slug: $categoryParentSlug)が見つかりません"]);
            }
            if ((int) $parent['term_id'] === (int) $target['term_id']) {
                respond(500, ['error' => '親カテゴリに自分自身は指定できません']);
            }
            $updateArgs[] = '--parent=' . $parent['term_id'];
        }

        [$code, $out, $err] = runWp($updateArgs);
        if ($code !== 0) {
            respond(500, ['error' => '更新に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
        respond(200, ['status' => 'ok']);
    }

    if ($action === 'category_delete' || $action === 'tag_delete') {
        $taxonomy = $action === 'category_delete' ? 'category' : 'post_tag';
        $categoryTargetSlug = (string) ($input['categoryTargetSlug'] ?? '');
        if ($categoryTargetSlug === '') {
            respond(400, ['error' => '削除対象のスラッグを指定してください']);
        }

        $target = findTermBySlug(fetchTerms($sitePath, $taxonomy), $categoryTargetSlug);
        if ($target === null) {
            // 既に存在しない = 目的達成済みとみなす
            respond(200, ['status' => 'skipped']);
        }

        [$code, $out, $err] = runWp(['term', 'delete', $taxonomy, (string) $target['term_id'], "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => '削除に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
        respond(200, ['status' => 'ok']);
    }

    if ($action === 'plugin_install' || $action === 'theme_install') {
        $type = $action === 'plugin_install' ? 'plugin' : 'theme';
        [$code, $out, $err] = runWp([$type, 'list', '--field=name', '--format=json', "--path=$sitePath", '--allow-root']);
        $installed = $code === 0 ? (json_decode($out, true) ?: []) : [];
        if (in_array($value, $installed, true)) {
            respond(200, ['status' => 'skipped']);
        }
        [$code, $out, $err] = runWp([$type, 'install', $value, "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => "{$type}のインストールに失敗しました", 'detail' => combinedOutput($out, $err)]);
        }
        runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);
        respond(200, ['status' => 'ok']);
    }

    if ($action === 'plugin_activate' || $action === 'plugin_deactivate') {
        $verb = $action === 'plugin_activate' ? 'activate' : 'deactivate';
        [$code, $out, $err] = runWp(['plugin', $verb, $value, "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => "プラグインの{$verb}に失敗しました", 'detail' => combinedOutput($out, $err)]);
        }
        respond(200, ['status' => 'ok']);
    }

    if ($action === 'plugin_delete') {
        // 有効化されている場合に備え先に無効化を試みる(未有効化時のエラーは無視してよい)
        runWp(['plugin', 'deactivate', $value, "--path=$sitePath", '--allow-root']);
        [$code, $out, $err] = runWp(['plugin', 'delete', $value, "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'プラグインの削除に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
        respond(200, ['status' => 'ok']);
    }

    if ($action === 'theme_activate') {
        [$code, $out, $err] = runWp(['theme', 'activate', $value, "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'テーマの有効化に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
        respond(200, ['status' => 'ok']);
    }

    if ($action === 'theme_delete') {
        // 有効化中のテーマは削除できない(wp-cliが自然にエラーを返す想定。自動での切替は行わない)
        [$code, $out, $err] = runWp(['theme', 'delete', $value, "--path=$sitePath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'テーマの削除に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }
        respond(200, ['status' => 'ok']);
    }

    respond(400, ['error' => '未対応のactionです']);
}

const ALLOWED_BULK_UPLOAD_ACTIONS = ['plugin_install', 'theme_install'];

if ($path === '/bulk-management/upload' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($_POST['slug'] ?? '');
    $action = (string) ($_POST['action'] ?? '');

    if (!isValidSlug($slug) || !in_array($action, ALLOWED_BULK_UPLOAD_ACTIONS, true) || empty($_FILES['file'])) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = "/var/www/html/sites/$slug";
    if (!is_dir($sitePath)) {
        respond(404, ['error' => 'サイトが見つかりません']);
    }
    $type = $action === 'plugin_install' ? 'plugin' : 'theme';

    $tmpPath = '/tmp/letsblog-bulk-' . bin2hex(random_bytes(8)) . '.zip';
    if (!move_uploaded_file($_FILES['file']['tmp_name'], $tmpPath)) {
        respond(500, ['error' => 'アップロードファイルの一時保存に失敗しました']);
    }

    // zipの中身(実際のslug)は展開するまで確定しないため事前の存在チェックは行わず、
    // 常に--forceで上書きインストールする(本アプリ全体の「差分チェックをせず全上書き」方針に合わせる)
    [$code, $out, $err] = runWp([$type, 'install', $tmpPath, '--force', "--path=$sitePath", '--allow-root']);
    runCommand(['rm', '-f', $tmpPath]);
    if ($code !== 0) {
        respond(500, ['error' => "{$type}のインストールに失敗しました", 'detail' => combinedOutput($out, $err)]);
    }
    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);
    respond(200, ['status' => 'ok']);
}

if ($path === '/deprovision' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $dbName = (string) ($input['dbName'] ?? '');

    if (!isValidSlug($slug) || !isValidDbName($dbName)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }

    $sitePath = "/var/www/html/sites/$slug";
    runCommand(['rm', '-rf', $sitePath]);
    runCommand(['mysql', '--skip-ssl', '-h', $dbHost, '-uroot', "-p$rootPassword", '-e', "DROP DATABASE IF EXISTS `$dbName`;"]);

    respond(200, ['status' => 'ok']);
}

/**
 * 通常のブログ運用操作(投稿・カテゴリ/タグ解決・著者・メディア・疎通確認)をmanaged
 * WordPressサイトに対してwp-cli経由で行うためのエンドポイント群。
 * SSH経由のWordPressSshOperationsと同等の操作をエージェント側で行う。
 */

if ($path === '/wp-cli/core-version' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['core', 'version', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => 'wp core versionの実行に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    respond(200, ['version' => $out]);
}

const ALLOWED_RESOLVE_TAXONOMIES = ['category', 'post_tag'];

if ($path === '/wp-cli/resolve-terms' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $taxonomy = (string) ($input['taxonomy'] ?? '');
    $names = is_array($input['names'] ?? null) ? array_values($input['names']) : [];

    if (!isValidSlug($slug) || !in_array($taxonomy, ALLOWED_RESOLVE_TAXONOMIES, true) || empty($names)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['term', 'list', $taxonomy, '--fields=name,term_id', '--format=json', "--path=$sitePath", '--allow-root']);
    $existing = $code === 0 ? (json_decode($out, true) ?: []) : [];

    $ids = [];
    foreach ($names as $name) {
        $name = (string) $name;
        $matchId = null;
        foreach ($existing as $term) {
            if (strcasecmp((string) $term['name'], $name) === 0) {
                $matchId = (string) $term['term_id'];
                break;
            }
        }
        if ($matchId === null) {
            [$code, $out, $err] = runWp(['term', 'create', $taxonomy, $name, '--porcelain', "--path=$sitePath", '--allow-root']);
            if ($code !== 0) {
                respond(500, ['error' => "カテゴリ/タグ '$name' の作成に失敗しました", 'detail' => combinedOutput($out, $err)]);
            }
            $matchId = $out;
            $existing[] = ['name' => $name, 'term_id' => $matchId];
        }
        $ids[] = $matchId;
    }
    respond(200, ['ids' => $ids]);
}

if ($path === '/wp-cli/provision-author' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $email = (string) ($input['email'] ?? '');
    $wpRole = (string) ($input['wpRole'] ?? 'author');

    if (!isValidSlug($slug) || $email === '' || !str_contains($email, '@')) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['user', 'list', "--search=$email", '--fields=ID', '--format=json', "--path=$sitePath", '--allow-root']);
    $existing = $code === 0 ? (json_decode($out, true) ?: []) : [];
    $userId = !empty($existing) ? (string) $existing[0]['ID'] : null;

    if ($userId === null) {
        $username = substr($email, 0, strpos($email, '@'));
        $randomPassword = bin2hex(random_bytes(18));
        [$code, $out, $err] = runWp([
            'user', 'create', $username, $email,
            "--role=$wpRole", "--user_pass=$randomPassword", '--porcelain', "--path=$sitePath", '--allow-root',
        ]);
        if ($code !== 0) {
            // 同時作成の可能性を考慮し再検索してからフォールバックする
            [$code2, $out2, $err2] = runWp(['user', 'list', "--search=$email", '--fields=ID', '--format=json', "--path=$sitePath", '--allow-root']);
            $retry = $code2 === 0 ? (json_decode($out2, true) ?: []) : [];
            if (empty($retry)) {
                respond(500, ['error' => '著者の作成に失敗しました', 'detail' => combinedOutput($out, $err)]);
            }
            $userId = (string) $retry[0]['ID'];
        } else {
            $userId = $out;
        }
    }

    $updateArgs = ['user', 'update', $userId, "--role=$wpRole", "--path=$sitePath", '--allow-root'];
    foreach ([
        'email' => 'user_email', 'displayName' => 'display_name',
        'firstName' => 'first_name', 'lastName' => 'last_name',
        'websiteUrl' => 'user_url', 'bio' => 'description',
    ] as $inputKey => $wpField) {
        $value = $input[$inputKey] ?? null;
        if (is_string($value) && $value !== '') {
            $updateArgs[] = "--$wpField=$value";
        }
    }
    [$code, $out, $err] = runWp($updateArgs);
    if ($code !== 0) {
        respond(500, ['error' => '著者の更新に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    respond(200, ['userId' => $userId]);
}

if ($path === '/wp-cli/find-author' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $email = (string) ($input['email'] ?? '');

    if (!isValidSlug($slug) || $email === '' || !str_contains($email, '@')) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['user', 'list', "--search=$email", '--fields=ID', '--format=json', "--path=$sitePath", '--allow-root']);
    $existing = $code === 0 ? (json_decode($out, true) ?: []) : [];
    $userId = !empty($existing) ? (string) $existing[0]['ID'] : null;

    respond(200, ['userId' => $userId]);
}

if ($path === '/wp-cli/post' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $title = (string) ($input['title'] ?? '');
    $status = (string) ($input['status'] ?? '');
    $existingPostId = $input['existingPostId'] ?? null;
    $postSlug = (string) ($input['postSlug'] ?? '');
    $categoryIds = is_array($input['categoryIds'] ?? null) ? array_values($input['categoryIds']) : [];
    $tagIds = is_array($input['tagIds'] ?? null) ? array_values($input['tagIds']) : [];
    $featuredMediaId = $input['featuredMediaId'] ?? null;
    $authorId = $input['authorId'] ?? null;
    $htmlContent = (string) ($input['htmlContent'] ?? '');
    $publishScheduledAt = (string) ($input['publishScheduledAt'] ?? '');

    if (!isValidSlug($slug) || $title === '' || $status === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }

    // publishScheduledAtが未指定ならnullのまま(予約投稿ではない通常の作成/更新)。
    // SSH経路(WordPressSshOperations)と同じくpost_date/post_date_gmtの両方にUTC値を送る
    // (post updateは未指定フィールドを既存値のまま引き継ぐため、post_date_gmtだけでは
    // サイトのローカル時刻を表示するpost_dateが更新前の値に取り残される。issue #1003)。
    $scheduledDateArg = null;
    if ($publishScheduledAt !== '') {
        try {
            $scheduledDate = new DateTime($publishScheduledAt);
        } catch (Exception $e) {
            respond(400, ['error' => "publishScheduledAtの形式が不正です: $publishScheduledAt"]);
        }
        $scheduledDate->setTimezone(new DateTimeZone('UTC'));
        $scheduledDateArg = $scheduledDate->format('Y-m-d H:i:s');
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    // existingPostIdはAPI側(lets_blog.posts)が前回投稿時に記憶したWordPress投稿IDだが、
    // WordPress側のサイト再構築/DBリセット等で当該投稿が消失していると`post update`が
    // 「無効な投稿 ID です」で失敗し、投稿自体ができなくなる(issue #487)。更新対象が実在するか
    // 事前確認し、存在しなければ新規作成として扱う(自己修復。次回以降は新しいIDが記憶される)。
    if ($existingPostId !== null) {
        [$existsCode, , ] = runWp(['post', 'get', (string) $existingPostId, '--field=ID', "--path=$sitePath", '--allow-root']);
        if ($existsCode !== 0) {
            error_log("[wp-cli/post] existingPostId=" . var_export($existingPostId, true)
                . " はWordPress側に存在しないため新規作成として扱います");
            $existingPostId = null;
        }
    }

    $subArgs = $existingPostId !== null ? ['post', 'update', (string) $existingPostId, '-'] : ['post', 'create', '-'];
    $subArgs[] = "--post_title=$title";
    $subArgs[] = "--post_status=$status";
    if ($scheduledDateArg !== null) {
        $subArgs[] = "--post_date=$scheduledDateArg";
        $subArgs[] = "--post_date_gmt=$scheduledDateArg";
    }
    if ($postSlug !== '') {
        $subArgs[] = "--post_name=$postSlug";
    }
    if (!empty($categoryIds)) {
        $subArgs[] = '--post_category=' . implode(',', $categoryIds);
    }
    if (!empty($tagIds)) {
        $subArgs[] = '--tax_input=' . json_encode(['post_tag' => array_map('intval', $tagIds)]);
    }
    if ($authorId !== null) {
        $subArgs[] = '--post_author=' . $authorId;
    }
    $subArgs[] = '--porcelain';
    $subArgs[] = "--path=$sitePath";
    $subArgs[] = '--allow-root';

    error_log("[wp-cli/post] slug=$slug existingPostId=" . var_export($existingPostId, true)
        . " featuredMediaId=" . var_export($featuredMediaId, true) . " args=" . implode(' ', $subArgs));

    [$code, $out, $err] = runWpWithStdin($subArgs, $htmlContent);
    if ($code !== 0) {
        error_log("[wp-cli/post] post create/update失敗: code=$code detail=" . combinedOutput($out, $err));
        respond(500, ['error' => '投稿の作成/更新に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    // $existingPostIdはinputそのまま(更新対象)、新規作成時は$out(porcelain出力のstdoutのみ、
    // stderrへのPHP Warning等が混ざらないよう分離済み)を投稿IDとして使う。
    $postId = $existingPostId !== null ? (string) $existingPostId : $out;

    // `wp post create/update`の--post_thumbnailはwp_insert_post()の認識するフィールドではなく
    // 黙って無視される(_thumbnail_id postmetaが更新されない)ため、明示的に`post meta update`で設定する。
    if ($featuredMediaId !== null) {
        [$thumbCode, $thumbOut, $thumbErr] = runWp(['post', 'meta', 'update', $postId, '_thumbnail_id', (string) $featuredMediaId, "--path=$sitePath", '--allow-root']);
        if ($thumbCode !== 0) {
            error_log("[wp-cli/post] postId=$postId のアイキャッチ設定に失敗: code=$thumbCode detail=" . combinedOutput($thumbOut, $thumbErr));
            respond(500, ['error' => 'アイキャッチ(featured media)の設定に失敗しました', 'detail' => combinedOutput($thumbOut, $thumbErr)]);
        }
        error_log("[wp-cli/post] postId=$postId のアイキャッチをmediaId=$featuredMediaId に設定しました");
    }

    [$code, $out, $err] = runWp(['post', 'get', $postId, '--fields=guid,post_status', '--format=json', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => '作成/更新した投稿の情報取得に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $post = json_decode($out, true) ?: [];
    respond(200, ['postId' => $postId, 'guid' => $post['guid'] ?? '', 'status' => $post['post_status'] ?? '']);
}

if ($path === '/wp-cli/post-delete' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $postId = (string) ($input['postId'] ?? '');

    if (!isValidSlug($slug) || $postId === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    // --forceを付けない = WordPressコアのwp_delete_post()既定挙動(ゴミ箱対応の投稿タイプはゴミ箱へ移動)に委ねる。
    // --yesは付けない。`wp post delete`は確認プロンプトを出さず、このフラグを受け付けない
    // (`wp db reset`等のコマンド専用)。渡すと"unknown --yes parameter"で必ず失敗する(issue #1001)。
    [$code, $out, $err] = runWp(['post', 'delete', $postId, "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => '投稿の削除に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    respond(200, ['postId' => $postId]);
}

// API側(lets_blog.posts)が記憶している投稿IDが、WordPress側で削除される等で実在しなくなって
// いないかを確認するための読み取り専用エンドポイント。API側はこれを使って、その投稿と一緒に
// アップロードした画像の再利用キャッシュを信頼してよいか判断する(issue #493)。
// メディア(添付ファイル)もpost_type=attachmentのwp_postsレコードとして保存されているため、
// `wp post get`は投稿IDだけでなくメディアIDでも同じように動作する。API側はこれを利用して、
// 個々のメディアがメディアライブラリから削除されていないかの確認にもこのエンドポイントを
// 再利用している(issue #495)。
if ($path === '/wp-cli/post-exists' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $postId = (string) ($input['postId'] ?? '');

    if (!isValidSlug($slug) || $postId === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, , ] = runWp(['post', 'get', $postId, '--field=ID', "--path=$sitePath", '--allow-root']);
    respond(200, ['exists' => $code === 0]);
}

if ($path === '/wp-cli/post-list' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $postType = (string) ($input['postType'] ?? 'post');

    if (!isValidSlug($slug) || !in_array($postType, ['post', 'page'], true)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['post', 'list', "--post_type=$postType",
        '--fields=ID,post_title,post_name,post_status', '--format=json', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => '投稿/ページ一覧の取得に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $items = json_decode($out, true) ?: [];
    $posts = array_map(function ($item) {
        return [
            'id' => (string) ($item['ID'] ?? ''),
            'title' => $item['post_title'] ?? '',
            'slug' => $item['post_name'] ?? '',
            'status' => $item['post_status'] ?? '',
        ];
    }, $items);
    respond(200, ['posts' => $posts]);
}

if ($path === '/wp-cli/post-status-update' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $postId = (string) ($input['postId'] ?? '');
    $status = (string) ($input['status'] ?? '');

    if (!isValidSlug($slug) || $postId === '' || $status === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['post', 'update', $postId, "--post_status=$status", "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => '投稿/ページのステータス変更に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    respond(200, ['postId' => $postId, 'status' => $status]);
}

// 記事プレビュー(ローカル/テスト環境)で、非公開(private)投稿として作成したプレビュー記事の
// 実ページをPlaywright側から閲覧するための認証Cookieを発行する。private投稿は未ログインの
// 訪問者には表示されないため、指定ユーザー(サイト管理者)としてログイン済みと同等のCookieを
// wp_generate_auth_cookie()で生成し、呼び出し側(Java)がブラウザコンテキストへ注入する。
if ($path === '/wp-cli/generate-auth-cookie' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $userLogin = (string) ($input['userLogin'] ?? '');

    if (!isValidSlug($slug) || $userLogin === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    $userLoginLiteral = var_export($userLogin, true);
    $phpCode = "\$u = get_user_by('login', $userLoginLiteral); "
        . "if (!\$u) { echo json_encode(['error' => 'user_not_found']); exit; } "
        . "echo json_encode(['name' => LOGGED_IN_COOKIE, "
        . "'value' => wp_generate_auth_cookie(\$u->ID, time() + 3600, 'logged_in')]);";

    [$code, $out, $err] = runWp(['eval', $phpCode, "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => '認証Cookieの生成に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $result = json_decode($out, true);
    if (!is_array($result) || isset($result['error']) || empty($result['name']) || empty($result['value'])) {
        respond(404, ['error' => "ユーザー '$userLogin' が見つかりません"]);
    }
    respond(200, ['name' => $result['name'], 'value' => $result['value']]);
}

// 記事プレビュー(ArticlePreviewService)のテーマCSS/DOM取得(スクレイプ&スプライス)向けに、
// サイト内の最新公開記事を「参照記事」として返す。従来は認証なしのWordPress REST API
// (wp-json/wp/v2/posts)を直接叩いていたが、managed WordPressサイトは他の全操作と同じく
// wp-cli経由に揃える(issue #519)。title/contentはREST版のtitle.rendered/content.rendered相当
// (the_title/the_contentフィルタ適用後)になるよう、wp-cliのpost系コマンドではなくwp evalで
// WordPressコアのAPI(get_posts/get_permalink/apply_filters)を直接呼び出す。
if ($path === '/wp-cli/reference-post' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');

    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    $phpCode = "\$posts = get_posts(['numberposts' => 1, 'post_status' => 'publish', "
        . "'orderby' => 'date', 'order' => 'DESC']); "
        . "if (empty(\$posts)) { echo json_encode(['found' => false]); exit; } "
        . "\$post = \$posts[0]; "
        . "echo json_encode(['found' => true, 'id' => (string) \$post->ID, "
        . "'link' => get_permalink(\$post->ID), "
        . "'title' => apply_filters('the_title', \$post->post_title, \$post->ID), "
        . "'content' => apply_filters('the_content', \$post->post_content)]);";

    [$code, $out, $err] = runWp(['eval', $phpCode, "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => '参照記事の取得に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $result = json_decode($out, true);
    if (!is_array($result)) {
        respond(500, ['error' => '参照記事の取得結果を解析できませんでした', 'detail' => $out]);
    }
    respond(200, $result);
}

// ガベージコレクション画面(issue #500)向けにメディアライブラリの一覧を取得する。添付ファイルも
// post_type=attachmentのwp_postsレコードのため`wp post list`で取得できる。ゴミ箱にあるメディアも
// 「蓄積した不要メディア」の掃除対象に含めるため、既定(inherit)に加えprivate/trashも対象とする。
if ($path === '/wp-cli/media-list' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');

    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['post', 'list', '--post_type=attachment',
        '--post_status=inherit,private,trash',
        '--fields=ID,post_title,guid,post_mime_type,post_date', '--format=json', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => 'メディア一覧の取得に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $items = json_decode($out, true) ?: [];
    $media = array_map(function ($item) {
        return [
            'id' => (string) ($item['ID'] ?? ''),
            'title' => $item['post_title'] ?? '',
            'guid' => $item['guid'] ?? '',
            'mimeType' => $item['post_mime_type'] ?? '',
            'uploadedAt' => $item['post_date'] ?? '',
        ];
    }, $items);
    respond(200, ['media' => $media]);
}

// ガベージコレクション画面(issue #500)向けに、公開投稿タイプ全件の本文/アイキャッチと、
// 主要なサイト設定(サイトアイコン・カスタムロゴ・ヘッダー/背景画像)が参照する添付ファイルIDを
// 1回のwp eval呼び出しでまとめて取得する。SSH側(WordPressSshOperations#scanMediaReferences)と
// 同一のPHPコードで、投稿タイプは['post','page']に固定せず動的に取得する(カスタム投稿タイプに
// 埋め込まれたメディアを誤って「未参照」と判定し削除してしまうリスクを避けるため)。
if ($path === '/wp-cli/media-reference-scan' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');

    if (!isValidSlug($slug)) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    $phpCode = "\$types = get_post_types(['public' => true], 'names'); "
        . "unset(\$types['attachment']); \$types = array_values(\$types); "
        . "\$posts = get_posts(['post_type' => \$types, "
        . "'post_status' => ['publish','future','draft','pending','private'], 'numberposts' => -1]); "
        . "\$items = array_map(function(\$p) { return ['id' => (string) \$p->ID, "
        . "'postType' => \$p->post_type, 'status' => \$p->post_status, 'content' => \$p->post_content, "
        . "'thumbnailId' => (string) get_post_thumbnail_id(\$p->ID)]; }, \$posts); "
        . "\$headerData = get_theme_mod('header_image_data'); "
        . "\$headerUrl = get_theme_mod('header_image'); "
        . "\$headerId = (is_object(\$headerData) && isset(\$headerData->attachment_id)) "
        . "? (string) \$headerData->attachment_id "
        . ": (\$headerUrl ? (string) attachment_url_to_postid(\$headerUrl) : ''); "
        . "\$bgUrl = get_theme_mod('background_image'); "
        . "\$bgId = \$bgUrl ? (string) attachment_url_to_postid(\$bgUrl) : ''; "
        . "\$settings = ['site_icon' => (string) get_option('site_icon'), "
        . "'custom_logo' => (string) get_theme_mod('custom_logo'), "
        . "'header_image' => \$headerId, 'background_image' => \$bgId]; "
        . "echo json_encode(['posts' => \$items, 'settings' => \$settings]);";

    [$code, $out, $err] = runWp(['eval', $phpCode, "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => 'メディア参照スキャンに失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $result = json_decode($out, true);
    if (!is_array($result)) {
        respond(500, ['error' => 'メディア参照スキャン結果を解析できませんでした', 'detail' => $out]);
    }
    respond(200, $result);
}

// メディア(添付ファイル)を完全に削除する(issue #500)。post-deleteと異なり`--force`を付けて
// ゴミ箱を経由せず物理削除する(アップロード済みファイルも合わせて削除される)。
// post-deleteと同じ理由で`--yes`は付けない(issue #1001)。
if ($path === '/wp-cli/media-delete' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($input['slug'] ?? '');
    $mediaId = (string) ($input['mediaId'] ?? '');

    if (!isValidSlug($slug) || $mediaId === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    [$code, $out, $err] = runWp(['post', 'delete', $mediaId, '--force', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => 'メディアの削除に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    respond(200, ['mediaId' => $mediaId]);
}

if ($path === '/wp-cli/media-upload' && $_SERVER['REQUEST_METHOD'] === 'POST') {
    $slug = (string) ($_POST['slug'] ?? '');
    if (!isValidSlug($slug) || empty($_FILES['file'])) {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    $originalName = (string) ($_FILES['file']['name'] ?? 'upload');
    $safeName = preg_replace('/[^A-Za-z0-9._-]/', '_', basename($originalName));
    $safeName = $safeName !== '' && $safeName !== null ? $safeName : 'upload';
    $tmpPath = '/tmp/letsblog-media-' . bin2hex(random_bytes(8)) . '-' . $safeName;
    if (!move_uploaded_file($_FILES['file']['tmp_name'], $tmpPath)) {
        respond(500, ['error' => 'アップロードファイルの一時保存に失敗しました']);
    }

    ensureSvgUploadMuPlugin($sitePath);

    [$code, $out, $err] = runWp(['media', 'import', $tmpPath, '--porcelain', "--path=$sitePath", '--allow-root']);
    runCommand(['rm', '-f', $tmpPath]);
    if ($code !== 0) {
        respond(500, ['error' => 'メディアのアップロードに失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $mediaId = $out;

    [$code, $out, $err] = runWp(['post', 'get', $mediaId, '--fields=guid', '--format=json', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => 'アップロードしたメディアの情報取得に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    $media = json_decode($out, true) ?: [];
    runCommand(['chown', '-R', 'www-data:www-data', "$sitePath/wp-content/uploads"]);
    respond(200, ['mediaId' => $mediaId, 'guid' => $media['guid'] ?? '']);
}

respond(404, ['error' => 'not found']);
