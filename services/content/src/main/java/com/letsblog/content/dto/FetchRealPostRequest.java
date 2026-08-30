package com.letsblog.content.dto;

/** {@link com.letsblog.content.service.PreviewSkeletonFetcher#fetchRealPost}への内部ブリッジリクエスト。 */
public record FetchRealPostRequest(String url, String cookieName, String cookieValue) {
}
