package com.letsblog.logwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ログメッセージキューイング(issue #466)のコンシューマー側サーバー。RabbitMQからエラーログ・
 * 操作ログ・監査ログのメッセージを受信し、apiサーバーと共有するDB(既存のFlywayマイグレーション済み
 * スキーマ)へ書き込む。スキーマ自体の所有権はapiサーバー側のFlywayマイグレーションにあるため、
 * このアプリケーションはFlywayを実行しない(application.ymlでspring.flyway.enabled=false)。
 */
@SpringBootApplication
public class LogWriterApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogWriterApplication.class, args);
    }
}
