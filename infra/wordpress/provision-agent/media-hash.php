<?php
/**
 * media-upload が _letsblog_sha256 に記録するsha256の算出(issue #1436)。
 *
 * client(publishing-service)が送るsha256は信用しない。誤った値が記録されると、以後の投稿が
 * media-find-by-hash で別内容のメディアを再利用してしまうため、記録値は必ずagentが
 * アップロードされたファイルそのものから計算する。
 */

/**
 * $filePath の内容から小文字16進64桁のsha256を計算する。読めなければnull。
 *
 * @param string|null $clientSha256 client送信値。互換のため受け取るが、結果には一切使わない
 */
function computeMediaSha256(string $filePath, ?string $clientSha256 = null): ?string
{
    $hash = @hash_file('sha256', $filePath);
    return is_string($hash) ? $hash : null;
}
