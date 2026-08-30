package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * 生成画像が利用可能になった(issue #580)。発行元: media-service(GeneratedImageController#create、
 * ComfyUI/ChatGPTでの生成完了後にファイル保存とDB行作成が終わった時点)。購読: content-service。
 *
 * @param imageUrl media-serviceの{@code /api/generated-images/{id}/file}への相対パス
 *                 (サービス間呼び出しの base URL 解決は既存の内部ブリッジ環境変数(MEDIA_SERVICE_URI等)に
 *                 委ねるため、このイベント自体はホストを含まない)
 */
public record ImageGeneratedEvent(
        String eventId,
        Instant occurredAt,
        Long generatedImageId,
        Long projectId,
        String imageUrl
) implements DomainEvent, Serializable {
}
