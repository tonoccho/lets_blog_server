package com.letsblog.common.messaging;

import java.io.Serializable;
import java.time.Instant;

/**
 * ユーザーが無効化された(issue #580)。発行元: identity-service(UserService#deactivate)。
 * 購読: 全サービス(ユーザー単位の権限キャッシュ・認可情報を持つサービスは、このイベントを受けて
 * キャッシュを破棄する)。
 *
 * <p>本PR時点では権限キャッシュを実装しているサービスがまだ存在しないため、実際の破棄対象は
 * 将来の課題(各サービスがキャッシュを持つようになった時点でこのイベントを購読する)。
 * content-serviceのみ、冪等な受信記録(監査目的)としてこのイベントを購読する。
 */
public record UserDeactivatedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String keycloakSub
) implements DomainEvent, Serializable {
}
