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
     * Run all registered migrations that should run
     * Migrations are executed in registration order
     * @param em The EntityManager to use for database operations
     */
    public void runMigrations(@Nonnull EntityManager em) {
        if (migrations.isEmpty()) {
            return;
        }

        // Get list of migrations that should run
        List<Migration> toRun = new ArrayList<>();
        for (Migration migration : migrations) {
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
