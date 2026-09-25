package com.letsblog.media.client;

import java.util.List;
import java.util.Map;

/**
 * legacy-apiの{@code com.letsblog.api.cms.CmsMediaReferenceScan}を写したもの(#573 stage3)。
 * settingsMediaIdsのキーは"site_icon"/"custom_logo"/"header_image"/"background_image"、
 * 値は解決済みの添付ファイルID(未設定/解決不能時は空文字)。
 */
public record CmsMediaReferenceScan(List<CmsPostContentSummary> posts, Map<String, String> settingsMediaIds) {
}
