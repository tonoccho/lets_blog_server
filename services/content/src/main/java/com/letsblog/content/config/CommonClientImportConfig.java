package com.letsblog.content.config;

import com.letsblog.common.auth.ServiceTokenClientConfig;
import com.letsblog.common.client.GenerationJobClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * lbs-commonのオプトイン部品を取り込む(issue #1409)。カスタムタグ生成ジョブの作成と、進捗・完了・失敗の
 * 更新(サービス自身のClient Credentialsトークン。#1083)に使う。media-service・project-serviceの
 * {@code CommonClientImportConfig}と同じ(#1483のオプトイン方式)。
 */
@Configuration
@Import({ServiceTokenClientConfig.class, GenerationJobClient.class})
public class CommonClientImportConfig {
}
