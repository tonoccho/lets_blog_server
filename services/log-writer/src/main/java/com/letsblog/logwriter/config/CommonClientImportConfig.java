package com.letsblog.logwriter.config;

import com.letsblog.common.client.GenerationJobClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * lbs-commonのオプトイン部品を取り込む(#1483)。log-writerは統合操作ログのAI_JOB取得(`GenerationJobClient#listRecent`のみ)を使う。ServiceTokenClientは不要。
 * lbs-commonの該当クラスはコンポーネントスキャン対象ではなく、使うサービスがここで明示的に
 * {@code @Import}する。{@code @Configuration}なのでWebMvcTest等のスライスには載らない
 * (従来の{@code @Component}/{@code ServiceTokenClientConfig}と同じ)。
 */
@Configuration
@Import({GenerationJobClient.class})
public class CommonClientImportConfig {
}
