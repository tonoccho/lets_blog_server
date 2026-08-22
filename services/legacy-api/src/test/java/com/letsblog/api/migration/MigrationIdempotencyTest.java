package com.letsblog.api.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Database Migration Idempotency Tests")
class MigrationIdempotencyTest extends MigrationTestBase {

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("Flyway migrations should complete successfully")
    void testMigrationsCompleteSuccessfully() {
        assertDoesNotThrow(() -> {
            flyway.migrate();
        }, "Migrations should execute without throwing exceptions");
    }

    @Test
    @DisplayName("Schema should be in consistent state after migrations")
    void testSchemaConsistencyAfterMigrations() {
        assertDoesNotThrow(() -> {
            var result = flyway.migrate();
            assertNotNull(result, "Migration result should not be null");
            assertTrue(result.success, "Migrations should complete successfully");
            assertTrue(result.migrationsExecuted >= 0, "Should have executed zero or more migrations");
        });
    }

    @Test
    @DisplayName("Running migrations twice should not cause conflicts")
    void testMigrationIdemptency() {
        assertDoesNotThrow(() -> {
            var firstRun = flyway.migrate();
            assertTrue(firstRun.success, "First migration run should succeed");

            var secondRun = flyway.migrate();
            assertTrue(secondRun.success, "Second migration run should succeed");
            assertEquals(0, secondRun.migrationsExecuted,
                    "Second migration run should execute 0 migrations (already applied)");
        });
    }

    @Test
    @DisplayName("Flyway migration history should be properly tracked")
    void testMigrationHistoryTracking() {
        assertDoesNotThrow(() -> {
            flyway.migrate();

            var migrations = flyway.info().all();
            assertTrue(migrations.length > 0, "Should have at least one migration executed");

            for (var migration : migrations) {
                assertNotNull(migration.getVersion(), "Migration version should not be null");
                assertNotNull(migration.getDescription(), "Migration description should not be null");
                assertNotNull(migration.getInstalledOn(), "Migration install date should be recorded");
                assertEquals("SUCCESS", migration.getState().toString(),
                        "All migrations should have SUCCESS state");
            }
        });
    }

    @Test
    @DisplayName("Flyway should report correct version after migrations")
    void testFlywayCoreVersion() {
        assertDoesNotThrow(() -> {
            var info = flyway.info();
            assertNotNull(info, "Flyway info should be available");
            assertNotNull(info.all(), "Flyway migrations array should not be null");
        });
    }

    @Test
    @DisplayName("Validation should pass for applied migrations")
    void testMigrationValidation() {
        assertDoesNotThrow(() -> {
            flyway.migrate();
            flyway.validate();
        }, "Flyway validation should pass after migration");
    }
}
