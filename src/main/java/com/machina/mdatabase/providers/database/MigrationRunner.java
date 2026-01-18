package com.machina.mdatabase.providers.database;

import jakarta.persistence.EntityManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Runs database migrations in order
 * Tracks executed migrations to avoid running them multiple times
 */
public class MigrationRunner {
    /**
     * The list of migrations to run (in order)
     */
    @Nonnull
    private final List<Migration> migrations;

    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(MigrationRunner.class.getName());

    /**
     * The database provider
     */
    @Nonnull
    private final SQLDatabaseProvider provider;

    /**
     * Create a new MigrationRunner
     */
    public MigrationRunner(SQLDatabaseProvider provider) {
        this.provider = provider;
        this.migrations = new ArrayList<>();
    }

    /**
     * Register a migration to be executed
     * Migrations should be registered in order
     * @param migration The migration to register
     */
    public void registerMigration(@Nonnull Migration migration) {
        migrations.add(migration);
    }

    /**
     * Register multiple migrations
     * @param migrations The migrations to register (in order)
     */
    public void registerMigrations(@Nonnull List<Migration> migrations) {
        this.migrations.addAll(migrations);
    }

    /**
     * Check if a migration has already been executed
     * @param migrationId The migration ID to check
     * @return True if the migration has been executed, false otherwise
     */
    private boolean isMigrationExecuted(@Nonnull String migrationId) {
        try {
            // First check if migrations table exists
            if (!DatabaseMigrationUtils.tableExists(provider, "mdatabaseMigrations")) {
                // Table doesn't exist yet - no migrations have been run
                logger.info("Migrations table does not exist, assuming migration has not been executed");

                return false;
            }

            // Use em.find() - with show_sql=true, the SQL query will appear in logs
            MigrationRecordModel record = provider.getEntityManager().find(MigrationRecordModel.class, migrationId);

            boolean executed = record != null;

            logger.info("Checking if migration " + migrationId + " has been executed: " + executed);

            if (executed && record != null) {
                logger.info("Migration " + migrationId + " has been executed: " + record.executedAt);
                logger.info("Migration " + migrationId + " has been executed: " + record.description);
                logger.info("Migration " + migrationId + " has been executed: " + record.id);
            }
            
            return executed;
        } catch (Exception e) {
            logger.severe("Error checking if migration " + migrationId + " has been executed: " + e.getMessage());
            e.printStackTrace();

            // On error, assume migration hasn't been executed
            return false;
        }
    }

    /**
     * Mark a migration as executed
     * @param migration The migration to mark as executed
     */
    private void markMigrationExecuted(@Nonnull Migration migration) {
        try {
            // Check if already marked (idempotency)
            if (isMigrationExecuted(migration.getId())) {
                return;
            }

            MigrationRecordModel record = new MigrationRecordModel(migration.getId(), migration.getDescription());
            provider.getEntityManager().persist(record);
            provider.getEntityManager().flush(); // Ensure it's written immediately
        } catch (Exception e) {
            // Log but don't fail - tracking is not critical, but this is a warning
            logger.severe("Failed to record migration execution for " + migration.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Run all registered migrations that should run
     * Migrations are executed in registration order
     */
    public void runMigrations() {
        if (migrations.isEmpty()) {
            logger.info("No migrations to run");
            return;
        }

        logger.info("Checking for " + migrations.size() + " migrations to run");

        var em = provider.getEntityManager();

        // Migrations table (mdatabaseMigrations) is automatically created by Hibernate
        // when MigrationRecordModel is registered with the provider

        // Get list of migrations that should run
        // A migration should run if:
        // 1. It hasn't been executed before (checked via tracking table), AND
        // 2. shouldRun() returns true (plugin-specific logic)
        List<Migration> toRun = new ArrayList<>();
        for (Migration migration : migrations) {
            // Check if already executed
            if (isMigrationExecuted(migration.getId())) {
                logger.info("Migration " + migration.getId() + " already executed, skipping");
                continue;
            }

            // Check plugin-specific shouldRun logic
            if (migration.shouldRun(em)) {
                toRun.add(migration);

                logger.info("Migration " + migration.getId() + " should run, adding to list");
            } else {
                logger.info("Migration " + migration.getId() + " should not run, skipping");
            }
        }
    
        // If no migrations should run, return
        if (toRun.isEmpty()) {
            logger.info("No migrations should run, returning");
            return;
        }

        // Execute migrations in order
        var transaction = em.getTransaction();
        
        for (Migration migration : toRun) {
            try {
                // Start transaction if not active
                if (!transaction.isActive()) {
                    transaction.begin();
                }

                logger.info("Executing migration " + migration.getId());

                // Execute migration
                migration.execute(em);

                // Mark migration as executed
                markMigrationExecuted(migration);

                // Commit transaction
                if (transaction.isActive()) {
                    transaction.commit();
                }

                logger.info("Migration " + migration.getId() + " executed successfully");
            } catch (Exception e) {
                // Rollback on error
                if (transaction.isActive()) {
                    try {
                        transaction.rollback();
                    } catch (Exception rollbackEx) {
                        // Ignore rollback errors
                    }
                }

                // Log error but don't fail initialization
                logger.severe("Failed to run migration " + migration.getId() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Get all registered migrations (read-only)
     * @return List of registered migrations
     */
    @Nonnull
    public List<Migration> getMigrations() {
        return Collections.unmodifiableList(migrations);
    }

    /**
     * Clear all registered migrations
     */
    public void clear() {
        migrations.clear();
    }
}
