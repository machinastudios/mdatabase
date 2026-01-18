package com.machina.mdatabase.providers.database;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.cfg.Configuration;

import javax.annotation.Nonnull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SQL database provider implementation
 * Supports multiple database dialects (SQLITE, MYSQL, POSTGRES)
 * Provides a unified interface for database operations regardless of the underlying database
 * Configuration is done programmatically - no persistence.xml required
 */
public class SQLDatabaseProvider {
    /**
     * The entity manager factory (shared across all instances)
     */
    private static EntityManagerFactory emf;
    
    /**
     * Shared EntityManager for all instances (to avoid connection pool exhaustion)
     */
    private static EntityManager sharedEm;
    private static final Object SHARED_EM_LOCK = new Object();

    /**
     * The database name (e.g., "auth", "griefprevention")
     */
    private final String databaseName;

    /**
     * The database dialect
     */
    private final DatabaseDialect dialect;

    /**
     * Initialize SQLite PRAGMAs (only once, shared across all instances)
     */
    private static boolean pragmasInitialized = false;

    /**
     * Registered model classes
     */
    private final Set<Class<?>> registeredModels = new HashSet<>();

    /**
     * Migration runner for database migrations
     */
    private final MigrationRunner migrationRunner = new MigrationRunner();

    /**
     * The dialect class
     */
    private Class<?> dialectClass;

    /**
     * Constructor
     * @param dialect The database dialect
     * @param databaseName The database name (e.g., "auth", "griefprevention")
     */
    public SQLDatabaseProvider(DatabaseDialect dialect, String databaseName) {
        this.dialect = dialect;
        this.databaseName = databaseName;

        // Always register MigrationRecordModel for tracking executed migrations
        registerModel(MigrationRecordModel.class);
    }

    public void initialize() {
        // Download and load Jakarta/Hibernate dependencies first
        DatabaseDialectDownloader.loadJakartaDependencies();

        // Download the dialect if it's not already downloaded
        Class<?> dialectClass = DatabaseDialectDownloader.loadDialectDriverClass(dialect);

        // If the dialect class is null, throw an exception
        if (dialectClass == null) {
            throw new RuntimeException("Failed to load dialect: " + dialect);
        }

        // Set the dialect class
        this.dialectClass = dialectClass;
    }

    /**
     * Initialize the EntityManagerFactory programmatically (no persistence.xml needed)
     */
    private synchronized void initializeEntityManagerFactory() {
        // Set the dialect classloader as context classloader for this thread
        // This is needed because Hibernate uses the context classloader to load the JDBC driver
        ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(DatabaseDialectDownloader.getDialectClassLoader());

        if (emf != null && emf.isOpen()) {
            Thread.currentThread().setContextClassLoader(originalCL);
            return;
        }

        // Create Hibernate configuration programmatically
        Configuration cfg = new Configuration();

        // Set the dialect class name
        cfg.setProperty("jakarta.persistence.jdbc.driver", dialectClass.getName());

        // Configure based on dialect
        switch (dialect) {
            case SQLITE:
                cfg.setProperty("hibernate.dialect", "org.hibernate.community.dialect.SQLiteDialect");
                cfg.setProperty("jakarta.persistence.jdbc.url", "jdbc:sqlite:" + databaseName + "?busy_timeout=5000");
                cfg.setProperty("jakarta.persistence.jdbc.user", "");
                cfg.setProperty("jakarta.persistence.jdbc.password", "");
                break;

            case MYSQL:
                cfg.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
                // URL, user, password should be set by caller
                break;

            case POSTGRES:
                cfg.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
                // URL, user, password should be set by caller
                break;
        }

        // Common Hibernate properties
        cfg.setProperty("hibernate.hbm2ddl.auto", "update");
        cfg.setProperty("hibernate.show_sql", "false");
        cfg.setProperty("hibernate.format_sql", "false");
        cfg.setProperty("hibernate.dialect_resolvers", "");
        cfg.setProperty("hibernate.connection.provider_disables_autocommit", "true");
        cfg.setProperty("hibernate.jdbc.time_zone", "UTC");

        // Connection pool settings for SQLite (single connection)
        if (dialect == DatabaseDialect.SQLITE) {
            cfg.setProperty("hibernate.connection.provider_class", 
                "org.hibernate.engine.jdbc.connections.internal.DriverManagerConnectionProviderImpl");
            cfg.setProperty("hibernate.connection.pool_size", "1");
        }

        // Register all entity classes
        for (Class<?> modelClass : registeredModels) {
            cfg.addAnnotatedClass(modelClass);
        }

        try {
            // Build the EntityManagerFactory
            emf = cfg.buildSessionFactory().unwrap(EntityManagerFactory.class);

            // Register shutdown hook to close factory when JVM exits
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                closeFactory();
            }));
        } finally {
            // Restore original classloader
            Thread.currentThread().setContextClassLoader(originalCL);
        }
    }

    /**
     * Tests the database connection
     * @return True if the connection is successful, false otherwise
     */
    public boolean testConnection() {
        try {
            getEntityManager().createNativeQuery("SELECT 1").getResultList();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get or create the shared EntityManager
     */
    private EntityManager getSharedEntityManager() {
        synchronized (SHARED_EM_LOCK) {
            // Initialize EMF if not done yet
            if (emf == null || !emf.isOpen()) {
                initializeEntityManagerFactory();
            }

            if (sharedEm == null || !sharedEm.isOpen()) {
                sharedEm = emf.createEntityManager();
            }

            return sharedEm;
        }
    }

    /**
     * Initialize SQLite PRAGMAs for better concurrency
     * @param em The EntityManager to use
     */
    private static void initializePragmas(EntityManager em) {
        try {
            var connection = em.unwrap(java.sql.Connection.class);
            boolean originalAutoCommit = connection.getAutoCommit();
            
            try {
                connection.setAutoCommit(true);
                
                try (var stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL");
                    stmt.execute("PRAGMA busy_timeout=5000");
                    stmt.execute("PRAGMA synchronous=NORMAL");
                }
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (Exception e) {
            // Ignore if already set or if there's an issue
        }
    }

    /**
     * Get the EntityManager for this provider
     * @return The EntityManager
     */
    public EntityManager getEntityManager() {
        EntityManager em = getSharedEntityManager();
        
        // Initialize PRAGMAs for SQLite if not already initialized
        if (dialect == DatabaseDialect.SQLITE && !pragmasInitialized) {
            initializePragmas(em);
            pragmasInitialized = true;
        }
        
        // Run migrations on first access
        runMigrations(em);
        
        return em;
    }

    /**
     * Register a migration to be executed
     * Migrations should be registered before getEntityManager() is called
     * @param migration The migration to register
     */
    public void registerMigration(@Nonnull Migration migration) {
        migrationRunner.registerMigration(migration);
    }

    /**
     * Register multiple migrations
     * Migrations should be registered before getEntityManager() is called
     * @param migrations The migrations to register (in order)
     */
    public void registerMigrations(@Nonnull List<Migration> migrations) {
        migrationRunner.registerMigrations(migrations);
    }

    /**
     * Whether migrations have been run for this provider instance
     */
    private boolean migrationsRun = false;

    /**
     * Run all registered migrations that should run
     * Called automatically on first getEntityManager() call
     * @param em The EntityManager to use
     */
    private void runMigrations(@Nonnull EntityManager em) {
        // Only run migrations once per provider instance
        if (migrationsRun) {
            return;
        }
        
        migrationRunner.runMigrations(em);
        migrationsRun = true;
    }

    /**
     * Get the database dialect
     * @return The database dialect
     */
    public DatabaseDialect getDialect() {
        return dialect;
    }

    /**
     * Register a model class and inject this provider into Model<T> via reflection
     * Must be called BEFORE getEntityManager() is called
     * @param modelClass The model class to register
     */
    public void registerModel(Class<?> modelClass) {
        registeredModels.add(modelClass);

        try {
            // Inject provider into Model<T> static field
            Class<?> modelBaseClass = Class.forName("com.machina.mdatabase.database.Model");
            Field providerField = modelBaseClass.getDeclaredField("provider");
            providerField.setAccessible(true);
            providerField.set(null, this);
        } catch (ClassNotFoundException e) {
            // Model class not found, that's okay
        } catch (NoSuchFieldException e) {
            // Model class doesn't have provider field, that's okay
        } catch (Exception e) {
            throw new RuntimeException("Failed to register model: " + modelClass.getName(), e);
        }
    }

    /**
     * Get all registered models
     * @return Set of registered model classes
     */
    public Set<Class<?>> getRegisteredModels() {
        return new HashSet<>(registeredModels);
    }

    /**
     * Close the provider and release all resources
     */
    public void close() {
        // Don't close shared EntityManager here - it's shared across instances
        // Just rollback any active transaction for this instance
        if (sharedEm != null && sharedEm.isOpen()) {
            try {
                // Rollback any active transaction
                if (sharedEm.getTransaction().isActive()) {
                    sharedEm.getTransaction().rollback();
                }
            } catch (Exception e) {
                // Ignore errors during close
            }
        }
    }

    /**
     * Close the EntityManagerFactory and shared EntityManager (should be called on application shutdown)
     */
    public static void closeFactory() {
        synchronized (SHARED_EM_LOCK) {
            if (sharedEm != null && sharedEm.isOpen()) {
                try {
                    // Rollback any active transaction
                    if (sharedEm.getTransaction().isActive()) {
                        sharedEm.getTransaction().rollback();
                    }
                    sharedEm.close();
                } catch (Exception e) {
                    // Ignore errors during shutdown
                }
                sharedEm = null;
            }
        }
        
        if (emf != null && emf.isOpen()) {
            try {
                emf.close();
            } catch (Exception e) {
                // Ignore errors during shutdown
            }
        }
        emf = null;
        pragmasInitialized = false;
    }

    /**
     * Append a row to the database
     * @param entity The entity to persist
     */
    public <T> void append(T entity) {
        EntityManager em = getEntityManager();

        if (!em.isOpen()) {
            throw new RuntimeException("EntityManager is closed");
        }
        
        var transaction = em.getTransaction();
        transaction.begin();

        try {
            em.persist(entity);
            transaction.commit();
        } catch (Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw new RuntimeException("Unable to append row", e);
        }
    }

    /**
     * Save or update a row in the database (merge if exists, persist if new)
     * @param entity The entity to save or update
     */
    public <T> void saveOrUpdate(T entity) {
        EntityManager em = getEntityManager();

        if (!em.isOpen()) {
            throw new RuntimeException("EntityManager is closed");
        }
        
        var transaction = em.getTransaction();
        transaction.begin();

        try {
            em.merge(entity);
            transaction.commit();
        } catch (Exception e) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw new RuntimeException("Unable to save or update row", e);
        }
    }

    /**
     * Find a row by a field and value
     * @param entityClass The entity class
     * @param fieldName The field name to find by
     * @param value The value to find by
     * @return The row, or null if not found
     */
    public <T> T findByField(Class<T> entityClass, String fieldName, Object value) {
        try {
            EntityManager em = getEntityManager();
            jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();
            jakarta.persistence.criteria.CriteriaQuery<T> query = cb.createQuery(entityClass);
            jakarta.persistence.criteria.Root<T> root = query.from(entityClass);

            // Convert value to appropriate type
            Object convertedValue = convertValue(value.toString(), getFieldType(entityClass, fieldName));

            jakarta.persistence.criteria.Predicate predicate = cb.equal(root.get(fieldName), convertedValue);
            query.where(predicate);

            jakarta.persistence.TypedQuery<T> typedQuery = em.createQuery(query);
            return typedQuery.getResultStream().findFirst().orElse(null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to find row by field", e);
        }
    }

    /**
     * Find all rows by a field and value
     * @param entityClass The entity class
     * @param fieldName The field name to find by
     * @param value The value to find by
     * @return List of rows
     */
    public <T> List<T> findAllByField(Class<T> entityClass, String fieldName, Object value) {
        try {
            EntityManager em = getEntityManager();
            jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();
            jakarta.persistence.criteria.CriteriaQuery<T> query = cb.createQuery(entityClass);
            jakarta.persistence.criteria.Root<T> root = query.from(entityClass);

            // Convert value to appropriate type
            Object convertedValue = convertValue(value.toString(), getFieldType(entityClass, fieldName));

            jakarta.persistence.criteria.Predicate predicate = cb.equal(root.get(fieldName), convertedValue);
            query.where(predicate);

            jakarta.persistence.TypedQuery<T> typedQuery = em.createQuery(query);
            return typedQuery.getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Unable to find rows by field", e);
        }
    }

    /**
     * Find a row by multiple fields and values (for composite keys)
     * @param entityClass The entity class
     * @param fieldValues A map of field names to values
     * @return The row, or null if not found
     */
    public <T> T findByFields(Class<T> entityClass, Map<String, Object> fieldValues) {
        try {
            EntityManager em = getEntityManager();
            var cb = em.getCriteriaBuilder();
            var query = cb.createQuery(entityClass);
            var root = query.from(entityClass);

            List<Predicate> predicates = new ArrayList<>();

            for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
                String fieldName = entry.getKey();
                Object value = entry.getValue();
                
                // Convert value to appropriate type
                Object convertedValue = convertValue(value.toString(), getFieldType(entityClass, fieldName));
                predicates.add(cb.equal(root.get(fieldName), convertedValue));
            }

            query.where(cb.and(predicates.toArray(new Predicate[0])));

            var typedQuery = em.createQuery(query);
            return typedQuery.getResultStream().findFirst().orElse(null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to find row by fields", e);
        }
    }

    /**
     * Find all rows in the database
     * @param entityClass The entity class
     * @return List of all rows
     */
    public <T> List<T> findAll(Class<T> entityClass) {
        try {
            EntityManager em = getEntityManager();
            jakarta.persistence.criteria.CriteriaBuilder cb = em.getCriteriaBuilder();
            jakarta.persistence.criteria.CriteriaQuery<T> query = cb.createQuery(entityClass);
            jakarta.persistence.criteria.Root<T> root = query.from(entityClass);
            jakarta.persistence.TypedQuery<T> typedQuery = em.createQuery(query);
            return typedQuery.getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Unable to find all rows", e);
        }
    }

    /**
     * Get the type of a field by name
     * @param entityClass The entity class
     * @param fieldName The field name
     * @return The field type
     */
    private Class<?> getFieldType(Class<?> entityClass, String fieldName) {
        try {
            var field = entityClass.getDeclaredField(fieldName);
            return field.getType();
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + fieldName, e);
        }
    }

    /**
     * Convert a string value to the appropriate type
     * @param value The string value
     * @param targetType The target type
     * @return The converted value
     */
    private Object convertValue(String value, Class<?> targetType) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        if (targetType == String.class) {
            return value;
        } else if (targetType == UUID.class) {
            return UUID.fromString(value);
        } else if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(value);
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(value);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } else if (targetType == java.util.Date.class) {
            return new java.util.Date(Long.parseLong(value));
        }

        return value;
    }
}
