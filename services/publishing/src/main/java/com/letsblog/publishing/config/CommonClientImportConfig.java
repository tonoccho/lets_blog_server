package com.letsblog.publishing.config;

import com.letsblog.common.auth.ServiceTokenClientConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * lbs-commonのオプトイン部品を取り込む(#1483)。publishing-serviceはcontent-service呼び出しでClient Credentialsトークン(`ServiceTokenClientConfig`、#1207)を使う。
 * lbs-commonの該当クラスはコンポーネントスキャン対象ではなく、使うサービスがここで明示的に
 * {@code @Import}する。{@code @Configuration}なのでWebMvcTest等のスライスには載らない
 * (従来の{@code @Component}/{@code ServiceTokenClientConfig}と同じ)。
 */
@Configuration
@Import({ServiceTokenClientConfig.class})
public class CommonClientImportConfig {
}
