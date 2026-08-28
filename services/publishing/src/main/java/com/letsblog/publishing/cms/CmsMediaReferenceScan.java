package com.letsblog.publishing.cms;

import java.util.List;
import java.util.Map;

/**
 * ガベージコレクション画面のメディア参照スキャン結果(issue #500)。
 * settingsMediaIdsのキーは"site_icon"/"custom_logo"/"header_image"/"background_image"、
 * 値は解決済みの添付ファイルID(未設定/解決不能時は空文字)。
 */
public record CmsMediaReferenceScan(List<CmsPostContentSummary> posts, Map<String, String> settingsMediaIds) {
}
