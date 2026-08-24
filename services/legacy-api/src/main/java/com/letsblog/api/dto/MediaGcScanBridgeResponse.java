package com.letsblog.api.dto;

import com.letsblog.api.cms.CmsMediaReferenceScan;
import com.letsblog.api.cms.CmsMediaSummary;

import java.util.List;

/**
 * media-serviceのMediaGarbageCollectionServiceが呼ぶ内部ブリッジ
 * ({@code GET /api/internal/cms/projects/{projectId}/media-scan}、issue #573 stage3)の応答。
 * 未参照判定の実際のロジック(正規表現によるコンテンツ参照抽出)はmedia-service側へ移設済みで、
 * legacy-apiはCMS(WordPress)からの生データ取得のみを担う。
 */
public record MediaGcScanBridgeResponse(List<CmsMediaSummary> media, CmsMediaReferenceScan refs) {
}
