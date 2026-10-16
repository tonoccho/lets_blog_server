package com.letsblog.ai.config;

import com.letsblog.common.auth.ToolsTokenProviderConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * extensionsのツール(Ollama)を呼ぶときに付ける専用クライアントのトークン(#1804)を取り込む。
 * lbs-commonの{@link ToolsTokenProviderConfig}はオプトインなので、使うサービスがここで{@code @Import}する。
 * {@code KEYCLOAK_TOOLS_CLIENT_SECRET}が空ならトークンは付かない。
 */
@Configuration
@Import(ToolsTokenProviderConfig.class)
public class ToolsTokenConfig {
}
