package com.letsblog.media.client;

import java.util.List;

/** {@link CmsBridgeClient#scanMedia}の戻り値。legacy-apiのMediaGcScanBridgeResponseに対応する。 */
public record MediaGcScanResult(List<CmsMediaSummary> media, CmsMediaReferenceScan refs) {
}
