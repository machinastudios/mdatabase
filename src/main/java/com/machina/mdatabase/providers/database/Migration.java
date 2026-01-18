package com.machina.mdatabase.providers.database;

import jakarta.persistence.EntityManager;

import javax.annotation.Nonnull;

/**
 * Interface for database migrations
 * Migrations are executed in order during database initialization
 */
public interface Migration {
    /**
     * Get a unique identifier for this migration
     * Used to track which migrations have been executed
     * @return The migration ID (should be unique and stable)
     */
    @Nonnull
    String getId();

    /**
     * Get the migration description
     * @return A description of what this migration does
     */
    @Nonnull
    String getDescription();

    /**
     * Check if this migration should run
     * This method should check if the migration is needed (e.g., column doesn't exist)
     * @param em The EntityManager to use for queries
     * @return True if the migration should run, false otherwise
     */
    boolean shouldRun(@Nonnull EntityManager em);

    /**
     * Execute the migration
     * @param em The EntityManager to use for database operations
     */
    void execute(@Nonnull EntityManager em);
}
