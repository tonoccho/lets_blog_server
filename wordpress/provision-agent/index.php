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

    [$code, $out, $err] = runWp(['core', 'download', "--path=$sitePath", "--locale=$locale", '--allow-root']);
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

        // 各環境の管理者・プロジェクトメンバーアカウント(wp_users/wp_usermeta)は
        // ProjectUserSyncServiceが環境ごとに個別管理しているため、DB同期の対象から除外する
        $dumpCmd = 'mysqldump --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
            . ' --ignore-table=' . escapeshellarg("$fromDbName.wp_users")
            . ' --ignore-table=' . escapeshellarg("$fromDbName.wp_usermeta")
            . ' ' . escapeshellarg($fromDbName);
        $importCmd = 'mysql --skip-ssl -h' . escapeshellarg($dbHost) . ' -uroot -p' . escapeshellarg($rootPassword)
            . ' ' . escapeshellarg($toDbName);
        [$code, $out, $err] = runCommand(['sh', '-c', "$dumpCmd | $importCmd"]);
        if ($code !== 0) {
            respond(500, ['error' => 'DBの同期に失敗しました', 'detail' => combinedOutput($out, $err)]);
        }

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

    if (!isValidSlug($slug) || $title === '' || $status === '') {
        respond(400, ['error' => 'パラメータが不正です']);
    }
    $sitePath = resolveExistingSitePath($slug);
    if ($sitePath === null) {
        respond(404, ['error' => "サイト '$slug' が見つかりません"]);
    }

    $subArgs = $existingPostId !== null ? ['post', 'update', (string) $existingPostId, '-'] : ['post', 'create', '-'];
    $subArgs[] = "--post_title=$title";
    $subArgs[] = "--post_status=$status";
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

    // --forceを付けない = WordPressコアのwp_delete_post()既定挙動(ゴミ箱対応の投稿タイプはゴミ箱へ移動)に委ねる
    [$code, $out, $err] = runWp(['post', 'delete', $postId, '--yes', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        respond(500, ['error' => '投稿の削除に失敗しました', 'detail' => combinedOutput($out, $err)]);
    }
    respond(200, ['postId' => $postId]);
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
