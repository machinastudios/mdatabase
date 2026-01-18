package com.machina.mdatabase.providers.database;

import jakarta.persistence.EntityManager;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * Create a new MigrationRunner
     */
    public MigrationRunner() {
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
     * @param em The EntityManager to use for queries
     * @param migrationId The migration ID to check
     * @return True if the migration has been executed, false otherwise
     */
    private boolean isMigrationExecuted(@Nonnull EntityManager em, @Nonnull String migrationId) {
        try {
            // First check if migrations table exists
            if (!DatabaseMigrationUtils.tableExists(em, "mDatabaseMigrations")) {
                // Table doesn't exist yet - no migrations have been run
                return false;
            }

            MigrationRecordModel record = em.find(MigrationRecordModel.class, migrationId);
            return record != null;
        } catch (Exception e) {
            // On error, assume migration hasn't been executed
            return false;
        }
    }

    /**
     * Mark a migration as executed
     * @param em The EntityManager to use for database operations
     * @param migration The migration to mark as executed
     */
    private void markMigrationExecuted(@Nonnull EntityManager em, @Nonnull Migration migration) {
        try {
            // Check if already marked (idempotency)
            if (isMigrationExecuted(em, migration.getId())) {
                return;
            }

            MigrationRecordModel record = new MigrationRecordModel(migration.getId(), migration.getDescription());
            em.persist(record);
            em.flush(); // Ensure it's written immediately
        } catch (Exception e) {
            // Log but don't fail - tracking is not critical, but this is a warning
            System.err.println("[Migration] Warning: Failed to record migration execution for " + migration.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Run all registered migrations that should run
     * Migrations are executed in registration order
     * @param em The EntityManager to use for database operations
     */
    public void runMigrations(@Nonnull EntityManager em) {
        if (migrations.isEmpty()) {
            return;
        }

        // Migrations table (mDatabaseMigrations) is automatically created by Hibernate
        // when MigrationRecordModel is registered with the provider

        // Get list of migrations that should run
        // A migration should run if:
        // 1. It hasn't been executed before (checked via tracking table), AND
        // 2. shouldRun() returns true (plugin-specific logic)
        List<Migration> toRun = new ArrayList<>();
        for (Migration migration : migrations) {
            // Check if already executed
            if (isMigrationExecuted(em, migration.getId())) {
                continue;
            }

            // Check plugin-specific shouldRun logic
            if (migration.shouldRun(em)) {
                toRun.add(migration);
            }
        }

        if (toRun.isEmpty()) {
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

                // Execute migration
                migration.execute(em);

                // Mark migration as executed
                markMigrationExecuted(em, migration);

                // Commit transaction
                if (transaction.isActive()) {
                    transaction.commit();
                }

                System.out.println("[Migration] ✓ " + migration.getId() + ": " + migration.getDescription());
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
                System.err.println("[Migration] ✗ Failed to run migration " + migration.getId() + ": " + e.getMessage());
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
