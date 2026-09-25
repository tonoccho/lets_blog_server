package com.letsblog.media.client;

/**
 * legacy-apiの{@code com.letsblog.api.cms.CmsMediaSummary}を写したもの(#573 stage3、
 * CmsBridgeClient参照)。ガベージコレクションのメディア一覧用。
 */
public record CmsMediaSummary(String id, String guid, String title, String mimeType, String uploadedAt) {
}
