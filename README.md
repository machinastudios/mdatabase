# MDatabase - SQL Database Provider

Simple database API with abstraction layer for Hytale plugins and mods. Supports SQLite, MySQL, and PostgreSQL.

## Overview

MDatabase provides a simple SQL database abstraction layer using JPA/Hibernate that allows Hytale plugins and mods to work with multiple database types seamlessly. 

## Features

- **SQLDatabaseProvider**: Base class for SQL database operations
  - Supports SQLite, MySQL, and PostgreSQL
  - Programmatic configuration (no persistence.xml required)
  - Automatic dialect driver download and management
  - Migration system for schema evolution

- **Model<T>**: ORM-style model class
  - Static methods for queries (findOne, findAll, create, destroy, etc.)
  - Instance methods for backward compatibility
  - Automatic provider injection via reflection

- **Migration System**: Database schema migration framework
  - Database-agnostic migrations using JPA
  - Automatic migration execution on database initialization
  - Database-specific utilities for common migration tasks

## Dependencies

- **SQLite JDBC**: SQLite database driver
- **Hibernate ORM**: JPA implementation for database operations
- **Jakarta Persistence API**: Standard JPA API

## Building

### Build with Maven

```bash
cd mdatabase
mvn clean install
```

### Build with marn

```bash
cd mdatabase
marn build
marn link  # Install to local Maven repository
```

**IMPORTANT**: This project must be installed to your local Maven repository (`~/.m2/repository`) before other projects can use it.

## Usage

### Adding Dependency

Add `mdatabase` as a dependency in your plugin's `pom.xml`:

```xml
<dependency>
    <groupId>com.machina</groupId>
    <artifactId>mdatabase</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Creating a Database Provider

Each plugin should extend `SQLDatabaseProvider` and register its models:

```java
package com.machina.myplugin.providers.database;

import com.machina.myplugin.database.models.MyModel;
import com.machina.mdatabase.providers.database.DatabaseDialect;
import com.machina.mdatabase.providers.database.SQLDatabaseProvider;

public class SQLDatabaseProvider extends SQLDatabaseProvider {
    public SQLDatabaseProvider() {
        super(
            DatabaseDialect.SQLITE,
            "path/to/database.db"
        );

        // Register all models
        registerModel(MyModel.class);

        // Register migrations (in order)
        registerMigration(new MyMigration());
    }
}
```

### Using Models

Models should extend `Model<T>` and use static methods for queries:

```java
package com.machina.myplugin.database.models;

import com.machina.mdatabase.database.Model;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;

@Entity
public class MyModel extends Model<MyModel> {
    @Id
    public UUID uuid;
    public String name;

    public MyModel() {
        super(MyModel.class);
    }

    // Use static methods for queries
    public static MyModel findByName(String name) {
        return Model.findOne(MyModel.class, Map.of("name", name));
    }
}
```

### Database Migrations

Create migrations by implementing the `Migration` interface:

```java
package com.machina.myplugin.database.migrations;

import com.machina.mdatabase.providers.database.DatabaseMigrationUtils;
import com.machina.mdatabase.providers.database.Migration;
import jakarta.persistence.EntityManager;
import javax.annotation.Nonnull;

public class AddColumnMigration implements Migration {
    @Override
    @Nonnull
    public String getId() {
        return "myplugin-001-add-column";
    }

    @Override
    @Nonnull
    public String getDescription() {
        return "Add new column to table";
    }

    @Override
    public boolean shouldRun(@Nonnull EntityManager em) {
        return !DatabaseMigrationUtils.columnExists(em, "myTable", "newColumn");
    }

    @Override
    public void execute(@Nonnull EntityManager em) {
        // Migration logic here
        // Use JPA for database-agnostic operations
    }
}
```

## Project Structure

### Database Provider Architecture

1. **SQLDatabaseProvider**: All projects should use the `SQLDatabaseProvider` abstract class from `mdatabase` for SQL database operations (SQLite, MySQL, PostgreSQL). For future MongoDB support, it will be a completely separate implementation.

2. **Provider Structure**:
   - Database providers should be in `root/providers/database/`
   - Each project should have its own provider that extends `SQLDatabaseProvider` from mdatabase
   - Example: `SQLDatabaseProvider` in mauth (extends `com.machina.mdatabase.providers.database.SQLDatabaseProvider`), `EconomyDatabaseProvider` in meconomy

3. **Model Structure**:
   - Database models should be in `root/database/models/`
   - Models should be named `*Model` in both filename and class name
   - Examples: `AccountModel`, `SessionModel`, `AreaModel`, `RegionModel`
   - Models should use the `Model<T>` class from mdatabase that connects with the active provider

4. **Don't Separate Database and Model**: The idea of having a separate "database" and "model" is incorrect. It's easier to just have the model connect with the provider that's active for that plugin.

### Naming Conventions

1. **Providers**: 
   - SQL-specific database providers: `SQLDatabaseProvider` in each project (e.g., `com.machina.mauth.providers.database.SQLDatabaseProvider`)
   - Feature providers: `SQLiteBased[Feature]` (e.g., `SQLiteBasedAuthenticator`, `SQLiteBasedProvider`)

2. **Models**: Always end with `Model` (e.g., `AccountModel`, `AreaModel`, `RegionModel`)

3. **Entity Classes**: Use descriptive and consistent names

## Database Support

### Supported Databases

- **SQLite**: Full support (default for most plugins)
- **MySQL**: Full support
- **PostgreSQL**: Full support

### Future Support

- **MongoDB**: Will be implemented as a completely separate provider (not in mdatabase)

## License

This project is part of the Machina plugin ecosystem.
