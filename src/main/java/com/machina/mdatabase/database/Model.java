package com.machina.mdatabase.database;

import com.machina.mdatabase.providers.database.SQLDatabaseProvider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base model class that provides ORM functionality
 * Models can use static methods for queries, or instance methods for operations
 * The provider is injected via reflection when models are registered
 * @param <T> The entity type
 */
public class Model<T> {
    /**
     * The database provider (injected via reflection)
     */
    protected static SQLDatabaseProvider provider;

    /**
     * The database provider (for instance methods, kept for backward compatibility)
     */
    protected final SQLDatabaseProvider instanceProvider;

    /**
     * The entity class (for instance methods, kept for backward compatibility)
     */
    protected final Class<T> entityClass;

    /**
     * Constructor (for backward compatibility)
     * @param provider The database provider to use
     * @param entityClass The entity class
     */
    public Model(SQLDatabaseProvider provider, Class<T> entityClass) {
        this.instanceProvider = provider;
        this.entityClass = entityClass;
    }

    /**
     * Default constructor for models that extend Model<T>
     * @param entityClass The entity class
     */
    protected Model(Class<T> entityClass) {
        this.instanceProvider = null;
        this.entityClass = entityClass;
    }

    // ========== STATIC METHODS (ORM-style) ==========

    /**
     * Find one entity matching the options
     * Calls findAll with limit 1
     * @param modelClass The model class
     * @param options Find options
     * @return The entity, or null if not found
     */
    public static <T> T findOne(Class<T> modelClass, FindOptions options) {
        if (provider == null) {
            throw new RuntimeException("Provider not registered. Call SQLDatabaseProvider.registerModel() first.");
        }

        if (options == null) {
            options = new FindOptions();
        }

        // Set limit to 1 for findOne
        FindOptions findOneOptions = new FindOptions(options.where);
        findOneOptions.attributes = options.attributes;
        findOneOptions.limit = 1;
        findOneOptions.skip = options.skip;

        List<T> results = findAll(modelClass, findOneOptions);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Find one entity matching the options (convenience method)
     * @param modelClass The model class
     * @param where Where conditions
     * @return The entity, or null if not found
     */
    public static <T> T findOne(Class<T> modelClass, Op<?> where) {
        return findOne(modelClass, FindOptions.where(where));
    }

    /**
     * Find one entity matching the options (convenience method)
     * @param modelClass The model class
     * @param where Where conditions
     * @return The entity, or null if not found
     */
    public static <T> T findOne(Class<T> modelClass, Map<String, Object> where) {
        return findOne(modelClass, FindOptions.where(where));
    }

    /**
     * Find all entities matching the options
     * @param modelClass The model class
     * @param options Find options
     * @return List of entities
     */
    public static <T> List<T> findAll(Class<T> modelClass, FindOptions options) {
        if (provider == null) {
            throw new RuntimeException("Provider not registered. Call SQLDatabaseProvider.registerModel() first.");
        }

        try {
            EntityManager em = provider.getEntityManager();
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<T> query = cb.createQuery(modelClass);
            Root<T> root = query.from(modelClass);

            // Apply where conditions
            if (options != null && options.where != null && !options.where.isEmpty()) {
                List<Predicate> predicates = new ArrayList<>();

                for (Map.Entry<String, Object> entry : options.where.entrySet()) {
                    String fieldName = entry.getKey();
                    Object value = entry.getValue();

                    // If the field name is "$", `value` can only be an operator
                    if (fieldName.equals("$")) {
                        if (!(value instanceof Op<?>)) {
                            throw new RuntimeException("Where condition must be an operator");
                        }
                    }

                    // If the value is an operator
                    if (value instanceof Op) {
                        // Apply it
                        ((Op<?>) value).apply(query, cb, root, fieldName, value);
                    } else {
                        // Convert value to appropriate type
                        Object convertedValue = convertValue(value, getFieldType(modelClass, fieldName));
                        predicates.add(cb.equal(root.get(fieldName), convertedValue));
                    }
                }

                query.where(cb.and(predicates.toArray(new Predicate[0])));
            }

            // Note: attributes selection is not fully supported yet
            // For now, we select all fields. Full attribute selection would require
            // knowing the constructor signature of the model class
            // TODO: Implement proper attribute selection

            jakarta.persistence.TypedQuery<T> typedQuery = em.createQuery(query);

            // Apply limit
            if (options != null && options.limit != null) {
                typedQuery.setMaxResults(options.limit);
            }

            // Apply skip/offset
            if (options != null && options.skip != null) {
                typedQuery.setFirstResult(options.skip);
            }

            return typedQuery.getResultList();
        } catch (Exception e) {
            throw new RuntimeException("Unable to find all entities", e);
        }
    }

    /**
     * Find all entities (convenience method)
     * @param modelClass The model class
     * @return List of all entities
     */
    public static <T> List<T> findAll(Class<T> modelClass) {
        return findAll(modelClass, new FindOptions());
    }

    /**
     * Create a new entity
     * @param modelClass The model class
     * @param entity The entity to create
     * @return The created entity
     */
    public static <T> T create(Class<T> modelClass, T entity) {
        if (provider == null) {
            throw new RuntimeException("Provider not registered. Call SQLDatabaseProvider.registerModel() first.");
        }

        provider.append(entity);
        return entity;
    }

    /**
     * Destroy/delete an entity
     * @param modelClass The model class
     * @param entity The entity to delete
     */
    public static <T> void destroy(Class<T> modelClass, T entity) {
        if (provider == null) {
            throw new RuntimeException("Provider not registered. Call SQLDatabaseProvider.registerModel() first.");
        }

        try {
            EntityManager em = provider.getEntityManager();
            var transaction = em.getTransaction();
            transaction.begin();
            try {
                em.remove(em.contains(entity) ? entity : em.merge(entity));
                transaction.commit();
            } catch (Exception e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw new RuntimeException("Unable to destroy entity", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to destroy entity", e);
        }
    }

    /**
     * Find entity by primary key
     * @param modelClass The model class
     * @param pk The primary key value
     * @return The entity, or null if not found
     */
    public static <T> T findByPk(Class<T> modelClass, Object pk) {
        // Try to find @Id field
        try {
            Field[] fields = modelClass.getDeclaredFields();
            for (Field field : fields) {
                if (field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    Map<String, Object> where = new HashMap<>();
                    where.put(field.getName(), pk);
                    return findOne(modelClass, where);
                }
            }
        } catch (Exception e) {
            // Fall through
        }

        // If no @Id found, try "uuid" or "id" field
        Map<String, Object> where = new HashMap<>();
        try {
            modelClass.getDeclaredField("uuid");
            where.put("uuid", pk);
        } catch (NoSuchFieldException e) {
            try {
                modelClass.getDeclaredField("id");
                where.put("id", pk);
            } catch (NoSuchFieldException e2) {
                throw new RuntimeException("No primary key field found in " + modelClass.getName());
            }
        }

        return findOne(modelClass, where);
    }

    /**
     * Bulk update entities
     * @param modelClass The model class
     * @param where Where conditions
     * @param updates Update values (field name -> new value)
     * @return Number of updated entities
     */
    public static <T> int bulkUpdate(Class<T> modelClass, Map<String, Object> where, Map<String, Object> updates) {
        if (provider == null) {
            throw new RuntimeException("Provider not registered. Call SQLDatabaseProvider.registerModel() first.");
        }

        List<T> entities = findAll(modelClass, FindOptions.where(where));
        int count = 0;

        try {
            EntityManager em = provider.getEntityManager();
            var transaction = em.getTransaction();
            transaction.begin();
            try {
                for (T entity : entities) {
                    // Apply updates via reflection
                    for (Map.Entry<String, Object> entry : updates.entrySet()) {
                        String fieldName = entry.getKey();
                        Object value = entry.getValue();
                        
                        Field field = modelClass.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        field.set(entity, value);
                    }
                    em.merge(entity);
                    count++;
                }
                transaction.commit();
            } catch (Exception e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw new RuntimeException("Unable to bulk update entities", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to bulk update entities", e);
        }

        return count;
    }

    /**
     * Bulk create entities
     * @param modelClass The model class
     * @param entities List of entities to create
     * @return List of created entities
     */
    public static <T> List<T> bulkCreate(Class<T> modelClass, List<T> entities) {
        if (provider == null) {
            throw new RuntimeException("Provider not registered. Call SQLDatabaseProvider.registerModel() first.");
        }

        try {
            EntityManager em = provider.getEntityManager();
            var transaction = em.getTransaction();
            transaction.begin();
            try {
                for (T entity : entities) {
                    em.persist(entity);
                }
                transaction.commit();
            } catch (Exception e) {
                if (transaction.isActive()) {
                    transaction.rollback();
                }
                throw new RuntimeException("Unable to bulk create entities", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to bulk create entities", e);
        }

        return entities;
    }

    /**
     * Upsert (update if exists, create if not)
     * @param modelClass The model class
     * @param entity The entity to upsert
     * @param where Where conditions to check if exists
     * @return The upserted entity
     */
    public static <T> T upsert(Class<T> modelClass, T entity, Map<String, Object> where) {
        if (provider == null) {
            throw new RuntimeException("Provider not registered. Call SQLDatabaseProvider.registerModel() first.");
        }

        T existing = findOne(modelClass, where);
        if (existing != null) {
            // Update existing - copy fields from entity to existing
            try {
                Field[] fields = modelClass.getDeclaredFields();
                for (Field field : fields) {
                    // Skip static fields and @Id fields (don't update primary key)
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || 
                        field.isAnnotationPresent(jakarta.persistence.Id.class)) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (value != null) {
                        field.set(existing, value);
                    }
                }
                
                EntityManager em = provider.getEntityManager();
                var transaction = em.getTransaction();
                transaction.begin();
                try {
                    T merged = em.merge(existing);
                    transaction.commit();
                    return merged;
                } catch (Exception e) {
                    if (transaction.isActive()) {
                        transaction.rollback();
                    }
                    throw new RuntimeException("Unable to upsert entity", e);
                }
            } catch (Exception e) {
                throw new RuntimeException("Unable to upsert entity", e);
            }
        } else {
            // Create new
            return create(modelClass, entity);
        }
    }

    /**
     * Find or create entity
     * @param modelClass The model class
     * @param where Where conditions to find
     * @param defaults Default values if creating
     * @return The found or created entity
     */
    public static <T> T findOrCreate(Class<T> modelClass, Map<String, Object> where, Map<String, Object> defaults) {
        T existing = findOne(modelClass, where);
        if (existing != null) {
            return existing;
        }

        // Create new entity with defaults
        try {
            T entity = modelClass.getDeclaredConstructor().newInstance();
            
            // Set default values via reflection
            for (Map.Entry<String, Object> entry : defaults.entrySet()) {
                String fieldName = entry.getKey();
                Object value = entry.getValue();
                
                Field field = modelClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(entity, value);
            }

            // Also set where conditions
            for (Map.Entry<String, Object> entry : where.entrySet()) {
                String fieldName = entry.getKey();
                Object value = entry.getValue();
                
                Field field = modelClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(entity, value);
            }

            return create(modelClass, entity);
        } catch (Exception e) {
            throw new RuntimeException("Unable to find or create entity", e);
        }
    }

    // ========== INSTANCE METHODS (for backward compatibility and convenience) ==========

    /**
     * Append a row to the database
     * @param entity The entity to persist
     */
    public void append(T entity) {
        if (instanceProvider != null) {
            instanceProvider.append(entity);
        } else if (provider != null) {
            provider.append(entity);
        } else {
            throw new RuntimeException("Provider not available");
        }
    }

    /**
     * Save or update a row in the database (merge if exists, persist if new)
     * @param entity The entity to save or update
     */
    public void saveOrUpdate(T entity) {
        if (instanceProvider != null) {
            instanceProvider.saveOrUpdate(entity);
        } else if (provider != null) {
            provider.saveOrUpdate(entity);
        } else {
            throw new RuntimeException("Provider not available");
        }
    }

    /**
     * Find a row by a field and value
     * @param fieldName The field name to find by
     * @param value The value to find by
     * @return The row, or null if not found
     */
    public T findByField(String fieldName, Object value) {
        if (instanceProvider != null) {
            return instanceProvider.findByField(entityClass, fieldName, value);
        } else if (provider != null) {
            return provider.findByField(entityClass, fieldName, value);
        } else {
            throw new RuntimeException("Provider not available");
        }
    }

    /**
     * Find all rows by a field and value
     * @param fieldName The field name to find by
     * @param value The value to find by
     * @return List of rows
     */
    public List<T> findAllByField(String fieldName, Object value) {
        if (instanceProvider != null) {
            return instanceProvider.findAllByField(entityClass, fieldName, value);
        } else if (provider != null) {
            return provider.findAllByField(entityClass, fieldName, value);
        } else {
            throw new RuntimeException("Provider not available");
        }
    }

    /**
     * Find a row by multiple fields and values (for composite keys)
     * @param fieldValues A map of field names to values
     * @return The row, or null if not found
     */
    public T findByFields(Map<String, Object> fieldValues) {
        if (instanceProvider != null) {
            return instanceProvider.findByFields(entityClass, fieldValues);
        } else if (provider != null) {
            return provider.findByFields(entityClass, fieldValues);
        } else {
            throw new RuntimeException("Provider not available");
        }
    }

    /**
     * Find all rows in the database (instance method - deprecated, use static findAll instead)
     * @return List of all rows
     * @deprecated Use static findAll() method instead
     */
    @Deprecated
    public List<T> findAllInstances() {
        if (instanceProvider != null) {
            return instanceProvider.findAll(entityClass);
        } else if (provider != null) {
            return provider.findAll(entityClass);
        } else {
            throw new RuntimeException("Provider not available");
        }
    }

    // ========== UTILITY METHODS ==========

    /**
     * Get the type of a field by name
     * @param entityClass The entity class
     * @param fieldName The field name
     * @return The field type
     */
    private static Class<?> getFieldType(Class<?> entityClass, String fieldName) {
        try {
            var field = entityClass.getDeclaredField(fieldName);
            return field.getType();
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + fieldName, e);
        }
    }

    /**
     * Convert a value to the appropriate type
     * @param value The value
     * @param targetType The target type
     * @return The converted value
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }

        String stringValue = value.toString();
        if (stringValue.isEmpty()) {
            return null;
        }

        if (targetType == String.class) {
            return stringValue;
        } else if (targetType == UUID.class) {
            return UUID.fromString(stringValue);
        } else if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(stringValue);
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(stringValue);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return "1".equals(stringValue) || "true".equalsIgnoreCase(stringValue);
        } else if (targetType == java.util.Date.class) {
            return new java.util.Date(Long.parseLong(stringValue));
        }

        return value;
    }
}
