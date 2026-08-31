# Backup and Recovery Strategy

## Overview

This document outlines the Let's Blog application's backup and recovery strategy to ensure data protection and business continuity.

## Backup Scope

The backup system (owned by `platform-service`, issue #694 / C10-2) protects three critical components:

1. **All service MySQL schemas**: `lbs_identity`, `lbs_project`, `lbs_content`, `lbs_media`, `lbs_ai`,
   `lbs_publishing`, `lbs_analytics`, `lbs_platform`, `lbs_log`. This covers sites, articles, user data,
   audit logs, and configuration across every domain service (#570 schema-per-service split).
   The pre-split single schema (`lets_blog`) was dropped in #785 after `legacy-api` was removed in
   #583, so it is no longer in `BACKUP_MYSQL_SCHEMAS`.
2. **Keycloak PostgreSQL database** (`keycloak`, in the separate `keycloak-postgres` instance): all
   authentication data (users, credentials, roles, sessions).
3. **Generated Images**: All AI-generated images stored in the `generated-images` volume.

**Note**: Individual managed WordPress sites' databases and files are excluded. Each WordPress site is responsible for its own backup/recovery procedures.

## Backup Architecture

### Cross-Schema Credentials

Each service owns a dedicated MySQL user scoped to its own schema (#570), so no single service
credential can dump/restore every schema. `BackupService` instead uses a dedicated `lbs_backup` MySQL
user (created by `mysql/init/01-create-service-schemas.sh`) that is granted the same per-schema
privileges as each service's own user, but on every known schema at once — it has no access to the
MySQL system schemas or any database outside the application's own set, preserving the intent of the
schema-per-service separation (#570). For Keycloak's PostgreSQL database, the existing `keycloak` user (already
scoped to only the `keycloak` database) is reused; no new PostgreSQL credential is introduced.

### Backup Package Format

Backups are created as ZIP archives containing:

- `metadata.json` - Backup metadata including:
  - `encryptionKeyHash` - SHA256 hash of the current APP_ENCRYPTION_KEY for validation during restore
  - `createdAt` - ISO 8601 timestamp of backup creation
  - `mysqlSchemas` - list of MySQL schema names included in this backup
  - `postgresDatabases` - list of PostgreSQL database names included in this backup (currently just `keycloak`)
- `mysql/<schema>.sql` - One `mysqldump` (single-transaction, includes routines and triggers) per MySQL
  schema listed in `mysqlSchemas`, e.g. `mysql/lbs_identity.sql`, `mysql/lbs_media.sql`, ...
- `postgres/keycloak.dump` - `pg_dump --format=custom` output of the Keycloak PostgreSQL database
- `generated-images/` - Directory containing all AI-generated images (if any exist)

### Encryption Key Validation

The backup includes a hash of the APP_ENCRYPTION_KEY used at backup time. During restore:

- The system validates that the current key matches the backup key
- If keys mismatch, restore is blocked unless explicitly acknowledged
- This prevents data corruption from encrypted fields (site authentication) being restored in incompatible environments

## Retention Policy

### Backup Retention Guidelines

| Scenario | Retention Duration | Rationale |
|----------|-------------------|-----------|
| Manual backups | No automatic deletion | Administrator-managed, critical for compliance/archival |
| Failed backups | No retention | Only successful backups are kept |
| Development environment | 7 days | Ephemeral data, reduces storage costs |
| Staging environment | 30 days | Mirrors production procedures, longer retention for troubleshooting |
| Production environment | 90 days minimum | Regulatory/compliance requirements, adequate for incident investigation |

### Storage Locations

- **Manual backups**: Downloaded by administrators via `/api/backup/download`
- **Backup archives**: Recommend external storage (S3, GCS, Azure Blob) with lifecycle policies
- **Local retention**: Minimum 7 days for quick recovery, maximum 90 days

## Backup Triggers

### Current Implementation

**Manual backups only** - Triggered by administrators via the web interface at `/admin/backup`

### Future Enhancement

Automated scheduled backups can be implemented via:

- Daily backups at 00:00 UTC (low-traffic window)
- Retention: Keep daily for 7 days, weekly for 30 days, monthly for 90 days
- Monitoring: Alert on backup failure or timeout

## Recovery Procedures

### Prerequisites

- Admin access to the application
- Current APP_ENCRYPTION_KEY or acknowledgment of key mismatch
- Access to the backup ZIP file
- MySQL command-line client availability (for manual recovery)

### Automated Recovery (Recommended)

1. Navigate to `/admin/backup` (requires admin access)
2. Click "Restore Backup"
3. Upload the backup ZIP file
4. If encryption key mismatch warning appears:
   - Verify you are restoring to the correct environment
   - Check if APP_ENCRYPTION_KEY was intentionally rotated
   - If intentional, acknowledge the mismatch to continue
5. Click "Confirm Restore" (destructive operation)
6. Monitor logs for recovery completion
7. Verify application functionality post-restore

### Manual Recovery (for System Administrators)

If automated recovery fails, manual recovery is possible:

```bash
# Extract backup archive
unzip -d /tmp/backup-extract lets-blog-backup-20240108-145300.zip

# Restore each MySQL schema (one dump file per schema under mysql/)
for dump in /tmp/backup-extract/mysql/*.sql; do
  schema=$(basename "$dump" .sql)
  mysql -h <mysql-host> -u <mysql-user> -p<mysql-password> "$schema" < "$dump"
done

# Restore the Keycloak PostgreSQL database (custom-format pg_dump output)
PGPASSWORD=<keycloak-db-password> pg_restore -h <keycloak-postgres-host> -U keycloak \
  --dbname=keycloak --clean --if-exists /tmp/backup-extract/postgres/keycloak.dump

# Restore generated images
# Copy generated-images/* to /path/to/generated-images-storage/
rsync -av /tmp/backup-extract/generated-images/ /path/to/generated-images-storage/
```

### Recovery Verification Checklist

After restore completion, every service and Keycloak must come back up healthy (all consume the
schemas/database restored above):

- [ ] `identity`, `project`, `content`, `media`, `ai`, `publishing`, `analytics`, `platform`,
      `log-writer`, and `gateway` all report healthy on their `/actuator/health` endpoint
      (`docker compose ps` shows `healthy`, matching the existing `x-actuator-healthcheck` healthcheck
      used by every service in `docker-compose.yml`). `bash scripts/wait-for-stack-healthy.sh`
      checks all of them at once.
- [ ] Keycloak itself starts and its realm/users are reachable (`/auth/realms/letsblog`), confirming the
      restored `keycloak` PostgreSQL database is intact
- [ ] Admin dashboard loads and admin login succeeds (validates both the identity/Keycloak restore and
      the `lbs_platform`/legacy schema restore)
- [ ] All sites are accessible
- [ ] Articles display correctly
- [ ] Generated images load in articles
- [ ] Audit logs show the recovery action (`DB_RESTORED`, recorded by platform-service)
- [ ] Database integrity check passes for each restored MySQL schema (check for errors in MySQL error log)
- [ ] Performance is normal (no slow queries)

## Monitoring and Alerting

### Backup Success Monitoring

The system logs all backup operations:

- **Audit log action**: `DB_BACKUP_DOWNLOADED`
- **Log level**: INFO
- **Logged information**: Database name, archive size, timestamp

Monitoring should check:

```
[INFO] Backup archive created (schemas=lbs_identity,lbs_project,..., size=... bytes)
```

### Backup Failure Alerting

Critical errors are logged and should trigger alerts:

- **Audit log action**: `DB_BACKUP_FAILED` (if implemented)
- **Common failure scenarios**:
  - mysqldump process timeout (>300 seconds)
  - Permission denied accessing MySQL or file system
  - Disk space exhaustion
  - Network connectivity loss

Alert conditions:

- Any backup operation fails
- Backup size is abnormal (0 bytes, or >2x normal size)
- Backup frequency falls below minimum expectations
- Missing encryption key metadata (corrupted backup)

### Restore Monitoring

All restore operations are tracked:

- **Audit log action**: `DB_RESTORED`
- **Log level**: INFO
- **Logged information**: Database name, number of images restored, timestamp

Database integrity should be verified post-restore by checking:

- MySQL error log for corruption warnings
- Application logs for initialization errors
- Test queries against critical tables

## Recovery Time Objective (RTO) and Recovery Point Objective (RPO)

| Metric | Target | Notes |
|--------|--------|-------|
| RPO (Recovery Point Objective) | < 24 hours | Daily backups recommended for production |
| RTO (Recovery Time Objective) | < 1 hour | Automated restore process with manual fallback |
| Backup window | < 10 minutes | Typical on-demand backup duration |
| Restore window | < 30 minutes | Typical automated restore duration |

## Disaster Recovery Scenarios

### Scenario: Database Corruption

1. Detect corruption via application errors or MySQL warnings
2. Restore from most recent known-good backup
3. Verify application functionality
4. If data loss is unacceptable, restore from older backup

### Scenario: Accidental Data Deletion

1. Use most recent backup to restore deleted data
2. Verify restore timestamp is after deletion
3. Merge recovered data with current changes if needed
4. Update monitoring to alert on unusual delete patterns

### Scenario: Encryption Key Rotation

1. Create backup with old encryption key
2. Rotate APP_ENCRYPTION_KEY
3. Restore backup (acknowledge key mismatch if necessary)
4. Verify encrypted data (site credentials) restore correctly
5. Audit log should show mismatch acknowledgment for compliance

### Scenario: Complete System Loss

1. Restore full backup to fresh environment
2. Verify environment configuration (APP_ENCRYPTION_KEY, database credentials)
3. Restore from most recent backup
4. Verify application functionality
5. Update DNS/load balancer to point to restored environment

## Security Considerations

### Backup File Security

- Backups are **not encrypted at rest** in the ZIP archive
- Recommend:
  - Storing backups on encrypted storage (e.g., S3 with encryption enabled)
  - Transmitting backups over HTTPS only
  - Restricting backup file access to administrators
  - Regular security scanning of backup storage

### Access Control

- Only administrators can create/restore backups
- `adminAuthorizationService.requireAdmin()` enforces this
- Backup operations are audited in the audit log

### Encryption Key Considerations

- The APP_ENCRYPTION_KEY is used for sensitive data (site credentials)
- Never rotate key without backing up under the old key first
- Backup metadata includes key hash to prevent silent data corruption

## Testing Strategy

Backup and recovery functionality is tested via:

1. **Unit tests**: Admin authorization, permission validation, confirm flag verification
2. **Integration tests**: Complete backup creation and restore lifecycle
3. **Staging environment tests**: Weekly restore exercises in staging environment
4. **Failure scenario tests**: Timeout handling, network failures, disk space errors

## Compliance and Retention

Backup retention policies should comply with:

- **GDPR**: Right to erasure - deleted user data should not be recoverable after configured retention period
- **Data Residency**: Backups should respect geographic residency requirements
- **Audit Requirements**: Backup operations must be auditable for compliance

## Future Enhancements

1. **Automated backups**: Scheduled daily backups with retention policies
2. **Incremental backups**: Reduce storage by only backing up changed data
3. **Geographic replication**: Replicate backups to multiple regions
4. **Backup encryption**: Encrypt backups at rest with separate key
5. **Automated testing**: Scheduled restore tests in staging environment
6. **Granular recovery**: Recover specific tables/data instead of full database
7. **Point-in-time recovery**: Utilize MySQL binary logs for recovery to any point in time

## References

- [BackupService.java](../services/platform/src/main/java/com/letsblog/platform/service/BackupService.java) - Implementation details
- [BackupController.java](../services/platform/src/main/java/com/letsblog/platform/controller/BackupController.java) - API endpoints
- [BackupServiceTest.java](../services/platform/src/test/java/com/letsblog/platform/service/BackupServiceTest.java) - Test coverage
