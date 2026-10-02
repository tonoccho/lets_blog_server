package com.letsblog.media.config;

import com.letsblog.common.auth.ServiceTokenClientConfig;
import com.letsblog.common.client.GenerationJobClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * lbs-commonのオプトイン部品を取り込む(#1483)。media-serviceはジョブの作成・進捗更新(`GenerationJobClient`)とそのClient Credentialsトークン(`ServiceTokenClientConfig`)を使う。
 * lbs-commonの該当クラスはコンポーネントスキャン対象ではなく、使うサービスがここで明示的に
 * {@code @Import}する。{@code @Configuration}なのでWebMvcTest等のスライスには載らない
 * (従来の{@code @Component}/{@code ServiceTokenClientConfig}と同じ)。
 */
@Configuration
@Import({ServiceTokenClientConfig.class, GenerationJobClient.class})
public class CommonClientImportConfig {
}
