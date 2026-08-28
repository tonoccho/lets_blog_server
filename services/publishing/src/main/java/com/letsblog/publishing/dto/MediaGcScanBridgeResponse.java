package com.letsblog.publishing.dto;

import com.letsblog.publishing.cms.CmsMediaReferenceScan;
import com.letsblog.publishing.cms.CmsMediaSummary;

import java.util.List;

/**
 * media-service向けメディアGCブリッジ({@link com.letsblog.publishing.controller.CmsMediaBridgeController})の
 * {@code GET /api/internal/cms/projects/{projectId}/media-scan}応答。legacy-apiの
 * {@code com.letsblog.api.dto.MediaGcScanBridgeResponse}をpublishing-serviceへ移設したもの
 * (issue #709、Epic #551 C6-3)。
 */
public record MediaGcScanBridgeResponse(List<CmsMediaSummary> media, CmsMediaReferenceScan refs) {
}
