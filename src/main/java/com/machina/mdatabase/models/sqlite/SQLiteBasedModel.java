package com.machina.mdatabase.models.sqlite;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQLite-based model implementation using JPA/Hibernate
 * Base class for all SQLite models across Machina plugins
 */
public class SQLiteBasedModel<T> {
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
     * The entity manager for this model instance
     */
    private final EntityManager em;

    /**
     * The type of the model rows
     */
    private final Class<T> type;

    /**
     * The persistence unit name (e.g., "mauth", "mgriefprevention")
     */
    private final String persistenceUnitName;

    /**
     * Initialize the EntityManagerFactory (singleton)
     * @param persistenceUnitName The persistence unit name from persistence.xml
     */
    private static synchronized EntityManagerFactory getEntityManagerFactory(String persistenceUnitName) {
        if (emf == null || !emf.isOpen()) {
            // Ensure data directory exists
            ensureDataDirectoryExists();
            emf = Persistence.createEntityManagerFactory(persistenceUnitName);
            
            // Register shutdown hook to close factory when JVM exits
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                closeFactory();
            }));
        }
        return emf;
    }
    
    /**
     * Ensure the data directory exists for the SQLite database
     */
    private static void ensureDataDirectoryExists() {
        try {
            File dataDir = new File("data");
            if (!dataDir.exists()) {
                boolean created = dataDir.mkdirs();
                if (!created && !dataDir.exists()) {
                    String userDir = System.getProperty("user.dir");
                    if (userDir != null) {
                        File absDataDir = new File(userDir, "data");
                        if (!absDataDir.exists()) {
                            absDataDir.mkdirs();
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create data directory", e);
        }
    }

    /**
     * Initialize SQLite PRAGMAs (only once, shared across all instances)
     */
    private static boolean pragmasInitialized = false;

    /**
     * Get or create the shared EntityManager
     */
    private static EntityManager getSharedEntityManager(String persistenceUnitName) {
        synchronized (SHARED_EM_LOCK) {
            if (sharedEm == null || !sharedEm.isOpen()) {
                sharedEm = getEntityManagerFactory(persistenceUnitName).createEntityManager();
                // Initialize PRAGMAs only once (they persist across connections)
                if (!pragmasInitialized) {
                    initializePragmas(sharedEm);
                    pragmasInitialized = true;
                }
            }
            return sharedEm;
        }
    }

    /**
     * Constructor
     * @param persistenceUnitName The persistence unit name from persistence.xml (e.g., "mauth", "mgriefprevention")
     * @param type The type of the model rows
     */
    public SQLiteBasedModel(String persistenceUnitName, Class<T> type) {
        this.persistenceUnitName = persistenceUnitName;
        this.type = type;
        // Use shared EntityManager to avoid connection pool exhaustion
        this.em = getSharedEntityManager(persistenceUnitName);
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
     * Append a row to the database
     * @param row The row to append
     */
    public void append(T row) {
        if (!em.isOpen()) {
            throw new RuntimeException("EntityManager is closed");
        }
        
        var transaction = em.getTransaction();
        transaction.begin();
        try {
            em.persist(row);
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
     * @param row The row to save or update
     */
    public void saveOrUpdate(T row) {
        if (!em.isOpen()) {
            throw new RuntimeException("EntityManager is closed");
        }
        
        var transaction = em.getTransaction();
        transaction.begin();
        try {
            em.merge(row);
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
     * @param fieldName The field name to find by
     * @param value The value to find by
     * @return The row
     */
    public T findByField(String fieldName, Object value) {
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(this.type);
            Root<T> root = query.from(this.type);

            Object convertedValue = convertValue(value.toString(), getFieldType(fieldName));

            Predicate predicate = cb.equal(root.get(fieldName), convertedValue);
            query.where(predicate);

            TypedQuery<T> typedQuery = em.createQuery(query);
            return typedQuery.getResultStream().findFirst().orElse(null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to find row by field", e);
        }
    }

    /**
     * Find all rows by a field and value
     * @param fieldName The field name to find by
     * @param value The value to find by
     * @return List of rows
     */
    public List<T> findAllByField(String fieldName, Object value) {
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(this.type);
            Root<T> root = query.from(this.type);

            Object convertedValue = convertValue(value.toString(), getFieldType(fieldName));

            Predicate predicate = cb.equal(root.get(fieldName), convertedValue);
            query.where(predicate);

            TypedQuery<T> typedQuery = em.createQuery(query);
            return typedQuery.getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Unable to find rows by field", e);
        }
    }

    /**
     * Find a row by multiple fields and values (for composite keys)
     * @param fieldValues A map of field names to values
     * @return The row
     */
    public T findByFields(Map<String, Object> fieldValues) {
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(this.type);
            Root<T> root = query.from(this.type);

            List<Predicate> predicates = new ArrayList<>();
            for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
                String fieldName = entry.getKey();
                Object value = entry.getValue();
                
                Object convertedValue = convertValue(value.toString(), getFieldType(fieldName));
                predicates.add(cb.equal(root.get(fieldName), convertedValue));
            }

            query.where(cb.and(predicates.toArray(new Predicate[0])));

            TypedQuery<T> typedQuery = em.createQuery(query);
            return typedQuery.getResultStream().findFirst().orElse(null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to find row by fields", e);
        }
    }

    /**
     * Find all rows in the table
     * @return List of all rows
     */
    public List<T> findAll() {
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(this.type);
            Root<T> root = query.from(this.type);
            query.select(root);

            TypedQuery<T> typedQuery = em.createQuery(query);
            return typedQuery.getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Unable to find all rows", e);
        }
    }

    /**
     * Get the type of a field by name
     * @param fieldName The field name
     * @return The field type
     */
    private Class<?> getFieldType(String fieldName) {
        try {
            Field field = this.type.getDeclaredField(fieldName);
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

    /**
     * Close the entity manager
     * Note: Since we use a shared EntityManager, this doesn't actually close it
     * The shared EntityManager is closed when closeFactory() is called
     */
    public void close() {
        if (em != null && em.isOpen()) {
            try {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
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
}
