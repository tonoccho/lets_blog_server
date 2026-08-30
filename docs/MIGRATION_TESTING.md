# Database Migration Testing Guide

## サービス別スキーマ分離への移行(#570)について

[ADR-0004](adr/0004-schema-per-service.md) により、`api`(現 `services/legacy-api`)が
単独で持っていたスキーマは、サービスごとに分離される方針になった。新しいサービス
(project-service, content-service 等)のFlyway設定・スキーマ命名規約・既存データの
移行手順・移行検証手順は [docs/SERVICE_SCHEMA_MIGRATION.md](SERVICE_SCHEMA_MIGRATION.md)
を参照。

以下、このドキュメントの本体は `services/legacy-api` が現在も所有する既存のV1〜V63
マイグレーション(移行方針により再配置しない)に関する説明であり、引き続き有効。

## Overview

This document describes the database migration testing strategy for Let's Blog. All Flyway database migrations are automatically tested to ensure:

- ✅ Migrations execute successfully
- ✅ Migrations are idempotent (can run multiple times safely)
- ✅ Database schema is properly validated after migrations
- ✅ Essential tables and columns exist
- ✅ Proper constraints and primary keys are configured

## Migration Files Location

All Flyway migrations are located in:
```
services/legacy-api/src/main/resources/db/migration/
```

Migrations follow the Flyway naming convention:
- `V{version}__{description}.sql`
- Example: `V1__init_schema.sql`, `V2__add_users.sql`

## Migration Testing

### Test Classes

The following test classes are provided in `services/legacy-api/src/test/java/com/letsblog/api/migration/`:

1. **MigrationIdempotencyTest**
   - Tests that migrations can be applied successfully
   - Verifies that running migrations multiple times doesn't cause conflicts
   - Validates migration history tracking

2. **MigrationSchemaValidationTest**
   - Verifies essential tables exist after migrations
   - Checks for required columns in critical tables
   - Validates primary keys and constraints
   - Ensures no orphaned foreign key constraints

### Running Migration Tests Locally

#### Prerequisites

- MySQL 8.0 or later running on `localhost:3306`
- JDK 21 or later

#### Setup Test Database

```bash
mysql -u root -p -e "CREATE DATABASE lets_blog_test;"
mysql -u root -p -e "CREATE USER 'test_user'@'localhost' IDENTIFIED BY 'test_pass';"
mysql -u root -p -e "GRANT ALL PRIVILEGES ON lets_blog_test.* TO 'test_user'@'localhost';"
mysql -u root -p -e "FLUSH PRIVILEGES;"
```

#### Run Tests

```bash
cd services/legacy-api
./gradlew test --tests "com.letsblog.api.migration.*"
```

Or run specific test class:

```bash
cd services/legacy-api
./gradlew test --tests "com.letsblog.api.migration.MigrationIdempotencyTest"
```

## CI/CD Integration

The GitHub Actions workflow `.github/workflows/migration-test.yml` automatically:

1. Creates a MySQL test database in a service container
2. Runs all migration tests on each commit that modifies:
   - Migration files
   - Gradle configuration
   - Migration test workflow itself

3. Uploads test results as artifacts for review

### Triggering Migration Tests

Migration tests run automatically when:

- Pushing to `main` or `develop` branches with migration file changes
- Creating/updating a PR with migration file changes
- Manually editing the migration test workflow

## Idempotency Testing

Idempotency means migrations can be applied multiple times without causing errors or data loss.

### Why It Matters

- Allows safe retry operations during deployment failures
- Supports zero-downtime deployments
- Prevents accidental re-application of migrations

### How It Works

The `MigrationIdempotencyTest` class verifies:

1. First migration run completes successfully
2. Second migration run completes successfully
3. Second run executes 0 migrations (they're already applied)
4. Migration state remains consistent

## Flyway Configuration

Flyway is configured in `services/legacy-api/src/main/resources/application.yml`:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    placeholder-replacement: false
```

### Key Settings

- **enabled**: Flyway migrations run automatically on application startup
- **locations**: Directory containing migration files
- **placeholder-replacement**: Disabled to prevent conflicts with Thymeleaf syntax in email templates

## Migration Best Practices

### Do's ✅

- Keep migrations focused on a single change
- Use descriptive names: `V{N}__{description}.sql`
- Test migrations locally before pushing
- Include both forward and backward-compatible changes when possible
- Add indexes for foreign keys
- Add NOT NULL constraints with default values

### Don'ts ❌

- Don't modify existing migration files (create new ones instead)
- Don't use transactions in migration files (Flyway handles this)
- Don't drop tables without careful consideration
- Don't change column types without a migration path
- Don't remove migrations from version control

## Adding New Migrations

### Process

1. Create a new SQL file in `services/legacy-api/src/main/resources/db/migration/`
2. Follow naming: `V{NextNumber}__{Description}.sql`
3. Write idempotent SQL
4. Run tests locally: `./gradlew test --tests "com.letsblog.api.migration.*"`
5. Commit and push

### Example Migration

```sql
-- V34__add_new_feature_table.sql
CREATE TABLE IF NOT EXISTS new_feature (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_created_at ON new_feature(created_at);
```

## Troubleshooting

### Migration Fails Locally

1. Check MySQL is running on `localhost:3306`
2. Verify test database exists: `SHOW DATABASES;`
3. Check test user permissions: `SHOW GRANTS FOR 'test_user'@'localhost';`
4. Drop and recreate test database: `DROP DATABASE lets_blog_test;`

### Migration Fails in CI/CD

1. Check GitHub Actions logs in the workflow run
2. Review test artifact results
3. Ensure migration file syntax is correct
4. Verify migration doesn't conflict with existing migrations

### Previous Migrations Corrupted

If existing migrations are corrupted:

1. **Never** modify existing migration files
2. Create a new migration to fix the issue
3. Update schema as needed in the new migration

Example:
```sql
-- V35__fix_corrupted_data.sql
UPDATE affected_table SET column = corrected_value WHERE condition;
```

## Rollback Considerations

**Note:** Flyway does not natively support rollbacks in the open-source version. Instead:

1. Create a new forward-only migration to fix issues
2. Use compensating transactions for data corrections
3. Carefully plan schema changes to be reversible

## Related Files

- `.github/workflows/migration-test.yml` - CI/CD workflow
- `services/legacy-api/src/main/resources/application.yml` - Flyway configuration
- `services/legacy-api/src/test/resources/application-test.yml` - Test configuration
- `services/legacy-api/src/main/resources/db/migration/` - Migration files

## Questions or Issues?

If you encounter issues with migrations or have questions about the testing process:

1. Check this document for common solutions
2. Review existing migration files for patterns
3. Consult the [Flyway Documentation](https://flywaydb.org/documentation)
4. Open a GitHub issue if you find a problem
