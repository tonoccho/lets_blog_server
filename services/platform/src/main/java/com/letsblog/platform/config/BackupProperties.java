package com.letsblog.platform.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * BackupServiceが全サービス横断でバックアップ/リストアする際の接続情報(issue #694、C10-2)。
 *
 * <p>各サービスは専用のMySQLユーザー(例: {@code lbs_media})でスキーマ単位にアクセスが分離されている
 * (#570、ADR-0004)ため、バックアップ処理が全スキーマを横断してダンプ/リストアするには、この分離を
 * 横断できる専用の認証情報が必要になる。ここでは全スキーマへ{@code root}相当の権限を持つMySQL
 * rootクレデンシャルではなく、バックアップ専用のMySQLユーザー(既定値{@code lbs_backup}、
 * mysql/init/01-create-service-schemas.shが作成する)を使う。このユーザーは{@link #mysql}の
 * {@code schemas}で列挙されたスキーマ(=アプリ自身が所有する既知のスキーマ)にのみ権限を持ち、
 * mysqlシステムスキーマや他の想定外のデータベースへはアクセスできない(#570のスキーマ分離の
 * 意図を損なわない範囲での最小権限)。
 *
 * <p>Keycloakの認証情報(ユーザー/ロール等)はMySQLとは別インスタンスのPostgreSQL
 * (keycloak-postgres、DB名{@code keycloak})に保存されている。こちらは元々そのDB専用の
 * {@code keycloak}ユーザー(docker-compose.ymlのkeycloak-postgres定義参照)しか存在しないため、
 * 新たな横断的な認証情報を追加する必要はなく、その既存ユーザーの認証情報をそのまま流用する。
 */
@ConfigurationProperties(prefix = "app.backup")
@Getter
@Setter
public class BackupProperties {

    private Mysql mysql = new Mysql();
    private Postgres postgres = new Postgres();

    @Getter
    @Setter
    public static class Mysql {
        private String host = "mysql";
        private String port = "3306";
        private String user = "lbs_backup";
        private String password = "";
        /**
         * バックアップ/リストア対象のMySQLスキーマ名一覧。全サービスのスキーマ(lbs_identity等)に加え、
         * legacy-apiが解体(#583)されるまで使用するスキーマ(既定では{@code lets_blog})も含む。
         */
        private List<String> schemas = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Postgres {
        private String host = "keycloak-postgres";
        private String port = "5432";
        private String user = "keycloak";
        private String password = "";
        private String database = "keycloak";
    }
}
