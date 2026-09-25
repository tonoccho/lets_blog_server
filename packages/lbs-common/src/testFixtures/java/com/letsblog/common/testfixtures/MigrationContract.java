package com.letsblog.common.testfixtures;

import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 各サービスの Flyway マイグレーションが満たすべき性質を検証する共通契約(issue #914)。
 *
 * <p>#583 で legacy-api を削除するまで、マイグレーションの検証は legacy-api の
 * {@code MigrationIdempotencyTest} / {@code MigrationSchemaValidationTest} と、
 * それ専用の GitHub Actions ワークフローだけが担っていた。legacy-api ごと消えた結果、
 * <b>Flyway を持つ9サービスに同等の検証が1つも無くなった</b>のを埋める。
 *
 * <p>9サービスへ同じクラスを複製せず、{@code packages/lbs-common} の testFixtures へ置く
 * ({@link AuthorizationCoverageContract}(#830)・{@link AuthorizationMatrixContract}(#805)と同じ方針)。
 *
 * <h2>スキーマとエンティティの整合について</h2>
 *
 * <p>この契約は<b>整合を直接は検証しない</b>。各サービスのテスト設定が
 * {@code spring.flyway.enabled: true} + {@code spring.jpa.hibernate.ddl-auto: validate} に
 * なっており、<b>Spring コンテキストの起動そのもの</b>が「マイグレーションで作られたスキーマと
 * JPA エンティティ定義が一致すること」を検証するためである。食い違えば
 * {@code SchemaManagementException}(missing table / missing column)で context load が落ちる。
 *
 * <p>legacy-api にあった {@code MigrationSchemaValidationTest} は、ハードコードした
 * テーブル名・カラム名のリストを {@code DatabaseMetaData} で確認するだけだったので、
 * この {@code ddl-auto: validate} のほうが対象が広く、追随の手間も要らない。
 * そのため復元せず、代わりに<b>全サービスがその設定になっていること</b>を各サービスの
 * テストが担保する形にした。
 */
public final class MigrationContract {

    private MigrationContract() {
    }

    /**
     * マイグレーションが冪等であること。
     *
     * <p>2回目の {@code migrate()} が「成功する」だけでなく<b>何も実行しない</b>ことまで見る。
     * 「失敗しない」だけだと、再適用で余計な行を足したり DDL を二重に流したりしても通ってしまう。
     *
     * @param flyway 検証対象サービスの Flyway Bean
     */
    public static void verifyIdempotent(Flyway flyway) {
        MigrateResult first = flyway.migrate();
        if (!first.success) {
            throw new AssertionError("1回目のマイグレーションが失敗しました");
        }

        MigrateResult second = flyway.migrate();
        if (!second.success) {
            throw new AssertionError("2回目のマイグレーションが失敗しました(冪等ではありません)");
        }
        if (second.migrationsExecuted != 0) {
            throw new AssertionError(
                    "2回目のマイグレーションが " + second.migrationsExecuted
                            + " 件を実行しました。適用済みのはずなので0件でなければなりません"
                            + "(冪等ではないか、バージョン番号が重複しています)");
        }
    }

    /**
     * 適用済みの全マイグレーションが SUCCESS であり、履歴として追跡できていること。
     *
     * <p>{@code PENDING} や {@code OUTDATED} が混ざっていると、テストは通るのに
     * 本番の {@code ddl-auto: validate} が落ちる、という食い違いが起きうる。
     *
     * @param flyway        検証対象サービスの Flyway Bean
     * @param serviceModule エラーメッセージに出すサービス名({@code services/} 配下のディレクトリ名)
     */
    public static void verifyAllMigrationsApplied(Flyway flyway, String serviceModule) {
        flyway.migrate();

        MigrationInfo[] all = flyway.info().all();
        if (all.length == 0) {
            throw new AssertionError(serviceModule + ": マイグレーションが1件も見つかりません。"
                    + "spring.flyway.locations(既定 classpath:db/migration)を確認してください");
        }

        String problems = Arrays.stream(all)
                .filter(m -> m.getState() != MigrationState.SUCCESS)
                .map(m -> "  - " + m.getVersion() + " " + m.getDescription() + " => " + m.getState())
                .reduce("", (a, b) -> a + b + "\n");
        if (!problems.isBlank()) {
            throw new AssertionError(serviceModule + ": SUCCESS でないマイグレーションがあります。\n" + problems);
        }

        for (MigrationInfo m : all) {
            if (m.getVersion() == null || m.getDescription() == null || m.getInstalledOn() == null) {
                throw new AssertionError(serviceModule
                        + ": マイグレーションの履歴が欠けています(version/description/installedOn のいずれかが null): "
                        + m.getScript());
            }
        }
    }

    /**
     * 適用済みマイグレーションのチェックサムが、現在のファイルと一致していること。
     *
     * <p>適用後にマイグレーションファイルを編集すると、本番では
     * {@code FlywayValidateException} で起動できなくなる。テストで先に気付けるようにする。
     *
     * @param flyway        検証対象サービスの Flyway Bean
     * @param serviceModule エラーメッセージに出すサービス名
     */
    public static void verifyValidates(Flyway flyway, String serviceModule) {
        flyway.migrate();
        try {
            flyway.validate();
        } catch (RuntimeException e) {
            throw new AssertionError(serviceModule
                    + ": Flyway の検証に失敗しました。適用済みのマイグレーションファイルを"
                    + "後から編集していませんか(新しいバージョンを足してください): " + e.getMessage(), e);
        }
    }
}
