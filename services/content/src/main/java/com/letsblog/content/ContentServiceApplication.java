package com.letsblog.content;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * content-service(issue #576)。記事本文(posts)・カスタムタグ・Markdownレンダリング・記事プレビュー
 * (VSCode拡張向け)・ブログカード/Amazonタグのコンテンツキャッシュをlegacy-apiから抽出したもの。
 * 公開パイプラインとプレビューの両方から使われる中核ドメイン。lbs_contentスキーマ(ADR-0004)を
 * 所有する。埋め込みタグ(PlantUML/Recharts)の実際のダイアグラム描画はmedia-serviceへ委譲し、
 * [blogcard]/[amazon]タグのスクレイピングと記事プレビューのテーマ骨格取得用のヘッドレスブラウザ
 * (Playwright)は本サービスが持つ。
 *
 * <p>lbs-commonライブラリのAuditLogMessage/LogExchanges/ErrorResponse等は素のクラス(Spring Bean
 * ではない)として直接importして使うだけのため、log-writer/media-serviceと同じくscanBasePackagesは
 * 既定(com.letsblog.contentのみ)のままにしている。ai-serviceがcom.letsblog.commonを追加でスキャン
 * しているのはCredentialCipher(project_ai_settingsの暗号化に使う、唯一のSpring Bean)を使うためだが、
 * 本サービスは暗号化が必要な認証情報を持たないため不要(スキャンするとapp.encryption-key未設定で
 * 起動時にBeanCreationExceptionになる)。
 */
@SpringBootApplication
public class ContentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentServiceApplication.class, args);
    }
}
