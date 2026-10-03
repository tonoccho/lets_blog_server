package com.letsblog.fixture.conflict;

import org.springframework.context.annotation.Configuration;

/**
 * identity-serviceの独自{@code ServiceTokenClientConfig}を模した、共通版と同じ単純名の設定クラス(#1596)。
 * 両方がコンポーネントスキャンで拾われるとBean名{@code serviceTokenClientConfig}が衝突する。
 */
@Configuration
public class ServiceTokenClientConfig {
}
