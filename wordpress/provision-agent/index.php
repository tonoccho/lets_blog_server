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
 * escapeshellargで各トークンを個別にエスケープしてからシェルへ渡す(コマンドインジェクション対策)。
 * @param string[] $args
 * @return array{0:int,1:string}
 */
function runCommand(array $args): array
{
    $command = implode(' ', array_map('escapeshellarg', $args)) . ' 2>&1';
    exec($command, $output, $exitCode);
    return [$exitCode, implode("\n", $output)];
}

/**
 * wp-cli(phar)実行時、PHP CLIのデフォルトmemory_limitではWordPressコア展開時に
 * メモリ不足になることがあるため、明示的に緩和した上で実行する。
 * @param string[] $args wp-cliへのサブコマンド以降の引数
 * @return array{0:int,1:string}
 */
function runWp(array $args): array
{
    return runCommand(array_merge(['php', '-d', 'memory_limit=512M', '/usr/local/bin/wp'], $args));
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

    [$code, $out] = runCommand(['mkdir', '-p', $sitePath]);
    if ($code !== 0) {
        respond(500, ['error' => 'ディレクトリ作成に失敗しました', 'detail' => $out]);
    }

    $createDbSql = sprintf(
        "CREATE DATABASE IF NOT EXISTS `%s`; GRANT ALL PRIVILEGES ON `%s`.* TO '%s'@'%%'; FLUSH PRIVILEGES;",
        $dbName, $dbName, $dbUser
    );
    [$code, $out] = runCommand(['mysql', '--skip-ssl', '-h', $dbHost, '-uroot', "-p$rootPassword", '-e', $createDbSql]);
    if ($code !== 0) {
        runCommand(['rm', '-rf', $sitePath]);
        respond(500, ['error' => 'データベース作成に失敗しました', 'detail' => $out]);
    }

    [$code, $out] = runWp(['core', 'download', "--path=$sitePath", "--locale=$locale", '--allow-root']);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'WordPressコアのダウンロードに失敗しました', 'detail' => $out], $sitePath, $dbName, $dbHost, $rootPassword);
    }

    [$code, $out] = runWp([
        'config', 'create',
        "--path=$sitePath",
        "--dbname=$dbName",
        "--dbuser=$dbUser",
        "--dbpass=$dbPassword",
        "--dbhost=$dbHost",
        '--allow-root',
    ]);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'wp-config.php作成に失敗しました', 'detail' => $out], $sitePath, $dbName, $dbHost, $rootPassword);
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

    [$code, $out] = runWp([
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
        cleanupAndRespond(500, ['error' => 'WordPressのインストールに失敗しました', 'detail' => $out], $sitePath, $dbName, $dbHost, $rootPassword);
    }

    // パーマリンクを「投稿名」構造にする(デフォルトの「基本」のままでは
    // /wp-json/ のようなpretty permalink形式のREST APIパスが404になるため必須)。
    [$code, $out] = runWp(['rewrite', 'structure', '/%postname%/', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'パーマリンク設定に失敗しました', 'detail' => $out], $sitePath, $dbName, $dbHost, $rootPassword);
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

    [$code, $out] = runWp([
        'user', 'application-password', 'create',
        "--path=$sitePath",
        $adminUser, 'letsblog', '--porcelain', '--allow-root',
    ]);
    if ($code !== 0) {
        cleanupAndRespond(500, ['error' => 'アプリケーションパスワードの発行に失敗しました', 'detail' => $out], $sitePath, $dbName, $dbHost, $rootPassword);
    }
    $applicationPassword = trim($out);

    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);

    respond(200, [
        'url' => $siteUrl,
        'adminUser' => $adminUser,
        'applicationPassword' => $applicationPassword,
    ]);
}

const ALLOWED_SYNC_TARGETS = ['themes', 'plugins', 'db'];

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
        respond(400, ['error' => 'targetsが不正です(themes/plugins/dbのいずれかを指定してください)']);
    }

    $fromPath = "/var/www/html/sites/$fromSlug";
    $toPath = "/var/www/html/sites/$toSlug";
    if (!is_dir($fromPath) || !is_dir($toPath)) {
        respond(404, ['error' => '同期元または同期先のサイトが見つかりません']);
    }

    $backupDir = "/var/www/html/backups/$toSlug";
    runCommand(['mkdir', '-p', $backupDir]);
    $timestamp = date('Ymd-His');

    foreach (['themes', 'plugins'] as $type) {
        if (!in_array($type, $targets, true)) {
            continue;
        }
        $fromContentPath = "$fromPath/wp-content/$type";
        $toContentPath = "$toPath/wp-content/$type";
        if (!is_dir($fromContentPath)) {
            continue;
        }
        runCommand(['tar', '-czf', "$backupDir/{$type}-{$timestamp}.tar.gz", '-C', "$toPath/wp-content", $type]);
        runCommand(['rm', '-rf', $toContentPath]);
        [$code, $out] = runCommand(['cp', '-r', $fromContentPath, $toContentPath]);
        if ($code !== 0) {
            respond(500, ['error' => "{$type}の同期に失敗しました", 'detail' => $out]);
        }
    }

    if (in_array('db', $targets, true)) {
        // 上書きされる側(同期先)のバックアップを先に取得しておく
        runCommand(['sh', '-c',
            'mysqldump --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
                . ' ' . escapeshellarg($toDbName) . ' > ' . escapeshellarg("$backupDir/db-{$timestamp}.sql")]);

        // 各環境の管理者・プロジェクトメンバーアカウント(wp_users/wp_usermeta)は
        // ProjectUserSyncServiceが環境ごとに個別管理しているため、DB同期の対象から除外する
        $dumpCmd = 'mysqldump --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
            . ' --ignore-table=' . escapeshellarg("$fromDbName.wp_users")
            . ' --ignore-table=' . escapeshellarg("$fromDbName.wp_usermeta")
            . ' ' . escapeshellarg($fromDbName);
        $importCmd = 'mysql --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
            . ' ' . escapeshellarg($toDbName);
        [$code, $out] = runCommand(['sh', '-c', "$dumpCmd | $importCmd"]);
        if ($code !== 0) {
            respond(500, ['error' => 'DBの同期に失敗しました', 'detail' => $out]);
        }

        // コピー元のURLがwp_options等に焼き込まれたままになるため、コピー先自身のURLへ書き戻す
        $fromUrl = "https://localhost/sites/$fromSlug";
        $toUrl = "https://localhost/sites/$toSlug";
        [$code, $out] = runWp(['search-replace', $fromUrl, $toUrl, '--all-tables', "--path=$toPath", '--allow-root']);
        if ($code !== 0) {
            respond(500, ['error' => 'URL書き換え(search-replace)に失敗しました', 'detail' => $out]);
        }
    }

    runCommand(['chown', '-R', 'www-data:www-data', $toPath]);
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

respond(404, ['error' => 'not found']);
