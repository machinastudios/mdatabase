package com.machina.mdatabase.providers.database;

import jakarta.persistence.EntityManager;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import java.util.logging.Logger;

import com.machina.mdatabase.providers.database.DatabaseDialect;

/**
 * Utility class for database migrations
 * Provides database-agnostic methods for common migration tasks
 */
public class DatabaseMigrationUtils {
    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(DatabaseMigrationUtils.class.getName());
    /**
     * Database type enum
     */
    public enum DatabaseType {
        SQLITE,
        MYSQL,
        POSTGRES,
        UNKNOWN
    }

    /**
     * Detect database type from connection metadata
     * @param provider The SQLDatabaseProvider
     * @return The database type
     */
    @Nonnull
    public static DatabaseDialect detectDatabaseType(@Nonnull SQLDatabaseProvider provider) {
        return provider.getDialect();
    }

    /**
     * Check if a column exists in a table (database-agnostic)
     * For SQLite, uses PRAGMA table_info for better reliability
     * @param provider The SQLDatabaseProvider
     * @param tableName The table name
     * @param columnName The column name
     * @return True if column exists, false otherwise
     */
    public static boolean columnExists(@Nonnull SQLDatabaseProvider provider, @Nonnull String tableName, @Nonnull String columnName) {
        DatabaseDialect dbType = detectDatabaseType(provider);

        // For SQLite, use PRAGMA directly (more reliable than DatabaseMetaData)
        if (dbType == DatabaseDialect.SQLITE) {
            return columnExistsFallback(provider, tableName, columnName);
        }

        // For other databases, try DatabaseMetaData first
        try {
            Connection connection = provider.getEntityManager().unwrap(Connection.class);
            DatabaseMetaData metaData = connection.getMetaData();

            // Use DatabaseMetaData for database-agnostic column checking
            try (ResultSet columns = metaData.getColumns(null, null, tableName, columnName)) {
                return columns.next();
            }
        } catch (Exception e) {
            // If metadata check fails, try database-specific queries
            return columnExistsFallback(provider, tableName, columnName);
        }
    }

    /**
     * Fallback method to check column existence using database-specific queries
     * This is the primary method for SQLite (more reliable than DatabaseMetaData)
     * @param provider The SQLDatabaseProvider
     * @param tableName The table name
     * @param columnName The column name
     * @return True if column exists, false otherwise
     */
    private static boolean columnExistsFallback(@Nonnull SQLDatabaseProvider provider, @Nonnull String tableName, @Nonnull String columnName) {
        DatabaseDialect dbType = detectDatabaseType(provider);

        try {
            String query;

            switch (dbType) {
                case SQLITE:
                    query = "PRAGMA table_info(" + tableName + ")";
                    break;

                case MYSQL:
                    query = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '" + tableName + "' AND COLUMN_NAME = '" + columnName + "'";
                    break;

                case POSTGRES:
                    query = "SELECT column_name FROM information_schema.columns WHERE table_name = '" + tableName + "' AND column_name = '" + columnName + "'";
                    break;

                default:
                    // Unknown database, assume column doesn't exist
                    return false;
            }

            List<?> result = provider.getEntityManager().createNativeQuery(query).getResultList();

            // For SQLite, check in result rows
            if (dbType == DatabaseDialect.SQLITE) {
                for (Object row : result) {
                    // PRAGMA table_info returns rows as Object[] where:
                    // [0] = cid (column index)
                    // [1] = name (column name)
                    // [2] = type (column type)
                    // [3] = notnull
                    // [4] = dflt_value
                    // [5] = pk
                    if (row instanceof Object[]) {
                        Object[] columns = (Object[]) row;
                        if (columns.length > 1 && columns[1] instanceof String) {
                            String colName = (String) columns[1];
                            if (columnName.equalsIgnoreCase(colName)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            // For MySQL/PostgreSQL, if result has rows, column exists
            return !result.isEmpty();
        } catch (Exception e) {
            // On error, assume column doesn't exist
            return false;
        }
    }

    /**
     * Check if a table exists (database-agnostic)
     * Uses native queries so they appear in Hibernate logs
     * @param provider The SQLDatabaseProvider
     * @param tableName The table name
     * @return True if table exists, false otherwise
     */
    public static boolean tableExists(@Nonnull SQLDatabaseProvider provider, @Nonnull String tableName) {
        DatabaseDialect dbType = detectDatabaseType(provider);

        try {
            String query;

            switch (dbType) {
                case SQLITE:
                    // For SQLite, use sqlite_master with exact match
                    // SQLite table names are case-sensitive in the database but comparisons may vary
                    // Use LOWER() to ensure case-insensitive comparison
                    query = "SELECT name FROM sqlite_master WHERE type='table' AND LOWER(name) = LOWER('" + tableName + "')";
                    break;

                case MYSQL:
                    query = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + tableName + "'";
                    break;

                case POSTGRES:
                    query = "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename = '" + tableName + "'";
                    break;

                default:
                    logger.severe("Unknown database type: " + dbType);

                    // Unknown database, assume table doesn't exist
                    return false;
            }

            // Log the query manually so it appears in logs
            logger.info("Executing query to check if table exists: " + query);
            List<?> result = provider.getEntityManager().createNativeQuery(query).getResultList();
            boolean exists = !result.isEmpty();
            logger.info("Table '" + tableName + "' exists: " + exists);
            return exists;
        } catch (Exception e) {
            logger.severe("Error checking if table " + tableName + " exists:");
            e.printStackTrace();

            // On error, assume table doesn't exist
            return false;
        }
    }

    /**
     * Get SQL for adding a column (database-agnostic)
     * @param dbType The database type
     * @param tableName The table name
     * @param columnName The column name
     * @param columnType The column type (e.g., "TEXT", "VARCHAR(255)")
     * @return SQL statement for adding the column
     */
    @Nonnull
    public static String getAddColumnSql(@Nonnull DatabaseType dbType, @Nonnull String tableName, @Nonnull String columnName, @Nonnull String columnType) {
        switch (dbType) {
            case SQLITE:
                return "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType;

            case MYSQL:
                return "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType;

            case POSTGRES:
                return "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType;

            default:
                // Default to SQLite syntax (most common)
                return "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnType;
        }
    }

    /**
     * Ensure the migrations tracking table exists
     * This is called before checking migration status
     * @param provider The SQLDatabaseProvider to use
     */
    public static void ensureMigrationsTableExists(@Nonnull SQLDatabaseProvider provider) {
        String tableName = "mdatabaseMigrations";
        
        // If table already exists, nothing to do
        if (tableExists(provider, tableName)) {
            return;
        }

        DatabaseDialect dbType = detectDatabaseType(provider);
        String createTableSql;

        switch (dbType) {
            case SQLITE:
                createTableSql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id TEXT PRIMARY KEY, " +
                    "description TEXT, " +
                    "executedAt INTEGER" +
                    ")";
                break;

            case MYSQL:
                createTableSql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "description TEXT, " +
                    "executedAt DATETIME" +
                    ")";
                break;

            case POSTGRES:
                createTableSql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "description TEXT, " +
                    "\"executedAt\" TIMESTAMP" +
                    ")";
                break;

            default:
                // Default to SQLite syntax
                createTableSql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "id TEXT PRIMARY KEY, " +
                    "description TEXT, " +
                    "executedAt INTEGER" +
                    ")";
        }

        try {
            provider.getEntityManager().createNativeQuery(createTableSql).executeUpdate();
        } catch (Exception e) {
            // Ignore if table already exists or creation fails
        }
    }
}
