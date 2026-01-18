package com.machina.mdatabase.providers.database;

import jakarta.persistence.EntityManager;

import javax.annotation.Nonnull;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

/**
 * Utility class for database migrations
 * Provides database-agnostic methods for common migration tasks
 */
public class DatabaseMigrationUtils {
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
     * @param em The EntityManager
     * @return The database type
     */
    @Nonnull
    public static DatabaseType detectDatabaseType(@Nonnull EntityManager em) {
        try {
            Connection connection = em.unwrap(Connection.class);
            DatabaseMetaData metaData = connection.getMetaData();
            String databaseProductName = metaData.getDatabaseProductName().toLowerCase();

            if (databaseProductName.contains("sqlite")) {
                return DatabaseType.SQLITE;
            } else if (databaseProductName.contains("mysql")) {
                return DatabaseType.MYSQL;
            } else if (databaseProductName.contains("postgresql") || databaseProductName.contains("postgres")) {
                return DatabaseType.POSTGRES;
            }

            return DatabaseType.UNKNOWN;
        } catch (Exception e) {
            return DatabaseType.UNKNOWN;
        }
    }

    /**
     * Check if a column exists in a table (database-agnostic)
     * Uses DatabaseMetaData for maximum compatibility
     * @param em The EntityManager
     * @param tableName The table name
     * @param columnName The column name
     * @return True if column exists, false otherwise
     */
    public static boolean columnExists(@Nonnull EntityManager em, @Nonnull String tableName, @Nonnull String columnName) {
        try {
            Connection connection = em.unwrap(Connection.class);
            DatabaseMetaData metaData = connection.getMetaData();

            // Use DatabaseMetaData for database-agnostic column checking
            try (ResultSet columns = metaData.getColumns(null, null, tableName, columnName)) {
                return columns.next();
            }
        } catch (Exception e) {
            // If metadata check fails, try database-specific queries
            return columnExistsFallback(em, tableName, columnName);
        }
    }

    /**
     * Fallback method to check column existence using database-specific queries
     * @param em The EntityManager
     * @param tableName The table name
     * @param columnName The column name
     * @return True if column exists, false otherwise
     */
    private static boolean columnExistsFallback(@Nonnull EntityManager em, @Nonnull String tableName, @Nonnull String columnName) {
        DatabaseType dbType = detectDatabaseType(em);

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

            List<?> result = em.createNativeQuery(query).getResultList();

            // For SQLite, check in result rows
            if (dbType == DatabaseType.SQLITE) {
                for (Object row : result) {
                    Object[] columns = (Object[]) row;
                    String colName = (String) columns[1]; // column name is at index 1
                    if (columnName.equals(colName)) {
                        return true;
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
     * @param em The EntityManager
     * @param tableName The table name
     * @return True if table exists, false otherwise
     */
    public static boolean tableExists(@Nonnull EntityManager em, @Nonnull String tableName) {
        try {
            Connection connection = em.unwrap(Connection.class);
            DatabaseMetaData metaData = connection.getMetaData();

            // Use DatabaseMetaData for database-agnostic table checking
            try (ResultSet tables = metaData.getTables(null, null, tableName, null)) {
                return tables.next();
            }
        } catch (Exception e) {
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
}
