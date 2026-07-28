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

    if (!isValidSlug($slug) || !isValidDbName($dbName) || $adminUser === '' || $adminEmail === '' || $adminPassword === '') {
        respond(400, ['error' => 'パラメータが不正です']);
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

    [$code, $out] = runWp(['core', 'download', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => 'WordPressコアのダウンロードに失敗しました', 'detail' => $out]);
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
        respond(500, ['error' => 'wp-config.php作成に失敗しました', 'detail' => $out]);
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
        respond(500, ['error' => 'WordPressのインストールに失敗しました', 'detail' => $out]);
    }

    // パーマリンクを「投稿名」構造にする(デフォルトの「基本」のままでは
    // /wp-json/ のようなpretty permalink形式のREST APIパスが404になるため必須)。
    [$code, $out] = runWp(['rewrite', 'structure', '/%postname%/', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => 'パーマリンク設定に失敗しました', 'detail' => $out]);
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
        respond(500, ['error' => 'アプリケーションパスワードの発行に失敗しました', 'detail' => $out]);
    }
    $applicationPassword = trim($out);

    runCommand(['chown', '-R', 'www-data:www-data', $sitePath]);

    respond(200, [
        'url' => $siteUrl,
        'adminUser' => $adminUser,
        'applicationPassword' => $applicationPassword,
    ]);
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
