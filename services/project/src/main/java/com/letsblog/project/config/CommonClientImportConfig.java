package com.letsblog.project.config;

import com.letsblog.common.auth.ServiceTokenClientConfig;
import com.letsblog.common.client.GenerationJobClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * lbs-commonのオプトイン部品を取り込む(issue #1479)。
 *
 * <p><b>複製せず共通部品を使う理由</b>: 当初のIssueは「media/publishingに続く3本目の手書き複製にするか、
 * lbs-commonへ共通化するか」を実装判断としていたが、#1483で{@code GenerationJobClient}と
 * {@code ServiceTokenClientConfig}は既にlbs-commonへ統合済み(使うサービスが{@code @Import}する
 * オプトイン方式)。3本目を作ると統合を巻き戻すだけなので、media-serviceの
 * {@code CommonClientImportConfig}と同じく取り込むだけにした。project-serviceはジョブの作成と
 * 進捗・終端の更新(サービス自身のトークン)の両方を使う。
 */
@Configuration
@Import({ServiceTokenClientConfig.class, GenerationJobClient.class})
public class CommonClientImportConfig {
}
