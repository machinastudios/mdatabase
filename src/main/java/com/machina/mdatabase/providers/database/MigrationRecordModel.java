package com.machina.mdatabase.providers.database;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Date;

/**
 * Model for tracking executed migrations
 * Stores which migrations have been run to avoid running them multiple times
 */
@Entity
@Table(name = "mDatabaseMigrations")
public class MigrationRecordModel {
    @Id
    @Column(name = "id", length = 255)
    public String id;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    @Column(name = "executedAt")
    public Date executedAt;

    public MigrationRecordModel() {
    }

    public MigrationRecordModel(String id, String description) {
        this.id = id;
        this.description = description;
        this.executedAt = new Date();
    }
}
