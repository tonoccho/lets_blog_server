package com.letsblog.publishing.config;

import com.letsblog.common.auth.ServiceTokenClientConfig;
import com.letsblog.common.client.GenerationJobClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * lbs-commonのオプトイン部品を取り込む(issue #1702)。project-serviceの同名クラス(#1479)と同じく、
 * {@code GenerationJobClient}と{@code ServiceTokenClientConfig}(#1483でlbs-commonへ統合済み)を
 * {@code @Import}するだけで、複製はしない。publishing-serviceはジョブの作成と、進捗・終端の更新
 * (サービス自身のトークン)の両方を使う。
 */
@Configuration
@Import({ServiceTokenClientConfig.class, GenerationJobClient.class})
public class CommonClientImportConfig {
}
