package com.letsblog.logwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ログメッセージキューイング(issue #466)のコンシューマー側サーバー。RabbitMQからエラーログ・
 * 操作ログ・監査ログのメッセージを受信し、自身が所有するlbs_logスキーマへ書き込む。
 *
 * <p>issue #572でログの所有権をlegacy-apiから完全移管し、スキーマ(lbs_log、ADR-0004)・
 * Flywayマイグレーション・読み取りAPI(監査ログ・操作ログ・フロントエンドエラーログ)の
 * すべてを本サービスが持つようになった。
 *
 * <p>{@code @EnableScheduling}は、{@link com.letsblog.logwriter.service.AuditLogService}と
 * {@link com.letsblog.logwriter.service.OperationLogService}の保存期間による削除(@Scheduled)を
 * 動かすために必要(issue #1725。これが無いと@Scheduledは黙って登録されない)。
 */
@SpringBootApplication
@EnableScheduling
public class LogWriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogWriterApplication.class, args);
    }
}
