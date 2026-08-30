# Backup and Recovery Operations Guide

This guide is for system operators managing Let's Blog application backups and recovery procedures.

## Table of Contents

1. [Creating Backups](#creating-backups)
2. [Restoring from Backup](#restoring-from-backup)
3. [Backup Verification](#backup-verification)
4. [Troubleshooting](#troubleshooting)
5. [Operational Runbooks](#operational-runbooks)

## Creating Backups

### Web Interface (Recommended)

**Prerequisites**: Admin access to Let's Blog application

**Steps**:

1. Log in to the Let's Blog administration interface
2. Navigate to **Admin** → **Backup & Recovery**
3. Click the **"Create Backup"** button
4. Wait for backup completion (typically 5-10 minutes)
5. Browser will automatically download `lets-blog-backup-YYYYMMDD-HHMMSS.zip`
6. Verify file size is reasonable (>1 MB for typical installations)
7. Store backup in secure location

### Command Line (Developers/Operators)

For developers with shell access to the application servers:

```bash
# Navigate to repository root
cd /path/to/lets_blog_server

# Run backup script
./scripts/db-backup.sh

# Optional: specify output path
./scripts/db-backup.sh ./backups/my-custom-backup.sql
```

**Note**: The shell script only backs up the database, not generated images. For complete backups including images, use the web interface.

## Restoring from Backup

### Prerequisites

- Admin access to Let's Blog application
- Backup ZIP file (from web download or created via API)
- APP_ENCRYPTION_KEY must match the environment (or administrator must acknowledge mismatch)
- Backup size should be validated (should not be suspiciously small or large)

### Web Interface (Recommended)

**⚠️ WARNING**: Restore is a **destructive operation** that will overwrite current data.

**Steps**:

1. Log in to the Let's Blog administration interface
2. Navigate to **Admin** → **Backup & Recovery**
3. Click **"Upload and Restore"** tab
4. Select backup ZIP file from your computer
5. Review warning message about destructive operation
6. If encryption key mismatch warning appears:
   - Check that you are restoring to the correct environment
   - Verify APP_ENCRYPTION_KEY value in `.env` or environment configuration
   - If key was intentionally rotated, check "I acknowledge the encryption key mismatch"
7. Click **"Confirm Restore"**
8. Monitor progress (restoration typically takes 5-30 minutes depending on database size)
9. Page will redirect upon successful completion
10. Monitor application logs for any errors
11. Verify functionality (see [Backup Verification](#backup-verification))

### API Endpoint

For automation or scripting:

```bash
# Create multipart form request
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@backup.zip" \
  -F "confirm=true" \
  https://example.com/api/backup/restore

# With encryption key mismatch acknowledgment:
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F "file=@backup.zip" \
  -F "confirm=true" \
  -F "acknowledgeKeyMismatch=true" \
  https://example.com/api/backup/restore
```

**Response**:
- `204 No Content` - Restore started successfully
- `400 Bad Request` - Invalid parameters (e.g., confirm=false)
- `403 Forbidden` - User lacks admin privileges
- `500 Internal Server Error` - Restore failed (check application logs)

### Manual Database Recovery (Emergency Fallback)

If automated restore fails and you have shell access (backup format extended to all service schemas +
Keycloak PostgreSQL in issue #694/C10-2; see [BACKUP_RECOVERY_STRATEGY.md](./BACKUP_RECOVERY_STRATEGY.md#backup-package-format)):

```bash
# 1. Extract backup archive
unzip -d /tmp/backup-extract lets-blog-backup-20240108-145300.zip

# 2. Restore each MySQL schema (one dump file per schema, e.g. mysql/lbs_identity.sql, mysql/lbs_media.sql, ...)
for dump in /tmp/backup-extract/mysql/*.sql; do
  schema=$(basename "$dump" .sql)
  mysql -h $BACKUP_MYSQL_HOST -u $BACKUP_MYSQL_USER -p "$schema" < "$dump"
done

# 3. Restore the Keycloak PostgreSQL database
PGPASSWORD=$KEYCLOAK_DB_PASSWORD pg_restore -h keycloak-postgres -U keycloak \
  --dbname=keycloak --clean --if-exists /tmp/backup-extract/postgres/keycloak.dump

# 4. Restore generated images (if backup contains images)
rsync -av /tmp/backup-extract/generated-images/ /var/lib/lets-blog/generated-images/

# 5. Verify permissions
sudo chown -R app:app /var/lib/lets-blog/generated-images/

# 6. Restart every service so each picks up its restored schema, then verify all report healthy
docker compose up -d identity project content media ai analytics platform gateway api keycloak
docker compose ps
```

## Backup Verification

### Post-Restore Checklist

Execute immediately after restore completes:

```bash
#!/bin/bash
# Backup Verification Script

echo "=== Backup Verification Checklist ==="

# 1. Application Health
echo "1. Checking application health..."
curl -s http://localhost:8080/health || echo "FAIL: Application not responding"

# 2. Database Connectivity
echo "2. Checking database..."
mysql -h localhost -u lets_blog_user -p -e "SELECT COUNT(*) as table_count FROM information_schema.tables WHERE table_schema='lets_blog';" || echo "FAIL: Database unreachable"

# 3. Critical Tables
echo "3. Verifying critical tables..."
mysql -h localhost -u lets_blog_user -p -e "SELECT COUNT(*) FROM lets_blog.articles;" || echo "FAIL: articles table missing"
mysql -h localhost -u lets_blog_user -p -e "SELECT COUNT(*) FROM lets_blog.sites;" || echo "FAIL: sites table missing"

# 4. Generated Images
echo "4. Checking generated images directory..."
[ -d /var/lib/lets-blog/generated-images ] && echo "OK: Directory exists" || echo "FAIL: Directory missing"

# 5. Admin Access
echo "5. Verifying admin access..."
curl -s http://localhost:8080/api/admin/info -H "Authorization: Bearer $ADMIN_TOKEN" | grep -q "email" && echo "OK: Admin access working" || echo "FAIL: Admin access broken"

# 6. Disk Space
echo "6. Checking disk space..."
df -h /var/lib/lets-blog | tail -1 | awk '{if ($5 > 80) print "WARN: Disk usage " $5; else print "OK: Disk usage " $5}'

echo "=== Verification Complete ==="
```

### Application-Level Verification

1. **Admin Dashboard**: Can you access `/admin` panel?
2. **Article Display**: Do articles display with correct formatting?
3. **Image Loading**: Do generated images display without 404 errors?
4. **Site Admin**: Can each site administrator log in?
5. **API Health**: Check `/health` endpoint returns 200 OK

### Database Integrity Check

```sql
-- Check for table corruption
SHOW ENGINE INNODB STATUS\G

-- Verify critical tables have data
SELECT COUNT(*) as article_count FROM articles;
SELECT COUNT(*) as site_count FROM sites;
SELECT COUNT(*) as user_count FROM users;

-- Check for orphaned records
SELECT COUNT(*) FROM articles WHERE site_id NOT IN (SELECT id FROM sites);

-- Verify audit logs captured restore
SELECT action, timestamp FROM audit_logs WHERE action='DB_RESTORED' ORDER BY timestamp DESC LIMIT 10;
```

## Troubleshooting

### Backup Creation Fails

**Symptom**: "Backup archive creation failed"

**Diagnosis**:
```bash
# Check MySQL connectivity
mysql -h $MYSQL_HOST -u $MYSQL_USER -p -e "SELECT 1" || echo "MySQL connection failed"

# Check disk space
df -h /tmp || echo "No temp space"

# Check process timeout
ps aux | grep mysqldump | grep -v grep || echo "mysqldump not running"
```

**Solution**:
1. Verify MySQL is running and accessible
2. Ensure at least 2x database size free disk space
3. For very large databases (>5GB), consider splitting or increasing timeout
4. Check MySQL user has SELECT privilege: `SHOW GRANTS FOR 'lbs_app'@'localhost';`

### Restore Fails with Encryption Key Mismatch

**Symptom**: "This backup was created with a different APP_ENCRYPTION_KEY"

**Diagnosis**:
```bash
# Check current encryption key in .env
grep APP_ENCRYPTION_KEY .env

# Extract backup metadata
unzip -p backup.zip metadata.json | jq .
```

**Solution - Option 1** (Restore with key mismatch acknowledgment):
- If you intentionally rotated the encryption key, acknowledge the mismatch
- Site authentication information will not decrypt, but other data will be restored

**Solution - Option 2** (Restore with correct key):
- Verify the environment has the original APP_ENCRYPTION_KEY value
- This is critical for WordPress site credentials to decrypt correctly

**Solution - Option 3** (Restore database without using the automated process):
- Use manual recovery procedure (see above)
- This restores data without encryption key validation

### Restore Timeout (>30 minutes)

**Symptom**: Restore hangs or takes extremely long

**Diagnosis**:
```bash
# Monitor MySQL process
SHOW PROCESSLIST;

# Check slow query log
tail -f /var/log/mysql/slow-query.log
```

**Solution**:
1. Large databases (>10GB) may require >30 minutes for restore
2. Ensure sufficient memory available to MySQL (check free RAM)
3. For very large databases, consider:
   - Restoring during maintenance window
   - Using binary log for point-in-time recovery instead
   - Implementing incremental backups

### Generated Images Not Restored

**Symptom**: Articles display without AI-generated images after restore

**Diagnosis**:
```bash
# Check if images directory exists
ls -la /var/lib/lets-blog/generated-images/

# Check backup contains images
unzip -l backup.zip | grep generated-images
```

**Solution**:
1. Verify backup ZIP contains `generated-images/` directory
2. Check disk space in target directory
3. Manually restore images: `rsync -av generated-images/ /var/lib/lets-blog/generated-images/`
4. Verify file permissions: `sudo chown -R app:app /var/lib/lets-blog/generated-images/`

## Operational Runbooks

### Weekly Backup Test (Staging Environment)

**Purpose**: Verify backup/restore functionality before production use

**Schedule**: Every Friday at 2:00 AM UTC

**Steps**:

1. Create backup of staging database
2. Note backup file size and checksum
3. Restore backup to staging environment
4. Run application smoke tests
5. Document results in backup verification log
6. Alert on-call if verification fails

### Quarterly Disaster Recovery Drill (Production-Like)

**Purpose**: Practice full recovery procedure

**Schedule**: First Thursday of each quarter

**Steps**:

1. Backup production database
2. Restore to isolated recovery environment
3. Verify all systems online
4. Execute full verification checklist
5. Document recovery time and any issues
6. Brief team on findings
7. Plan improvements based on drill results

### Post-Incident: Backup Inspection

**When**: After any data loss or corruption incident

**Steps**:

1. Immediately create isolated backup (do not use for restore yet)
2. Extract backup metadata and verify encryptionKeyHash
3. Compare backup timestamp against incident discovery time
4. Inspect metadata for any anomalies
5. Perform database integrity check on isolated copy
6. Document findings for root cause analysis

## Monitoring and Alerts

### Key Metrics to Monitor

```sql
-- Backup frequency (should be daily minimum in production)
SELECT DATE(timestamp) as backup_date, COUNT(*) as backup_count 
FROM audit_logs 
WHERE action = 'DB_BACKUP_DOWNLOADED'
GROUP BY backup_date
ORDER BY backup_date DESC
LIMIT 30;

-- Restore operations
SELECT timestamp, user_id, status FROM audit_logs 
WHERE action = 'DB_RESTORED'
ORDER BY timestamp DESC;

-- Failed operations (if implemented)
SELECT COUNT(*) FROM audit_logs 
WHERE action LIKE '%BACKUP%' AND status = 'FAILED'
GROUP BY DATE(timestamp);
```

### Alert Thresholds

Configure alerts for:

- No backup completed in 24 hours (production)
- Backup file size deviates >20% from average
- Any restore operation (requires investigation)
- Restore failure of any kind
- Encryption key mismatch on restore (production alert)

## Support and Escalation

### Backup Creation Support

- **Issue**: Backup creation timeout
- **Escalation**: Database team (check MySQL performance)
- **Runbook**: See troubleshooting section

### Restore Failed

- **Issue**: Restore operation failed
- **Escalation**: Application team immediately (data loss risk)
- **Runbook**: Manual recovery procedure, no delaying

### Data Loss Reported

- **Issue**: User reports missing data post-restore
- **Escalation**: Manager + Database team (may need restore to older backup)
- **Immediate Action**: Do not perform additional restores without investigation
