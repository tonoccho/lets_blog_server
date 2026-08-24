package com.letsblog.content.dto;

/** legacy-api側のPostDeleteServiceが投稿削除(ゴミ箱移動)をposts行へ反映するための内部ブリッジリクエスト。 */
public record MarkTrashedRequest(Long siteId, String wpPostId) {
}
