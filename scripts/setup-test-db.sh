#!/bin/bash

# Setup test database for migration testing
# This script creates a test database and test user for running migration tests locally

set -e

# Configuration
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-""}
MYSQL_HOST=${MYSQL_HOST:-"localhost"}
MYSQL_USER=${MYSQL_USER:-"test_user"}
MYSQL_PASSWORD=${MYSQL_PASSWORD:-"test_pass"}
TEST_DB="lets_blog_test"

echo "Setting up test database for migration testing..."

# Build MySQL command based on whether password is provided
if [ -z "$MYSQL_ROOT_PASSWORD" ]; then
    MYSQL_CMD="mysql -h $MYSQL_HOST -u root"
else
    MYSQL_CMD="mysql -h $MYSQL_HOST -u root -p$MYSQL_ROOT_PASSWORD"
fi

# Drop existing test database if it exists
echo "Dropping existing test database if it exists..."
$MYSQL_CMD -e "DROP DATABASE IF EXISTS $TEST_DB;" || true

# Create test database
echo "Creating test database '$TEST_DB'..."
$MYSQL_CMD -e "CREATE DATABASE $TEST_DB CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# Create test user if not exists
echo "Creating test user '$MYSQL_USER'..."
$MYSQL_CMD -e "CREATE USER IF NOT EXISTS '$MYSQL_USER'@'localhost' IDENTIFIED BY '$MYSQL_PASSWORD';"

# Grant privileges
echo "Granting privileges to test user..."
$MYSQL_CMD -e "GRANT ALL PRIVILEGES ON $TEST_DB.* TO '$MYSQL_USER'@'localhost';"
$MYSQL_CMD -e "FLUSH PRIVILEGES;"

echo ""
echo "✅ Test database setup complete!"
echo ""
echo "Test database details:"
echo "  Database: $TEST_DB"
echo "  Host: $MYSQL_HOST"
echo "  User: $MYSQL_USER"
echo "  Password: $MYSQL_PASSWORD"
echo ""
echo "You can now run migration tests with:"
echo "  cd api && ./gradlew test --tests 'com.letsblog.api.migration.*'"
