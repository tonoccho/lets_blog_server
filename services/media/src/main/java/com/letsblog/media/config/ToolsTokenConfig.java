package com.letsblog.media.config;

import com.letsblog.common.auth.ToolsTokenProviderConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * extensionsのツール(ComfyUI・PlantUML)を呼ぶときに付ける専用クライアントのトークン(#1804)を取り込む。
 * lbs-commonの{@link ToolsTokenProviderConfig}はオプトインなので、使うサービスがここで{@code @Import}する。
 * {@code KEYCLOAK_TOOLS_CLIENT_SECRET}が空ならトークンは付かない。{@code letsblog-services}の
 * {@code ServiceTokenClient}のBeanとは型が別で、衝突しない。
 */
@Configuration
@Import(ToolsTokenProviderConfig.class)
public class ToolsTokenConfig {
}
