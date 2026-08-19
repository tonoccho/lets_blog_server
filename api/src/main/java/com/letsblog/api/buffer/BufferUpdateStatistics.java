package com.letsblog.api.buffer;

/**
 * Buffer側の1件のupdate(SNSプラットフォームごとの個別投稿)の統計(issue #390)。
 * favorites=いいね、shares=シェア/リツイート、comments=コメント/メンション、clicks=リンククリック数。
 */
public record BufferUpdateStatistics(long clicks, long favorites, long comments, long shares) {
}
