package com.letsblog.api.dto;

/**
 * サイト+スラッグに対応する既存投稿の照会結果(issue #505)。
 * VSCode拡張がfront matterのwp_post_ids(廃止)に頼らず、DB(postsテーブル)側の情報から
 * 既存投稿の有無・WordPress投稿IDを取得するために使う。
 */
public record PostLookupResponse(String wpPostId, String status) {
}
