package com.letsblog.api.dto;

import java.util.List;

/** ガベージコレクション画面のスキャン結果(issue #500)。 */
public record MediaGarbageCollectionScanResponse(
        String environment,
        List<UnreferencedMediaItem> items,
        int totalMediaCount,
        int referencedMediaCount,
        int unreferencedMediaCount) {
}
